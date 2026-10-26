package io.github.conchad.smartgarage.core.outbox

import io.github.conchad.smartgarage.core.cache.CachedRow
import io.github.conchad.smartgarage.core.cache.OutboxEntry
import io.github.conchad.smartgarage.core.cache.OutboxOp
import io.github.conchad.smartgarage.core.cache.OutboxState
import io.github.conchad.smartgarage.core.network.ApiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.time.Instant
import java.util.UUID

/** A file to upload with a write. */
class Upload(val fileName: String, val mimeType: String, val content: InputStream)

/**
 * One write as the writer hands it to the outbox. [body] is what the server gets; [localJson] is what the cache
 * shows meanwhile: the full row for a create, the changed fields for an update. [publishAs] is the document id
 * under which an [upload] should be readable offline straight away.
 */
class WriteRequest(
    val resource: String,
    val op: OutboxOp,
    val method: String,
    val targetId: String,
    val body: JsonObject,
    val localJson: JsonObject? = null,
    val vehicleId: String? = null,
    val subpath: String? = null,
    val upload: Upload? = null,
    val publishAs: String? = null,
)

/**
 * The enqueue side of the offline write queue (D-A16): records a write, applies it to the cache at once, and lets
 * failed entries be retried or dismissed. Pure Kotlin over [GarageStore] so it can be tested without Room.
 */
class Outbox(
    private val store: GarageStore,
    private val files: StagedFiles,
    private val now: () -> Instant = Instant::now,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    /** Queues the write and applies it locally. Returns the entry's key, or null when it cancelled a pending create. */
    suspend fun enqueue(request: WriteRequest): String? {
        if (request.op == OutboxOp.DELETE && cancelPendingCreate(request.resource, request.targetId)) return null

        val key = newKey()
        val filePath = request.upload?.let { files.stage(key, it.content) }
        val entry = OutboxEntry(
            key = key,
            resource = request.resource,
            op = request.op,
            method = request.method,
            subpath = request.subpath,
            targetId = request.targetId,
            vehicleId = request.vehicleId,
            body = request.body.toString(),
            localJson = request.localJson?.toString(),
            filePath = filePath,
            fileName = request.upload?.fileName,
            mimeType = request.upload?.mimeType,
            createdAt = now().toString(),
        )
        store.transaction {
            applyLocally(entry)
            store.insert(entry)
        }
        if (filePath != null && request.publishAs != null) files.publish(filePath, request.publishAs)
        return key
    }

    /**
     * Gives a FAILED entry a fresh key (the server replays a used key's answer, D-033), re-applies its change to
     * the cache and queues it again. False when it can't be retried: it depends on a create that failed.
     */
    suspend fun retry(key: String): Boolean {
        val entries = store.entries()
        val entry = entries.firstOrNull { it.key == key && it.state == OutboxState.FAILED } ?: return false
        // Temp ids the entry builds on; a create's own temp id is its product, not a dependency.
        val needed = (TempIds.findAll(entry.body) + TempIds.findAll(entry.localJson) + listOfNotNull(entry.vehicleId, entry.targetId))
            .filter(TempIds::isTemp)
            .let { if (entry.op == OutboxOp.CREATE) it - entry.targetId else it }
        val live = entries.filter { it.op == OutboxOp.CREATE && it.state != OutboxState.FAILED }.map { it.targetId }.toSet()
        if (needed.any { it !in live }) return false

        val fresh = entry.copy(
            key = newKey(),
            state = OutboxState.PENDING,
            attempts = 0,
            lastStatus = null,
            lastCode = null,
            lastError = null,
        )
        store.transaction {
            applyLocally(fresh)
            store.update(listOf(fresh))
        }
        return true
    }

    /** Drops a FAILED entry. The cache already matches the server for it. */
    suspend fun dismiss(key: String) {
        val entry = store.entries().firstOrNull { it.key == key && it.state == OutboxState.FAILED } ?: return
        store.delete(entry.seq)
        files.discard(entry.filePath)
    }

    /**
     * Takes back a create that hasn't been sent yet (still PENDING, nothing built on it), with its optimistic row.
     * False when it is in flight, already answered or referenced: then it stays queued. Used by the OBD upload worker
     * to keep a reading off mobile data when the server couldn't be reached on Wi-Fi (D-A73).
     */
    suspend fun withdraw(resource: String, id: String): Boolean = cancelPendingCreate(resource, id)

    /** Applies an entry's change to the cache, marking the row as waiting with the entry's key. */
    private suspend fun applyLocally(entry: OutboxEntry) {
        val stamp = now().toString()
        when (entry.op) {
            OutboxOp.CREATE -> {
                val json = entry.localJson ?: return
                val vehicleId = if (entry.resource == "vehicles") entry.targetId else entry.vehicleId ?: vehicleIdIn(json)
                store.upsert(listOf(CachedRow(entry.resource, entry.targetId, vehicleId, stamp, json, pendingKey = entry.key)))
            }
            OutboxOp.UPDATE -> {
                val existing = store.row(entry.resource, entry.targetId) ?: return
                val patch = entry.localJson?.let { ApiJson.parseToJsonElement(it).jsonObject }.orEmpty()
                val merged = JsonObject(ApiJson.parseToJsonElement(existing.json).jsonObject + patch)
                store.upsert(listOf(existing.copy(updatedAt = stamp, json = merged.toString(), pendingKey = entry.key)))
            }
            OutboxOp.DELETE -> store.deleteRow(entry.resource, entry.targetId)
        }
    }

    /**
     * Deleting a row that was created offline and is still waiting: forget the create (and edits to it) instead of
     * sending both. Only when nothing else depends on the temp id and nothing is in flight.
     */
    private suspend fun cancelPendingCreate(resource: String, id: String): Boolean {
        if (!TempIds.isTemp(id)) return false
        val entries = store.entries()
        val create = entries.firstOrNull { it.op == OutboxOp.CREATE && it.targetId == id } ?: return false
        if (create.state != OutboxState.PENDING) return false
        val own = entries.filter { it.targetId == id }
        val others = entries.filter { it.targetId != id && it.references(id) }
        if (others.isNotEmpty() || own.any { it.state != OutboxState.PENDING }) return false
        store.transaction {
            own.forEach { store.delete(it.seq) }
            store.deleteRow(resource, id)
        }
        own.forEach { files.discard(it.filePath) }
        return true
    }

    private fun vehicleIdIn(json: String): String? =
        ApiJson.parseToJsonElement(json).jsonObject["vehicleId"]?.jsonPrimitive?.contentOrNull
}

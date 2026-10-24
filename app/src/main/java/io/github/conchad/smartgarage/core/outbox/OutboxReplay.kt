package io.github.conchad.smartgarage.core.outbox

import io.github.conchad.smartgarage.core.cache.CachedRow
import io.github.conchad.smartgarage.core.cache.OutboxEntry
import io.github.conchad.smartgarage.core.cache.OutboxOp
import io.github.conchad.smartgarage.core.cache.OutboxState
import io.github.conchad.smartgarage.core.network.ApiClient
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.network.ApiResult
import io.github.conchad.smartgarage.core.network.ServerConnection
import io.github.conchad.smartgarage.core.network.ServerIds
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface ReplayOutcome {
    /** Nothing left to send (failed entries aside). */
    data object Done : ReplayOutcome

    /** Stopped at the first entry the server couldn't take right now; it and everything after it stay queued. */
    data class Stopped(val problem: ServerProblem) : ReplayOutcome

    data object SignedOut : ReplayOutcome
}

/** The error code the outbox records when an entry can never succeed because the create it builds on failed. */
const val DEPENDENCY_FAILED = "dependency_failed"

/** The error code recorded when a staged upload has vanished from disk. */
const val FILE_MISSING = "file_missing"

/** The server's 409 when an odometer reading isn't above the one it has (server D-046); the entry is dropped silently. */
const val ODOMETER_NOT_HIGHER = "odometer_not_higher"

/**
 * Sends the outbox in `seq` order, one entry at a time, before every pull (D-A16).
 *
 * A transient answer (unreachable, 5xx, 429, or the server still working on the same key) stops the run so order
 * is preserved; the sync's normal retry timing (D-A13) brings the next attempt. Any other 4xx is final: the entry
 * is marked FAILED, the cache is brought back in line with the server, and the run continues with the next entry.
 */
class OutboxReplay(
    private val api: ApiClient,
    private val connection: ServerConnection,
    private val store: GarageStore,
    private val files: StagedFiles,
    /** Called when a vehicle created offline gets its server id, so device-local per-vehicle prefs follow it (D-A44). */
    private val onVehicleAdopted: suspend (tempId: String, serverId: String) -> Unit = { _, _ -> },
) {
    suspend fun run(): ReplayOutcome {
        while (true) {
            // Re-read each time: an entry queued while this one was in flight should go out in the same run.
            val next = store.entries().firstOrNull { it.state != OutboxState.FAILED } ?: return ReplayOutcome.Done
            val entry = next.copy(state = OutboxState.IN_FLIGHT, attempts = next.attempts + 1)
            store.update(listOf(entry))

            val result = send(entry)
            if (result == null) {
                fail(entry, status = null, code = FILE_MISSING, message = null)?.let { return it }
                continue
            }
            when (result) {
                is ApiResult.Ok -> complete(entry, result.value)
                is ApiResult.HttpError -> when {
                    result.status == 401 -> return ReplayOutcome.SignedOut
                    result.isTransient -> {
                        store.update(listOf(entry.copy(state = OutboxState.PENDING, lastStatus = result.status, lastCode = result.code)))
                        return ReplayOutcome.Stopped(ServerProblem.Unexpected(result.status, result.message))
                    }
                    // A delete whose target is already gone has reached its goal.
                    entry.op == OutboxOp.DELETE && result.status == 404 -> complete(entry, null)
                    result.isOdometerNotHigher(entry) -> drop(entry)
                    else -> fail(entry, result.status, result.code, result.message)?.let { return it }
                }
                is ApiResult.Failed -> {
                    store.update(listOf(entry.copy(state = OutboxState.PENDING)))
                    return ReplayOutcome.Stopped(result.problem)
                }
            }
        }
    }

    private val ApiResult.HttpError.isTransient: Boolean
        get() = status == 429 || status >= 500 || (status == 409 && code == "request_in_progress")

    /**
     * The server already holds this reading or a higher one (409 `odometer_not_higher`, server D-046), as when an
     * adapter reading arrives after a manual one. Final, but nothing the user has to see or fix.
     */
    private fun ApiResult.HttpError.isOdometerNotHigher(entry: OutboxEntry): Boolean =
        status == 409 && code == ODOMETER_NOT_HIGHER && entry.resource == "odometerLogs" && entry.op == OutboxOp.CREATE

    /** Forgets an entry the server answered for good without it being a failure: no FAILED entry, no row. */
    private suspend fun drop(entry: OutboxEntry) {
        store.transaction {
            store.deleteRow(entry.resource, entry.targetId)
            store.delete(entry.seq)
        }
        files.discard(entry.filePath)
    }

    /** Null when the staged file is gone, which no retry can fix. */
    private suspend fun send(entry: OutboxEntry): ApiResult<JsonObject?>? {
        val path = WriteRoutes.path(entry)
        if (!entry.isMultipart) {
            val body = entry.body.takeIf { entry.method != "DELETE" }
            return api.write(connection, HttpMethod.parse(entry.method), path, entry.key, body)
        }
        val bytes = files.read(entry.filePath!!) ?: return null
        val fields = ApiJson.parseToJsonElement(entry.body).jsonObject
            .mapNotNull { (name, value) -> value.jsonPrimitive.contentOrNull?.let { name to it } }
            .toMap()
        return api.writeMultipart(
            connection = connection,
            path = path,
            idempotencyKey = entry.key,
            fields = fields,
            fileName = entry.fileName ?: "file",
            mimeType = entry.mimeType ?: "application/octet-stream",
            bytes = bytes,
        )
    }

    /** The server took the write: its row replaces the optimistic one and the entry is gone. */
    private suspend fun complete(entry: OutboxEntry, json: JsonObject?) {
        val serverRow = json?.let { ServerRows.toCachedRow(entry.resource, it) }
        store.transaction {
            when (entry.op) {
                OutboxOp.CREATE -> if (serverRow == null) store.deleteRow(entry.resource, entry.targetId) else adopt(entry, serverRow)
                // A schedule patched to inactive answers 204: it is no longer listed, like a delete.
                OutboxOp.UPDATE -> if (serverRow == null) store.deleteRow(entry.resource, entry.targetId) else settle(entry, serverRow)
                OutboxOp.DELETE -> store.deleteRow(entry.resource, entry.targetId)
            }
            store.delete(entry.seq)
        }
        if (entry.isMultipart) {
            // The copy published for offline viewing moves from the temp document id to the server's.
            val localDocId = if (entry.resource == "documents") entry.targetId else entry.localJson?.let { field(it, "imageDocumentId") }
            // `imageDocumentId` comes straight from the response body, not through `toCachedRow`: check it here (D-A28).
            val serverDocId = (if (entry.resource == "documents") serverRow?.id else json?.let { field(it.toString(), "imageDocumentId") })
                ?.takeIf(ServerIds::isValid)
            if (localDocId != null && serverDocId != null && ServerIds.isValid(localDocId)) files.rename(localDocId, serverDocId)
            files.discard(entry.filePath)
        }
    }

    /**
     * A created row gets its server id: the temp row goes, children and later entries that mention the temp id
     * are repointed, and the server's row takes its place. If a later edit to the same row is still queued, the
     * local version (with that edit) stays until that entry is answered.
     */
    private suspend fun adopt(entry: OutboxEntry, serverRow: CachedRow) {
        val tempId = entry.targetId
        val serverId = serverRow.id
        val local = store.row(entry.resource, tempId)
        val laterEdit = local?.pendingKey != null && local.pendingKey != entry.key
        if (tempId != serverId) {
            store.deleteRow(entry.resource, tempId, cascade = false)
            val children = store.rowsReferencing(tempId).filter { !(it.resource == entry.resource && it.id == tempId) }
            if (children.isNotEmpty()) {
                store.upsert(children.map { it.copy(vehicleId = it.vehicleId?.replace(tempId, serverId), json = it.json.replace(tempId, serverId)) })
            }
            val later = store.entries().filter { it.seq > entry.seq && it.references(tempId) }
            if (later.isNotEmpty()) store.update(later.map { it.remapped(tempId, serverId) })
            if (entry.resource == "vehicles") onVehicleAdopted(tempId, serverId)
        }
        val row = if (laterEdit && local != null) {
            local.copy(
                id = serverId,
                vehicleId = if (entry.resource == "vehicles") serverId else local.vehicleId?.replace(tempId, serverId),
                json = local.json.replace(tempId, serverId),
            )
        } else {
            serverRow
        }
        store.upsert(listOf(row))
    }

    /** An updated row takes the server's version unless a later edit to it is still queued. */
    private suspend fun settle(entry: OutboxEntry, serverRow: CachedRow) {
        val local = store.row(entry.resource, entry.targetId)
        if (local?.pendingKey == null || local.pendingKey == entry.key) store.upsert(listOf(serverRow))
    }

    /**
     * A final rejection. The entry is kept as FAILED for the user to see; the cache converges back to the server:
     * a failed create disappears (with everything that built on its temp id), a failed update or delete is
     * re-fetched by id, or a full resync is requested when that fetch doesn't settle it.
     */
    private suspend fun fail(entry: OutboxEntry, status: Int?, code: String?, message: String?): ReplayOutcome? {
        val failed = entry.copy(state = OutboxState.FAILED, lastStatus = status, lastCode = code, lastError = message)
        val discard = mutableListOf(entry.filePath)
        when (entry.op) {
            OutboxOp.CREATE -> store.transaction {
                store.deleteRow(entry.resource, entry.targetId)
                store.update(listOf(failed))
                discard += failDependents(failed)
            }
            OutboxOp.UPDATE, OutboxOp.DELETE -> store.transaction {
                store.update(listOf(failed))
                val row = store.row(entry.resource, entry.targetId)
                if (row?.pendingKey == entry.key) store.upsert(listOf(row.copy(pendingKey = null)))
            }
        }
        discard.forEach { files.discard(it) }
        if (entry.op == OutboxOp.CREATE) return null
        return when (val fetched = api.fetch(connection, WriteRoutes.itemPath(entry.resource, entry.targetId))) {
            is ApiResult.Ok -> {
                ServerRows.toCachedRow(entry.resource, fetched.value)?.let { store.upsert(listOf(it)) }
                null
            }
            is ApiResult.HttpError -> when (fetched.status) {
                401 -> ReplayOutcome.SignedOut
                404 -> {
                    store.deleteRow(entry.resource, entry.targetId)
                    null
                }
                else -> {
                    store.forgetSince()
                    null
                }
            }
            is ApiResult.Failed -> {
                store.forgetSince()
                null
            }
        }
    }

    /** Marks every later entry that builds on a failed create as FAILED too, and drops their optimistic rows. */
    private suspend fun failDependents(failed: OutboxEntry): List<String?> {
        val discard = mutableListOf<String?>()
        var frontier = setOf(failed.targetId)
        while (frontier.isNotEmpty()) {
            val dependents = store.entries().filter { e ->
                e.state != OutboxState.FAILED && e.seq > failed.seq && frontier.any { e.references(it) }
            }
            if (dependents.isEmpty()) break
            dependents.filter { it.op == OutboxOp.CREATE }.forEach { store.deleteRow(it.resource, it.targetId) }
            store.update(dependents.map { it.copy(state = OutboxState.FAILED, lastStatus = null, lastCode = DEPENDENCY_FAILED, lastError = null) })
            discard += dependents.map { it.filePath }
            frontier = dependents.filter { it.op == OutboxOp.CREATE }.map { it.targetId }.toSet()
        }
        return discard
    }

    private fun field(json: String, name: String): String? =
        runCatching { ApiJson.parseToJsonElement(json).jsonObject[name]?.jsonPrimitive?.contentOrNull }.getOrNull()
}

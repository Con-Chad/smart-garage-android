package io.github.conchad.smartgarage.core.data

import io.github.conchad.smartgarage.core.cache.OutboxOp
import io.github.conchad.smartgarage.core.model.Access
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.outbox.GarageStore
import io.github.conchad.smartgarage.core.outbox.Outbox
import io.github.conchad.smartgarage.core.outbox.StagedFiles
import io.github.conchad.smartgarage.core.outbox.TempIds
import io.github.conchad.smartgarage.core.outbox.Upload
import io.github.conchad.smartgarage.core.outbox.WriteRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID

/**
 * One profile's store, rights checks and queue for the duration of a write. Pure Kotlin over [GarageStore], so
 * [GarageWrites] can be tested with an in-memory store; the app opens one per call for the active profile.
 */
class WriteSession(
    val store: GarageStore,
    files: StagedFiles,
    /** The signed-in user, owner of anything created here. */
    val userId: String?,
    now: () -> Instant = Instant::now,
    newKey: () -> String = { UUID.randomUUID().toString() },
    /** Called after a write was queued, e.g. to start a sync. */
    val onQueued: () -> Unit = {},
) {
    val outbox = Outbox(store, files, now, newKey)
    val now: String = now().toString()

    private suspend fun access(vehicleId: String): Access? =
        store.row("vehicles", vehicleId)?.let { row -> runCatching { ApiJson.decodeFromString(Vehicle.serializer(), row.json) }.getOrNull() }?.access

    /** Null when the user may edit the vehicle (D-030: owner or EDITOR). */
    suspend fun requireEdit(vehicleId: String): WriteResult.Denied? {
        val access = access(vehicleId) ?: return WriteResult.Denied(WriteDenied.Unknown)
        return if (access.canEdit) null else WriteResult.Denied(WriteDenied.ViewOnly)
    }

    suspend fun requireOwner(vehicleId: String): WriteResult.Denied? {
        val access = access(vehicleId) ?: return WriteResult.Denied(WriteDenied.Unknown)
        return if (access.role == "OWNER") null else WriteResult.Denied(WriteDenied.OwnerOnly)
    }

    suspend fun requireRow(resource: String, id: String): WriteResult.Denied? =
        if (store.row(resource, id) == null) WriteResult.Denied(WriteDenied.Unknown) else null

    /** Edit rights on a cached row through its vehicle; a row without a vehicle needs none. */
    suspend fun requireEditOf(resource: String, id: String): WriteResult.Denied? {
        val row = store.row(resource, id) ?: return WriteResult.Denied(WriteDenied.Unknown)
        return row.vehicleId?.let { requireEdit(it) }
    }

    suspend fun create(
        resource: String,
        vehicleId: String?,
        body: JsonObject,
        local: JsonObject = body,
        id: String = TempIds.next(),
        upload: Upload? = null,
        publishAs: String? = null,
    ): WriteResult {
        val sent = if (vehicleId != null && "vehicleId" !in body) body.with("vehicleId" to JsonPrimitive(vehicleId)) else body
        val row = local.with(
            "id" to JsonPrimitive(id),
            "vehicleId" to vehicleId?.let(::JsonPrimitive),
            "createdAt" to JsonPrimitive(local["createdAt"]?.jsonPrimitive?.contentOrNull ?: now),
            "updatedAt" to JsonPrimitive(now),
        )
        return queue(WriteRequest(resource, OutboxOp.CREATE, "POST", id, sent, row, vehicleId, upload = upload, publishAs = publishAs))
    }

    suspend fun update(
        resource: String,
        id: String,
        body: JsonObject,
        local: JsonObject = body,
        method: String = "PATCH",
        subpath: String? = null,
        upload: Upload? = null,
        publishAs: String? = null,
    ): WriteResult {
        val vehicleId = store.row(resource, id)?.vehicleId
        return queue(WriteRequest(resource, OutboxOp.UPDATE, method, id, body, local, vehicleId, subpath, upload, publishAs))
    }

    suspend fun delete(resource: String, id: String): WriteResult {
        val vehicleId = store.row(resource, id)?.vehicleId
        return queue(WriteRequest(resource, OutboxOp.DELETE, "DELETE", id, JsonObject(emptyMap()), vehicleId = vehicleId))
    }

    /** Deletes a vehicle-owned row after checking edit rights through its vehicle. */
    suspend fun deleteOwned(resource: String, id: String): WriteResult = requireEditOf(resource, id) ?: delete(resource, id)

    private suspend fun queue(request: WriteRequest): WriteResult {
        outbox.enqueue(request)
        onQueued()
        return WriteResult.Queued(request.targetId)
    }

    companion object {
        /** The object with [fields] added; a null value leaves the field as it was. */
        fun JsonObject.with(vararg fields: Pair<String, JsonElement?>): JsonObject {
            val added = fields.mapNotNull { (name, value) -> value?.let { name to it } }
            return if (added.isEmpty()) this else JsonObject(this + added)
        }

        fun JsonObject.without(key: String): JsonObject = JsonObject(filterKeys { it != key })
    }
}

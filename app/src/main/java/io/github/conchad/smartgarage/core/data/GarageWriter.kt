package io.github.conchad.smartgarage.core.data

import io.github.conchad.smartgarage.core.cache.CacheProvider
import io.github.conchad.smartgarage.core.cache.OutboxEntry
import io.github.conchad.smartgarage.core.cache.OutboxOp
import io.github.conchad.smartgarage.core.cache.OutboxState
import io.github.conchad.smartgarage.core.data.WriteSession.Companion.with
import io.github.conchad.smartgarage.core.data.WriteSession.Companion.without
import io.github.conchad.smartgarage.core.model.Access
import io.github.conchad.smartgarage.core.model.Inspection
import io.github.conchad.smartgarage.core.model.MaintenanceItem
import io.github.conchad.smartgarage.core.model.MaintenanceTemplate
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.outbox.DEPENDENCY_FAILED
import io.github.conchad.smartgarage.core.outbox.FILE_MISSING
import io.github.conchad.smartgarage.core.outbox.OutboxStores
import io.github.conchad.smartgarage.core.outbox.TempIds
import io.github.conchad.smartgarage.core.outbox.Upload
import io.github.conchad.smartgarage.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface WriteResult {
    /** Queued and already visible in the cache. [id] is the row's id: a temp id for a create until the server answers. */
    data class Queued(val id: String) : WriteResult

    /** Refused locally; nothing was queued. */
    data class Denied(val reason: WriteDenied) : WriteResult
}

enum class WriteDenied {
    NotSignedIn,

    /** The vehicle or row isn't in the cache (not synced yet, or gone). */
    Unknown,

    /** Shared with this user as a VIEWER (D-030). */
    ViewOnly,

    /** Deleting a vehicle is for its owner only. */
    OwnerOnly,
}

/** Why the server finally rejected a queued write, for the UI to put into words. */
enum class WriteFailure {
    ViewOnly,
    NotFound,

    /** The server didn't accept the values (400 or 422). [FailedWrite.message] has its reason. */
    Invalid,
    FileTooLarge,
    UnsupportedFile,

    /** Built on a create that itself failed, so it could never apply. */
    DependencyFailed,

    /** The staged upload vanished from disk. */
    FileMissing,
    Other,
}

data class FailedWrite(
    val key: String,
    val resource: String,
    val op: OutboxOp,
    val targetId: String,
    val vehicleId: String?,
    val failure: WriteFailure,
    /** The server's own message, in English; null for local failures. */
    val message: String?,
    val createdAt: Instant?,
) {
    /** [GarageWriter.retry] can only resend what doesn't depend on a failed create. */
    val retryable: Boolean get() = failure != WriteFailure.DependencyFailed
}

/**
 * The typed write API (D-A16, D-035): one call per thing the web edits. Every call checks rights against the cached
 * vehicle, applies the change to the cache at once, queues it with an Idempotency-Key and returns without touching
 * the network. [open] yields the session for the active profile, or null when nobody is signed in.
 */
open class GarageWrites(private val open: suspend () -> WriteSession?) {

    // ── Fill-ups ─────────────────────────────────────────────────────────────────────────────────────────────

    /** A non-null [FuelEntryInput.receiptDocumentId] goes to the server as-is (D-A27); null is left out of the body. */
    suspend fun addFuelEntry(vehicleId: String, input: FuelEntryInput): WriteResult = write { s ->
        s.requireEdit(vehicleId) ?: s.create("fuelEntries", vehicleId, encode(FuelEntryInput.serializer(), input))
    }

    suspend fun deleteFuelEntry(id: String): WriteResult = write { s -> s.deleteOwned("fuelEntries", id) }

    // ── Expenses ─────────────────────────────────────────────────────────────────────────────────────────────

    suspend fun addExpense(vehicleId: String, input: ExpenseInput): WriteResult = write { s ->
        s.requireEdit(vehicleId) ?: s.create("expenses", vehicleId, encode(ExpenseInput.serializer(), input))
    }

    suspend fun deleteExpense(id: String): WriteResult = write { s -> s.deleteOwned("expenses", id) }

    // ── Odometer ─────────────────────────────────────────────────────────────────────────────────────────────

    suspend fun addOdometerLog(vehicleId: String, input: OdometerInput): WriteResult = write { s ->
        val body = encode(OdometerInput.serializer(), input)
        s.requireEdit(vehicleId) ?: s.create(
            resource = "odometerLogs",
            vehicleId = vehicleId,
            body = body,
            local = body.with("recordedAt" to JsonPrimitive(input.recordedAt ?: s.now), "source" to JsonPrimitive("manual")),
        )
    }

    suspend fun deleteOdometerLog(id: String): WriteResult = write { s -> s.deleteOwned("odometerLogs", id) }

    // ── Schedules ────────────────────────────────────────────────────────────────────────────────────────────

    suspend fun addSchedule(vehicleId: String, input: ScheduleInput): WriteResult = write { s ->
        s.requireEdit(vehicleId)?.let { return@write it }
        val body = encode(ScheduleInput.serializer(), input)
        val category = input.category ?: input.templateId?.let { id ->
            s.store.row("maintenanceTemplates", id)?.let { decode(MaintenanceTemplate.serializer(), it.json) }?.category
        }
        s.create("schedules", vehicleId, body, local = body.with("category" to category?.let(::JsonPrimitive)))
    }

    suspend fun updateSchedule(id: String, patch: SchedulePatch): WriteResult = write { s ->
        s.requireEditOf("schedules", id) ?: s.update("schedules", id, encode(SchedulePatch.serializer(), patch, patch.clear))
    }

    /** Deactivates the schedule (the web's delete); its records stay. */
    suspend fun deleteSchedule(id: String): WriteResult = write { s -> s.deleteOwned("schedules", id) }

    /** Replaces the schedule's default parts and fluids; an empty list clears them. */
    suspend fun setScheduleItemDefaults(id: String, items: List<MaintenanceItem>): WriteResult = write { s ->
        s.requireEditOf("schedules", id)?.let { return@write it }
        val itemsJson = ApiJson.encodeToJsonElement(ListSerializer(MaintenanceItem.serializer()), items)
        s.update(
            resource = "schedules",
            id = id,
            body = if (items.isEmpty()) JsonObject(emptyMap()) else JsonObject(mapOf("items" to itemsJson)),
            local = JsonObject(mapOf("itemDefaults" to itemsJson)),
            method = if (items.isEmpty()) "DELETE" else "PUT",
            subpath = "item-defaults",
        )
    }

    // ── Service records ──────────────────────────────────────────────────────────────────────────────────────

    suspend fun addRecord(scheduleId: String, input: RecordInput): WriteResult = write { s ->
        val vehicleId = s.store.row("schedules", scheduleId)?.vehicleId ?: return@write WriteResult.Denied(WriteDenied.Unknown)
        s.requireEdit(vehicleId)?.let { return@write it }
        val body = encode(RecordInput.serializer(), input).with("scheduleId" to JsonPrimitive(scheduleId))
        s.create("maintenanceRecords", vehicleId, body, local = body.without("saveAsDefault"))
    }

    suspend fun updateRecord(id: String, patch: RecordPatch): WriteResult = write { s ->
        s.requireEditOf("maintenanceRecords", id)?.let { return@write it }
        val body = encode(RecordPatch.serializer(), patch, patch.clear)
        s.update("maintenanceRecords", id, body, local = body.without("saveAsDefault"))
    }

    suspend fun deleteRecord(id: String): WriteResult = write { s -> s.deleteOwned("maintenanceRecords", id) }

    // ── Notes and wishlist (personal: no vehicle rights involved) ────────────────────────────────────────────

    suspend fun addNote(input: NoteInput): WriteResult = write { s ->
        val body = encode(NoteInput.serializer(), input)
        s.create("notes", input.vehicleId, body, local = body.with("content" to JsonPrimitive(input.content.orEmpty())))
    }

    suspend fun updateNote(id: String, patch: NotePatch): WriteResult = write { s ->
        s.requireRow("notes", id) ?: s.update("notes", id, encode(NotePatch.serializer(), patch, patch.clear))
    }

    suspend fun deleteNote(id: String): WriteResult = write { s -> s.requireRow("notes", id) ?: s.delete("notes", id) }

    suspend fun addWishlistItem(input: WishlistInput): WriteResult = write { s ->
        s.create("wishlistItems", input.vehicleId, encode(WishlistInput.serializer(), input))
    }

    suspend fun updateWishlistItem(id: String, patch: WishlistPatch): WriteResult = write { s ->
        s.requireRow("wishlistItems", id) ?: s.update("wishlistItems", id, encode(WishlistPatch.serializer(), patch, patch.clear))
    }

    suspend fun deleteWishlistItem(id: String): WriteResult = write { s ->
        s.requireRow("wishlistItems", id) ?: s.delete("wishlistItems", id)
    }

    // ── Vehicles ─────────────────────────────────────────────────────────────────────────────────────────────

    suspend fun addVehicle(input: VehicleInput): WriteResult = write { s ->
        val body = encode(VehicleInput.serializer(), input)
        val local = JsonObject(body.filterKeys { it !in SPEC_KEYS }).with(
            "ownerUserId" to JsonPrimitive(s.userId.orEmpty()),
            "access" to ApiJson.encodeToJsonElement(Access.serializer(), Access(role = "OWNER", canEdit = true)),
            "year" to JsonPrimitive(input.productionYear),
            "factorySpecs" to JsonObject(body.filterKeys { it in SPEC_KEYS }),
        )
        s.create("vehicles", vehicleId = null, body = body, local = local)
    }

    suspend fun updateVehicle(id: String, patch: VehiclePatch): WriteResult = write { s ->
        s.requireEdit(id) ?: s.update("vehicles", id, encode(VehiclePatch.serializer(), patch, patch.clear))
    }

    /** Owner only; the server deletes everything belonging to the vehicle and so does the cache. */
    suspend fun deleteVehicle(id: String): WriteResult = write { s -> s.requireOwner(id) ?: s.delete("vehicles", id) }

    /** Replaces the vehicle's photo. The image shows at once; its server document id arrives with the sync. */
    suspend fun setVehiclePhoto(id: String, fileName: String, mimeType: String, content: InputStream): WriteResult = write { s ->
        s.requireEdit(id)?.let { return@write it }
        val localImageId = TempIds.next()
        s.update(
            resource = "vehicles",
            id = id,
            body = JsonObject(emptyMap()),
            local = JsonObject(mapOf("imageDocumentId" to JsonPrimitive(localImageId))),
            method = "POST",
            subpath = "photo",
            upload = Upload(fileName, mimeType, content),
            publishAs = localImageId,
        )
    }

    // ── Inspections and insurance ────────────────────────────────────────────────────────────────────────────

    /** One inspection per vehicle and type: saving the same type again replaces it. */
    suspend fun saveInspection(vehicleId: String, input: InspectionInput): WriteResult = write { s ->
        s.requireEdit(vehicleId)?.let { return@write it }
        val existing = s.store.rowsOf("inspections", vehicleId)
            .firstOrNull { decode(Inspection.serializer(), it.json)?.type == input.type }
        s.create("inspections", vehicleId, encode(InspectionInput.serializer(), input), id = existing?.id ?: TempIds.next())
    }

    suspend fun deleteInspection(id: String): WriteResult = write { s -> s.deleteOwned("inspections", id) }

    suspend fun addInsurance(vehicleId: String, input: InsuranceInput): WriteResult = write { s ->
        s.requireEdit(vehicleId) ?: s.create("insurancePolicies", vehicleId, encode(InsuranceInput.serializer(), input))
    }

    suspend fun updateInsurance(id: String, patch: InsurancePatch): WriteResult = write { s ->
        s.requireEditOf("insurancePolicies", id)
            ?: s.update("insurancePolicies", id, encode(InsurancePatch.serializer(), patch, patch.clear))
    }

    suspend fun deleteInsurance(id: String): WriteResult = write { s -> s.deleteOwned("insurancePolicies", id) }

    // ── Documents ────────────────────────────────────────────────────────────────────────────────────────────

    /** Uploads a file for the vehicle. It opens offline right away; the upload follows with the sync. */
    suspend fun addDocument(
        vehicleId: String,
        input: DocumentInput,
        fileName: String,
        mimeType: String,
        content: InputStream,
        sizeBytes: Long = 0,
    ): WriteResult = write { s ->
        s.requireEdit(vehicleId)?.let { return@write it }
        val id = TempIds.next()
        val fields = encode(DocumentInput.serializer(), input)
        val local = fields.with(
            "purpose" to JsonPrimitive(if (input.maintenanceRecordId != null) "RECEIPT" else "OTHER"),
            "originalFilename" to JsonPrimitive(fileName),
            "mimeType" to JsonPrimitive(mimeType),
            "sizeBytes" to JsonPrimitive(sizeBytes),
        )
        s.create("documents", vehicleId, fields, local = local, id = id, upload = Upload(fileName, mimeType, content), publishAs = id)
    }

    suspend fun deleteDocument(id: String): WriteResult = write { s -> s.deleteOwned("documents", id) }

    // ── Plumbing ─────────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun write(block: suspend (WriteSession) -> WriteResult): WriteResult =
        open()?.let { block(it) } ?: WriteResult.Denied(WriteDenied.NotSignedIn)

    private companion object {
        /** Flat on the create body, nested under `factorySpecs` on the row. */
        val SPEC_KEYS = setOf("fuelType", "bodyType", "driveType", "powerKw", "powerPs", "torqueNm", "displacementCc", "engineDescription")

        fun <T> encode(serializer: KSerializer<T>, value: T, clear: Set<String> = emptySet()): JsonObject {
            val obj = ApiJson.encodeToJsonElement(serializer, value).jsonObject
            return if (clear.isEmpty()) obj else JsonObject(obj + clear.associateWith { JsonNull })
        }

        fun <T> decode(serializer: KSerializer<T>, json: String): T? =
            runCatching { ApiJson.decodeFromString(serializer, json) }.getOrNull()
    }
}

/**
 * [GarageWrites] for the active profile, plus the queue's state for the UI (D-A16). Reads stay in
 * [GarageRepository]; rows with a change still queued are listed in [pendingIds]. [entries] and [pendingIds] are
 * shared like the repository's resources (D-A22): one Room observer each, however many screens show them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GarageWriter @Inject constructor(
    private val cache: CacheProvider,
    private val profiles: ProfileStore,
    private val garage: GarageRepository,
    private val stores: OutboxStores,
    private val sync: SyncEngine,
    scope: CoroutineScope,
) : GarageWrites({ openSession(profiles, stores, sync) }) {

    private val sharing = SharingStarted.WhileSubscribed(stopTimeoutMillis = GarageRepository.STOP_TIMEOUT_MS, replayExpirationMillis = 0)

    /** The active profile's queue, in replay order. */
    val entries: Flow<List<OutboxEntry>> = garage.activeProfileId.flatMapLatest { cache.dao(it).observeOutbox() }
        .distinctUntilChanged()
        .shareIn(scope, sharing, replay = 1)

    /** Changes waiting to be sent (including one in flight). */
    val pendingCount: Flow<Int> = entries.map { list -> list.count { it.state != OutboxState.FAILED } }.distinctUntilChanged()

    /** Changes the server rejected, oldest first, until retried or dismissed. */
    val failed: Flow<List<FailedWrite>> =
        entries.map { list -> list.filter { it.state == OutboxState.FAILED }.map { it.toFailedWrite() } }.distinctUntilChanged()

    /** Ids of cached rows that show a change the server hasn't taken yet ("waiting to sync"). */
    val pendingIds: Flow<Set<String>> =
        garage.activeProfileId.flatMapLatest { cache.dao(it).observePendingIds().map { ids -> ids.toSet() } }
            .distinctUntilChanged()
            .shareIn(scope, sharing, replay = 1)

    /** Queues a FAILED entry again under a fresh key; false when it can't be (see [FailedWrite.retryable]). */
    suspend fun retry(key: String): Boolean {
        val session = openSession(profiles, stores, sync) ?: return false
        val queued = session.outbox.retry(key)
        if (queued) session.onQueued()
        return queued
    }

    /** Forgets a FAILED entry; the cache already matches the server. */
    suspend fun dismiss(key: String) {
        openSession(profiles, stores, sync)?.outbox?.dismiss(key)
    }

    private companion object {
        suspend fun openSession(profiles: ProfileStore, stores: OutboxStores, sync: SyncEngine): WriteSession? {
            val state = profiles.current()
            val active = state.active ?: return null
            if (!state.isSignedIn(active.id)) return null
            return WriteSession(
                store = stores.store(active.id),
                files = stores.files(active.id),
                userId = active.account?.userId,
                onQueued = { sync.syncSoon(active.id) },
            )
        }

        fun OutboxEntry.toFailedWrite() = FailedWrite(
            key = key,
            resource = resource,
            op = op,
            targetId = targetId,
            vehicleId = vehicleId,
            failure = when {
                lastCode == DEPENDENCY_FAILED -> WriteFailure.DependencyFailed
                lastCode == FILE_MISSING -> WriteFailure.FileMissing
                lastStatus == 403 -> WriteFailure.ViewOnly
                lastStatus == 404 -> WriteFailure.NotFound
                lastStatus == 400 || lastStatus == 422 -> WriteFailure.Invalid
                lastStatus == 413 -> WriteFailure.FileTooLarge
                lastStatus == 415 -> WriteFailure.UnsupportedFile
                else -> WriteFailure.Other
            },
            message = lastError,
            createdAt = createdAt.toInstantOrNull(),
        )
    }
}

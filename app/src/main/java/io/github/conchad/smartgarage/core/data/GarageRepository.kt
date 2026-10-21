package io.github.conchad.smartgarage.core.data

import io.github.conchad.smartgarage.core.cache.CacheProvider
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Document
import io.github.conchad.smartgarage.core.model.Expense
import io.github.conchad.smartgarage.core.model.FuelEntry
import io.github.conchad.smartgarage.core.model.Inspection
import io.github.conchad.smartgarage.core.model.InsurancePolicy
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.MaintenanceTemplate
import io.github.conchad.smartgarage.core.model.Note
import io.github.conchad.smartgarage.core.model.OdometerLog
import io.github.conchad.smartgarage.core.model.Schedule
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.WishlistItem
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read side for screens: the active profile's cached rows, decoded into typed models. Everything here works
 * offline; [SyncEngine] keeps the cache fresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class GarageRepository @Inject constructor(
    private val cache: CacheProvider,
    private val profiles: ProfileStore,
) {
    /** The signed-in active profile; switches (and re-queries) when the user changes servers. */
    val activeProfileId: Flow<String> = profiles.state
        .map { state -> state.active?.id?.takeIf { state.isSignedIn(it) } }
        .filterNotNull()
        .distinctUntilChanged()

    val vehicles: Flow<List<Vehicle>> = observe("vehicles", Vehicle.serializer())
    val templates: Flow<List<MaintenanceTemplate>> = observe("maintenanceTemplates", MaintenanceTemplate.serializer())
    val schedules: Flow<List<Schedule>> = observe("schedules", Schedule.serializer())
    val records: Flow<List<MaintenanceRecord>> = observe("maintenanceRecords", MaintenanceRecord.serializer())
    val fuelEntries: Flow<List<FuelEntry>> = observe("fuelEntries", FuelEntry.serializer())
    val odometerLogs: Flow<List<OdometerLog>> = observe("odometerLogs", OdometerLog.serializer())
    val expenses: Flow<List<Expense>> = observe("expenses", Expense.serializer())
    val inspections: Flow<List<Inspection>> = observe("inspections", Inspection.serializer())
    val insurancePolicies: Flow<List<InsurancePolicy>> = observe("insurancePolicies", InsurancePolicy.serializer())
    val documents: Flow<List<Document>> = observe("documents", Document.serializer())
    val notes: Flow<List<Note>> = observe("notes", Note.serializer())
    val wishlist: Flow<List<WishlistItem>> = observe("wishlistItems", WishlistItem.serializer())

    /** Formats values with the active server's unit, currency and time zone preferences. */
    val formatter: Flow<Formatter> = profiles.state.map { it.active?.preferences }.distinctUntilChanged().map { Formatter(it) }

    /** When the cache last synced successfully; null before the first sync. */
    val lastSyncedAt: Flow<Instant?> = activeProfileId.flatMapLatest { id ->
        cache.dao(id).observeMeta(SyncEngine.META_LAST_SYNC).map { it?.let(Instant::parse) }
    }

    private fun <T> observe(resource: String, serializer: KSerializer<T>): Flow<List<T>> =
        activeProfileId.flatMapLatest { id ->
            cache.dao(id).observe(resource).map { rows ->
                // A row the app can't decode is skipped rather than failing the whole screen.
                rows.mapNotNull { runCatching { ApiJson.decodeFromString(serializer, it.json) }.getOrNull() }
            }
        }.flowOn(Dispatchers.Default)
}

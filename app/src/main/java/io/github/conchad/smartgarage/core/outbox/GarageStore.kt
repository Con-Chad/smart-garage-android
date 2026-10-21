package io.github.conchad.smartgarage.core.outbox

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.cache.CacheDatabase
import io.github.conchad.smartgarage.core.cache.CacheProvider
import io.github.conchad.smartgarage.core.cache.CachedRow
import io.github.conchad.smartgarage.core.cache.OutboxEntry
import io.github.conchad.smartgarage.core.sync.SyncEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The cached rows and the outbox of one profile, as the write side sees them. The real one is the profile's Room
 * database; tests use an in-memory fake, like [io.github.conchad.smartgarage.core.sync.SyncStore] for pulls.
 */
interface GarageStore {
    suspend fun row(resource: String, id: String): CachedRow?

    suspend fun rowsOf(resource: String, vehicleId: String): List<CachedRow>

    /** Rows whose vehicle column or JSON mentions [id]; used to repoint children when a temp id gets its server id. */
    suspend fun rowsReferencing(id: String): List<CachedRow>

    suspend fun upsert(rows: List<CachedRow>)

    /** Removes one row; with [cascade], everything belonging to that vehicle as well (the server's cascade). */
    suspend fun deleteRow(resource: String, id: String, cascade: Boolean = resource == "vehicles")

    /** Every outbox entry in replay order. */
    suspend fun entries(): List<OutboxEntry>

    suspend fun insert(entry: OutboxEntry): Long

    suspend fun update(entries: List<OutboxEntry>)

    suspend fun delete(seq: Long)

    /** Forgets the sync watermark, so the next pull is a full resync. */
    suspend fun forgetSince()

    suspend fun <T> transaction(block: suspend () -> T): T
}

class RoomGarageStore(private val db: CacheDatabase) : GarageStore {
    private val dao = db.dao()

    override suspend fun row(resource: String, id: String) = dao.row(resource, id)
    override suspend fun rowsOf(resource: String, vehicleId: String) = dao.rowsOf(resource, vehicleId)
    override suspend fun rowsReferencing(id: String) = dao.rowsReferencing(id)
    override suspend fun upsert(rows: List<CachedRow>) = dao.upsert(rows)

    override suspend fun deleteRow(resource: String, id: String, cascade: Boolean) {
        dao.delete(resource, listOf(id))
        if (cascade) dao.deleteForVehicles(listOf(id))
    }

    override suspend fun entries() = dao.outbox()
    override suspend fun insert(entry: OutboxEntry) = dao.insertOutbox(entry)
    override suspend fun update(entries: List<OutboxEntry>) = dao.updateOutbox(entries)
    override suspend fun delete(seq: Long) = dao.deleteOutbox(seq)
    override suspend fun forgetSince() = dao.deleteMeta(SyncEngine.META_NEXT_SINCE)
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
}

/** Builds the per-profile store and staging directory the writer and the sync engine work on. */
@Singleton
class OutboxStores @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: CacheProvider,
) {
    fun store(profileId: String): GarageStore = RoomGarageStore(cache.database(profileId))
    fun files(profileId: String): StagedFiles = OutboxFiles(context, profileId)
}

package io.github.conchad.smartgarage.core.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One synced row, stored as the server's JSON (D-A12). `resource` is the `/sync` key (`vehicles`, `fuelEntries`, …).
 * Typed models are decoded at read time, so new server fields never need a migration.
 */
@Entity(tableName = "rows", primaryKeys = ["resource", "id"], indices = [Index("resource", "vehicleId")])
data class CachedRow(
    val resource: String,
    val id: String,
    /** The row's vehicle (the vehicle itself for `vehicles`); null for user-level rows like templates. */
    val vehicleId: String?,
    val updatedAt: String,
    val json: String,
)

@Entity(tableName = "meta")
data class MetaEntry(@PrimaryKey val key: String, val value: String)

@Dao
interface CacheDao {
    @Query("SELECT * FROM rows WHERE resource = :resource")
    fun observe(resource: String): Flow<List<CachedRow>>

    @Query("SELECT * FROM rows WHERE resource = :resource AND id = :id")
    fun observeOne(resource: String, id: String): Flow<CachedRow?>

    @Upsert
    suspend fun upsert(rows: List<CachedRow>)

    @Query("DELETE FROM rows WHERE resource = :resource AND id IN (:ids)")
    suspend fun delete(resource: String, ids: List<String>)

    /** Everything belonging to these vehicles, mirroring the server's cascade on vehicle delete or unshare. */
    @Query("DELETE FROM rows WHERE vehicleId IN (:vehicleIds)")
    suspend fun deleteForVehicles(vehicleIds: List<String>)

    @Query("DELETE FROM rows")
    suspend fun clearRows()

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun observeMeta(key: String): Flow<String?>

    @Upsert
    suspend fun putMeta(entry: MetaEntry)

    @Query("DELETE FROM meta")
    suspend fun clearMeta()

    /** Applies one complete sync session atomically, so a failed sync never leaves a half-updated cache. */
    @Transaction
    suspend fun applySync(
        fullResync: Boolean,
        upserts: List<CachedRow>,
        deletions: Map<String, List<String>>,
        meta: List<MetaEntry>,
    ) {
        if (fullResync) clearRows()
        upserts.chunked(500).forEach { upsert(it) }
        deletions.forEach { (resource, ids) ->
            ids.chunked(500).forEach { chunk ->
                delete(resource, chunk)
                if (resource == "vehicles") deleteForVehicles(chunk)
            }
        }
        meta.forEach { putMeta(it) }
    }
}

@Database(entities = [CachedRow::class, MetaEntry::class], version = 1, exportSchema = true)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun dao(): CacheDao
}

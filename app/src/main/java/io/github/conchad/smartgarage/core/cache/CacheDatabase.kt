package io.github.conchad.smartgarage.core.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One synced row, stored as the server's JSON (D-A12). `resource` is the `/sync` key (`vehicles`, `fuelEntries`, …).
 * Typed models are decoded at read time, so new server fields never need a migration.
 *
 * [pendingKey] is set while the row reflects a local change that hasn't reached the server yet (D-A16): the key of
 * the latest outbox entry touching it. A pull never overwrites such a row; the replay does, with the server's answer.
 */
@Entity(tableName = "rows", primaryKeys = ["resource", "id"], indices = [Index("resource", "vehicleId")])
data class CachedRow(
    val resource: String,
    val id: String,
    /** The row's vehicle (the vehicle itself for `vehicles`); null for user-level rows like templates. */
    val vehicleId: String?,
    val updatedAt: String,
    val json: String,
    val pendingKey: String? = null,
)

@Entity(tableName = "meta")
data class MetaEntry(@PrimaryKey val key: String, val value: String)

enum class OutboxOp { CREATE, UPDATE, DELETE }

enum class OutboxState { PENDING, IN_FLIGHT, FAILED }

/**
 * One queued write (D-A16). [seq] is the replay order (SQLite AUTOINCREMENT, so it is monotonic even across
 * deletions); [key] is the `Idempotency-Key` (D-033). [body] is the JSON request body, or the text fields of a
 * multipart upload when [filePath] points at the staged file. [localJson] is the optimistic row shown until the
 * server answers; it is re-applied when a failed entry is retried.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["key"], unique = true), Index("state")])
data class OutboxEntry(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val key: String,
    /** The `/sync` resource key, e.g. `fuelEntries`. */
    val resource: String,
    val op: OutboxOp,
    /** POST, PATCH, PUT or DELETE. */
    val method: String,
    /** Sub-resource under the id, e.g. `item-defaults` or `photo`. */
    val subpath: String? = null,
    /** The server id, or a temp id (`tmp-…`) for a row created offline. */
    val targetId: String,
    val vehicleId: String? = null,
    val body: String,
    val localJson: String? = null,
    val filePath: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val createdAt: String,
    val attempts: Int = 0,
    val state: OutboxState = OutboxState.PENDING,
    /** HTTP status and error code of the last answer, for FAILED entries. */
    val lastStatus: Int? = null,
    val lastCode: String? = null,
    /** The server's message for a FAILED entry (English, from the API). */
    val lastError: String? = null,
) {
    val isMultipart: Boolean get() = filePath != null
}

@Dao
interface CacheDao {
    @Query("SELECT * FROM rows WHERE resource = :resource")
    fun observe(resource: String): Flow<List<CachedRow>>

    @Query("SELECT * FROM rows WHERE resource = :resource AND id = :id")
    fun observeOne(resource: String, id: String): Flow<CachedRow?>

    @Query("SELECT * FROM rows WHERE resource = :resource AND id = :id")
    suspend fun row(resource: String, id: String): CachedRow?

    @Query("SELECT * FROM rows WHERE resource = :resource AND vehicleId = :vehicleId")
    suspend fun rowsOf(resource: String, vehicleId: String): List<CachedRow>

    /** Rows that point at [id] through their vehicle column or anywhere in their JSON (temp-id remap, D-A16). */
    @Query("SELECT * FROM rows WHERE vehicleId = :id OR json LIKE '%' || :id || '%'")
    suspend fun rowsReferencing(id: String): List<CachedRow>

    @Query("SELECT id FROM rows WHERE pendingKey IS NOT NULL")
    fun observePendingIds(): Flow<List<String>>

    @Query("SELECT resource || '/' || id FROM rows WHERE pendingKey IS NOT NULL")
    suspend fun pendingRowKeys(): List<String>

    @Upsert
    suspend fun upsert(rows: List<CachedRow>)

    @Query("DELETE FROM rows WHERE resource = :resource AND id IN (:ids)")
    suspend fun delete(resource: String, ids: List<String>)

    /** Everything belonging to these vehicles, mirroring the server's cascade on vehicle delete or unshare. */
    @Query("DELETE FROM rows WHERE vehicleId IN (:vehicleIds)")
    suspend fun deleteForVehicles(vehicleIds: List<String>)

    @Query("DELETE FROM rows")
    suspend fun clearRows()

    /** A full resync replaces what came from the server but keeps rows still waiting to be sent. */
    @Query("DELETE FROM rows WHERE pendingKey IS NULL")
    suspend fun clearSyncedRows()

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun observeMeta(key: String): Flow<String?>

    @Upsert
    suspend fun putMeta(entry: MetaEntry)

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun deleteMeta(key: String)

    @Query("DELETE FROM meta")
    suspend fun clearMeta()

    @Query("SELECT * FROM outbox ORDER BY seq")
    suspend fun outbox(): List<OutboxEntry>

    @Query("SELECT * FROM outbox ORDER BY seq")
    fun observeOutbox(): Flow<List<OutboxEntry>>

    @Insert
    suspend fun insertOutbox(entry: OutboxEntry): Long

    @Update
    suspend fun updateOutbox(entries: List<OutboxEntry>)

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun deleteOutbox(seq: Long)

    @Query("DELETE FROM outbox")
    suspend fun clearOutbox()

    /**
     * Applies one complete sync session atomically, so a failed sync never leaves a half-updated cache. Rows with a
     * local change still queued are left alone: the replay that precedes the next pull settles them (D-A16).
     */
    @Transaction
    suspend fun applySync(
        fullResync: Boolean,
        upserts: List<CachedRow>,
        deletions: Map<String, List<String>>,
        meta: List<MetaEntry>,
    ) {
        if (fullResync) clearSyncedRows()
        val locked = pendingRowKeys().toMutableSet()
        outbox().filter { it.op == OutboxOp.DELETE && it.state != OutboxState.FAILED }
            .forEach { locked += "${it.resource}/${it.targetId}" }
        upserts.filter { "${it.resource}/${it.id}" !in locked }.chunked(500).forEach { upsert(it) }
        deletions.forEach { (resource, ids) ->
            ids.chunked(500).forEach { chunk ->
                delete(resource, chunk)
                if (resource == "vehicles") deleteForVehicles(chunk)
            }
        }
        meta.forEach { putMeta(it) }
    }
}

@Database(entities = [CachedRow::class, MetaEntry::class, OutboxEntry::class], version = 2, exportSchema = true)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun dao(): CacheDao
}

package io.github.conchad.smartgarage.core.obd.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

enum class ReadingState {
    /** Kept on the phone only; the upload policy didn't pick it. */
    LOCAL,

    /** Picked for upload, waiting for an allowed network (the upload worker hands it to the outbox). */
    QUEUED,

    /** In the outbox (D-A16) under [ObdReading.outboxId]; the outbox owns delivery from here. */
    SENT,

    /** A higher reading of the same vehicle went instead. */
    SUPERSEDED,

    /** The server can't take it from this account (view-only vehicle). */
    REJECTED,
}

/**
 * Every odometer value read from an adapter, stored before anything else happens to it (D-A80). [km] is the odometer
 * in whole km; null when the codes-cleared profile had no manual reading to add [counterKm] to. [profileId] is the
 * server profile, [readAt] epoch ms, [tripId] one connection session.
 */
@Entity(tableName = "readings", indices = [Index("profileId", "state"), Index("profileId", "vehicleId")])
data class ObdReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String,
    val vehicleId: String,
    val km: Int?,
    /** The 0131 counter for the codes-cleared profile, else null. */
    val counterKm: Int? = null,
    /** The adapter's reply as received, for diagnostics. */
    val rawReply: String,
    val readAt: Long,
    val tripId: String,
    val state: ReadingState = ReadingState.LOCAL,
    /** The odometer log's id in the cache once handed to the outbox (a temp id until the server answers). */
    val outboxId: String? = null,
)

@Dao
interface ObdReadingDao {
    @Insert
    suspend fun insert(reading: ObdReading): Long

    @Query("UPDATE readings SET state = :state, outboxId = :outboxId WHERE id IN (:ids)")
    suspend fun setState(ids: List<Long>, state: ReadingState, outboxId: String?)

    @Query("SELECT * FROM readings WHERE profileId = :profileId AND state = :state ORDER BY readAt")
    suspend fun inState(profileId: String, state: ReadingState): List<ObdReading>

    @Query("SELECT COUNT(*) FROM readings WHERE profileId = :profileId AND state = 'QUEUED'")
    fun observeQueued(profileId: String): Flow<Int>

    @Query("SELECT * FROM readings WHERE profileId = :profileId ORDER BY readAt DESC LIMIT :limit")
    fun observeRecent(profileId: String, limit: Int): Flow<List<ObdReading>>

    @Query("SELECT * FROM readings WHERE profileId = :profileId ORDER BY readAt DESC LIMIT :limit")
    suspend fun recent(profileId: String, limit: Int): List<ObdReading>

    /** Old readings beyond [keep], never one still waiting to be handed to the outbox. */
    @Query(
        "DELETE FROM readings WHERE profileId = :profileId AND state != 'QUEUED' AND id NOT IN " +
            "(SELECT id FROM readings WHERE profileId = :profileId ORDER BY readAt DESC LIMIT :keep)",
    )
    suspend fun prune(profileId: String, keep: Int)

    @Query("DELETE FROM readings WHERE profileId = :profileId")
    suspend fun clear(profileId: String)
}

/**
 * The OBD readings database (D-A80). Its own file, not the profile cache (D-A12): the cache is dropped on any schema
 * change and refilled from the server, but a reading not yet uploaded exists nowhere else. So: no destructive
 * fallback here, ever. A schema change adds a `Migration` (and the exported schema in app/schemas) instead.
 */
@Database(entities = [ObdReading::class], version = 1, exportSchema = true)
abstract class ObdDatabase : RoomDatabase() {
    abstract fun readings(): ObdReadingDao

    companion object {
        const val FILE = "obd.db"
    }
}

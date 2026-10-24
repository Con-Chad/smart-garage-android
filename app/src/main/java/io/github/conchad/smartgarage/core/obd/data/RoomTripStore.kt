package io.github.conchad.smartgarage.core.obd.data

import io.github.conchad.smartgarage.core.cache.CacheDao
import io.github.conchad.smartgarage.core.model.OdometerLog
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.obd.CodesClearedAnchor
import io.github.conchad.smartgarage.core.obd.CodesClearedOffset
import io.github.conchad.smartgarage.core.obd.trip.TripStore

/**
 * The real [TripStore] for one profile: readings in [ObdDatabase], "last sent" and the anchor in [ObdSettingsStore],
 * manual odometer logs from the profile's cache. [onQueued] schedules the upload worker.
 */
class RoomTripStore(
    private val profileId: String,
    private val readings: ObdReadingDao,
    private val settings: ObdSettingsStore,
    private val cache: () -> CacheDao,
    private val onQueued: suspend (ObdSettings) -> Unit,
) : TripStore {
    override suspend fun record(reading: ObdReading): Long {
        val id = readings.insert(reading)
        readings.prune(profileId, KEEP_READINGS)
        return id
    }

    override suspend fun queue(readingId: Long, km: Int) {
        readings.setState(listOf(readingId), ReadingState.QUEUED, null)
        val updated = settings.update(profileId) { it.copy(lastSentKm = km) }
        onQueued(updated)
    }

    override suspend fun latestManual(vehicleId: String): OdometerLog? {
        val logs = cache().rowsOf("odometerLogs", vehicleId)
            .mapNotNull { runCatching { ApiJson.decodeFromString(OdometerLog.serializer(), it.json) }.getOrNull() }
        return CodesClearedOffset.latestManual(logs)
    }

    override suspend fun saveAnchor(anchor: CodesClearedAnchor) {
        settings.update(profileId) { it.copy(anchor = anchor) }
    }

    companion object {
        /** About 170 hours of driving at one reading per 5 minutes; QUEUED ones are never pruned. */
        const val KEEP_READINGS = 2_000
    }
}

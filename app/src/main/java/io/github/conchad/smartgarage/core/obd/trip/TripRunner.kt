package io.github.conchad.smartgarage.core.obd.trip

import io.github.conchad.smartgarage.core.model.OdometerLog
import io.github.conchad.smartgarage.core.obd.CodesClearedAnchor
import io.github.conchad.smartgarage.core.obd.CodesClearedOffset
import io.github.conchad.smartgarage.core.obd.ElmAdapter
import io.github.conchad.smartgarage.core.obd.ElmInit
import io.github.conchad.smartgarage.core.obd.ElmSession
import io.github.conchad.smartgarage.core.obd.ElmStatus
import io.github.conchad.smartgarage.core.obd.ElmTranscript
import io.github.conchad.smartgarage.core.obd.ElmTranscriptEntry
import io.github.conchad.smartgarage.core.obd.OdometerKind
import io.github.conchad.smartgarage.core.obd.OdometerProfile
import io.github.conchad.smartgarage.core.obd.OdometerRead
import io.github.conchad.smartgarage.core.obd.OdometerReader
import io.github.conchad.smartgarage.core.obd.UploadPolicy
import io.github.conchad.smartgarage.core.obd.data.ObdReading
import io.github.conchad.smartgarage.core.obd.link.AdapterConnection
import io.github.conchad.smartgarage.core.obd.link.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** What a trip needs from the rest of the app; the real one is Room plus the settings DataStore. */
interface TripStore {
    /** Stores the reading before anything else happens to it (D-A73); returns its id. */
    suspend fun record(reading: ObdReading): Long

    /** Picks a stored reading for upload: QUEUED, "last sent" = [km], upload worker scheduled. */
    suspend fun queue(readingId: Long, km: Int)

    /** The vehicle's newest manual odometer log in the cache, for the codes-cleared profile. */
    suspend fun latestManual(vehicleId: String): OdometerLog?

    suspend fun saveAnchor(anchor: CodesClearedAnchor)
}

/** One trip's settings, read fresh when the connection starts. */
data class TripConfig(
    val profileId: String,
    val vehicleId: String,
    val profile: OdometerProfile,
    val pollInterval: Duration,
    val policy: UploadPolicy,
    val lastSentKm: Int?,
    val anchor: CodesClearedAnchor?,
)

enum class TripEndReason {
    /** The link closed: car off and the adapter asleep, or out of range. */
    DISCONNECTED,

    /** Still connected, but the car stopped answering ([TripRunner.IGNITION_OFF_FAILURES] reads in a row). */
    IGNITION_OFF,

    /** The service or the user stopped it. */
    STOPPED,
}

sealed interface TripEvent {
    data class Initialized(val adapter: ElmAdapter) : TripEvent
    data object ReadStarted : TripEvent

    /** [km] null: codes-cleared profile without a manual reading to start from. */
    data class Reading(val km: Int?, val counterKm: Int?, val readAt: Long, val queued: Boolean) : TripEvent
    data class ReadFailed(val failure: OdometerRead.Failed) : TripEvent
    data class Ended(val reason: TripEndReason, val queuedKm: Int?) : TripEvent
}

/**
 * Remembers the newest transcript entry per command, so a reading can store its reply exactly as received (the
 * reader may send ATDPN right after it).
 */
class TranscriptTap(private val delegate: ElmTranscript?) : ElmTranscript {
    private val byCommand = ConcurrentHashMap<String, ElmTranscriptEntry>()

    @Volatile
    var last: ElmTranscriptEntry? = null
        private set

    override fun record(entry: ElmTranscriptEntry) {
        last = entry
        byCommand[entry.command] = entry
        delegate?.record(entry)
    }

    fun rawFor(command: String): String = byCommand[command.trim()]?.raw.orEmpty()
}

/**
 * One connection session = one trip (D-A73). Initializes the adapter, reads the odometer at once and then every
 * [TripConfig.pollInterval], stores every reading, and queues one for upload when [UploadPolicy] says so. The trip
 * ends when the link closes, when the car stops answering while the adapter stays up (ignition off), or when the
 * caller cancels; at the end the last reading of the trip gets the trip-end upload decision. There is no reading
 * "as the link drops": BLE gives no warning, so the last poll stands in for it.
 */
class TripRunner(
    private val config: TripConfig,
    private val store: TripStore,
    private val tap: TranscriptTap,
    private val clock: () -> Long,
    private val newTripId: () -> String = { UUID.randomUUID().toString() },
    private val onEvent: (TripEvent) -> Unit = {},
) {
    private var lastSentKm = config.lastSentKm
    private var anchor = config.anchor
    private var tripId = newTripId()
    private var tripOpen = true
    private var lastKm: Int? = null
    private var lastReadingId: Long? = null

    /** Runs until the trip ends; throws [io.github.conchad.smartgarage.core.obd.ElmInitException] if init fails. */
    suspend fun run(connection: AdapterConnection, session: ElmSession): TripEndReason {
        val adapter = ElmInit.run(session, config.profile)
        onEvent(TripEvent.Initialized(adapter))
        val reader = OdometerReader(session, config.profile, adapter.format)
        var reason = TripEndReason.STOPPED
        var failures = 0
        try {
            while (true) {
                onEvent(TripEvent.ReadStarted)
                when (val read = reader.read()) {
                    is OdometerRead.Value -> {
                        failures = 0
                        if (!tripOpen) startTrip()
                        handle(read)
                    }
                    is OdometerRead.Failed -> {
                        onEvent(TripEvent.ReadFailed(read))
                        if (read.status == ElmStatus.DISCONNECTED) {
                            reason = TripEndReason.DISCONNECTED
                            break
                        }
                        if (read.status in CAR_SILENT && ++failures == IGNITION_OFF_FAILURES && tripOpen) {
                            endTrip(TripEndReason.IGNITION_OFF)
                        }
                    }
                }
                val closed = withTimeoutOrNull(config.pollInterval) { connection.state.first { it is LinkState.Closed } }
                if (closed != null) {
                    reason = TripEndReason.DISCONNECTED
                    break
                }
            }
        } catch (e: CancellationException) {
            reason = TripEndReason.STOPPED
            throw e
        } finally {
            if (tripOpen) withContext(NonCancellable) { endTrip(reason) }
        }
        return reason
    }

    private fun startTrip() {
        tripId = newTripId()
        tripOpen = true
    }

    private suspend fun handle(read: OdometerRead.Value) {
        val readAt = clock()
        val (km, counter) = if (config.profile.kind == OdometerKind.CODES_CLEARED) {
            val step = CodesClearedOffset.step(anchor, store.latestManual(config.vehicleId), read.km)
            step.anchor?.takeIf { it != anchor }?.let {
                anchor = it
                store.saveAnchor(it)
            }
            step.odometerKm to read.km
        } else {
            read.km to null
        }
        val id = store.record(
            ObdReading(
                profileId = config.profileId,
                vehicleId = config.vehicleId,
                km = km,
                counterKm = counter,
                rawReply = tap.rawFor(config.profile.request),
                readAt = readAt,
                tripId = tripId,
            ),
        )
        var queued = false
        if (km != null) {
            lastKm = km
            lastReadingId = id
            if (config.policy.shouldUpload(km, lastSentKm, tripEnded = false)) {
                store.queue(id, km)
                lastSentKm = km
                queued = true
            }
        }
        onEvent(TripEvent.Reading(km, counter, readAt, queued))
    }

    private suspend fun endTrip(reason: TripEndReason) {
        tripOpen = false
        val km = lastKm
        val id = lastReadingId
        var queued: Int? = null
        if (km != null && id != null && config.policy.shouldUpload(km, lastSentKm, tripEnded = true)) {
            store.queue(id, km)
            lastSentKm = km
            queued = km
        }
        lastKm = null
        lastReadingId = null
        onEvent(TripEvent.Ended(reason, queued))
    }

    companion object {
        /** Reads in a row the car didn't answer before the trip counts as over while the adapter stays connected. */
        const val IGNITION_OFF_FAILURES = 3

        /** What an adapter says with the ignition off (WiCAN and OBDLink stay awake for a while). */
        private val CAR_SILENT = setOf(ElmStatus.NO_DATA, ElmStatus.UNABLE_TO_CONNECT, ElmStatus.CAN_ERROR, ElmStatus.BUS_ERROR, ElmStatus.TIMEOUT)
    }
}

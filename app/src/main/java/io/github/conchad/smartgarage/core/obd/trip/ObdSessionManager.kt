package io.github.conchad.smartgarage.core.obd.trip

import io.github.conchad.smartgarage.core.obd.CodesClearedOffset
import io.github.conchad.smartgarage.core.obd.ElmAdapter
import io.github.conchad.smartgarage.core.obd.ElmInit
import io.github.conchad.smartgarage.core.obd.ElmInitException
import io.github.conchad.smartgarage.core.obd.ElmReply
import io.github.conchad.smartgarage.core.obd.ElmSession
import io.github.conchad.smartgarage.core.obd.ElmStatus
import io.github.conchad.smartgarage.core.obd.OdometerKind
import io.github.conchad.smartgarage.core.obd.OdometerRead
import io.github.conchad.smartgarage.core.obd.OdometerReader
import io.github.conchad.smartgarage.core.obd.data.ObdDiagnostics
import io.github.conchad.smartgarage.core.obd.data.ObdSettings
import io.github.conchad.smartgarage.core.obd.link.AdapterConnection
import io.github.conchad.smartgarage.core.obd.link.AdapterTransport
import io.github.conchad.smartgarage.core.obd.link.LinkState
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import io.github.conchad.smartgarage.core.obd.link.ObdTransportKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A profile's OBD setup, read fresh for every connection. [displayMiles] is the server's distance preference. */
data class ObdSetup(val profileId: String, val settings: ObdSettings, val displayMiles: Boolean)

/** One byte of a reply in WiCAN's numbering ([io.github.conchad.smartgarage.core.obd.ObdResponse.bytes]): B[index]. */
data class IndexedByte(val index: Int, val value: Int) {
    val label: String get() = "B$index"
    val hex: String get() = "%02X".format(value)
}

/** "Read now" for the settings screen: the reply as received, its numbered bytes and what the profile makes of it. */
data class ReadNowResult(
    val command: String,
    val raw: String? = null,
    val bytes: List<IndexedByte> = emptyList(),
    /** The expression's value before unit conversion. */
    val value: Double? = null,
    /** The odometer in km (for codes-cleared: worked out from [counterKm], not saved). */
    val km: Int? = null,
    val counterKm: Int? = null,
    val failure: OdometerRead.Failed? = null,
    /** Couldn't get as far as asking: no adapter, no connection, not an ELM327. */
    val error: ObdError? = null,
    val detail: String? = null,
)

/** A raw command from the diagnostics screen. [reply] is null when no connection could be made ([error] says why). */
data class RawResult(val command: String, val reply: ElmReply? = null, val error: ObdError? = null, val detail: String? = null)

/**
 * Owns the adapter connection (D-A80), independent of any screen or service: [start] runs the standby loop (connect,
 * run a [TripRunner], and when [start]'s `keepWaiting` is set wait for the adapter again), [stop] ends it. The
 * foreground service only keeps the process alive and mirrors [status] in its notification; the settings screen's
 * [readNow] and [sendRaw] use the running session when there is one and a short one-off connection otherwise.
 * Pure Kotlin over [AdapterTransport] and [TripStore], so the whole flow tests on the JVM with the simulator.
 */
class ObdSessionManager(
    private val scope: CoroutineScope,
    private val transports: (ObdTransportKind) -> AdapterTransport?,
    private val setup: suspend () -> ObdSetup?,
    private val tripStore: (profileId: String) -> TripStore,
    private val diagnostics: ObdDiagnostics,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeout: Duration = CONNECT_TIMEOUT,
    private val retryDelay: Duration = RETRY_DELAY,
) {
    private val _status = MutableStateFlow<ObdStatus>(ObdStatus.Disconnected)
    val status: StateFlow<ObdStatus> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)

    /** Whether the standby loop runs; the foreground service stops itself when this goes false. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val loopLock = Mutex()
    private var loop: Job? = null

    private class Live(val setup: ObdSetup, val session: ElmSession, val tap: TranscriptTap) {
        @Volatile var adapter: ElmAdapter? = null
    }

    @Volatile
    private var live: Live? = null

    /** Starts the loop unless it runs. Without [keepWaiting], one connection attempt and one trip, then it stops. */
    suspend fun start(keepWaiting: Boolean = true) = loopLock.withLock {
        if (loop?.isActive == true) return@withLock
        _running.value = true
        loop = scope.launch {
            try {
                runLoop(keepWaiting)
            } finally {
                _running.value = false
                if (_status.value !is ObdStatus.Error) _status.value = ObdStatus.Disconnected
            }
        }
    }

    /** Ends the trip (its trip-end upload decision still runs) and closes the connection. */
    suspend fun stop() {
        val job = loopLock.withLock { loop.also { loop = null } } ?: return
        job.cancelAndJoin()
        _status.value = ObdStatus.Disconnected
    }

    private suspend fun runLoop(keepWaiting: Boolean) {
        while (true) {
            val s = setup()
            val problem = problemOf(s)
            if (problem != null) {
                fail(problem, null)
                return
            }
            val adapter = s!!.settings.adapter!!
            val transport = transports(adapter.transport)
            if (transport == null) {
                fail(ObdError.TRANSPORT_UNAVAILABLE, adapter.transport.name)
                return
            }
            val error = connectAndRun(s, adapter, transport.open(adapter, keepWaiting), keepWaiting)
            if (error == null) _status.value = ObdStatus.Disconnected
            if (!keepWaiting || error in FATAL) return
            delay(if (error != null) retryDelay else REARM_DELAY)
        }
    }

    /** Null after a trip, else what went wrong. */
    private suspend fun connectAndRun(s: ObdSetup, adapter: ObdAdapter, conn: AdapterConnection, waiting: Boolean): ObdError? {
        val name = adapter.name
        try {
            _status.value = ObdStatus.Connecting(name, waiting)
            diagnostics.log("Connecting to ${name ?: adapter.address}${if (waiting) " (waiting for it)" else ""}")
            when (val ready = awaitReady(conn, name, if (waiting) Duration.INFINITE else connectTimeout)) {
                null -> return fail(ObdError.CONNECT_FAILED, "no connection within $connectTimeout")
                is LinkState.Closed -> return fail(ObdError.of(ready.reason) ?: ObdError.CONNECT_FAILED, ready.detail ?: ready.reason.name)
                else -> diagnostics.log("Connected")
            }
            return coroutineScope {
                val readerScope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
                val tap = TranscriptTap(diagnostics)
                val session = ElmSession(conn.link, readerScope, tap, clock)
                val current = Live(s, session, tap)
                live = current
                val runner = TripRunner(tripConfig(s), tripStore(s.profileId), tap, clock) { event -> onTripEvent(name, current, event) }
                try {
                    val reason = runner.run(conn, session)
                    diagnostics.log("Trip ended: $reason")
                    null
                } catch (e: ElmInitException) {
                    val error = if (e.reason == ElmInitException.Reason.NOT_ELM) ObdError.NOT_ELM else ObdError.NO_REPLY
                    fail(error, e.message)
                } finally {
                    live = null
                    session.close()
                    readerScope.cancel()
                }
            }
        } finally {
            conn.close()
        }
    }

    private suspend fun awaitReady(conn: AdapterConnection, name: String?, timeout: Duration): LinkState? =
        withTimeoutOrNull(timeout) {
            conn.state
                .onEach {
                    if (it == LinkState.Pairing && _status.value !is ObdStatus.Pairing) {
                        _status.value = ObdStatus.Pairing(name)
                        diagnostics.log("Pairing requested by the adapter")
                    }
                }
                .first { it == LinkState.Ready || it is LinkState.Closed }
        }

    private fun onTripEvent(name: String?, current: Live, event: TripEvent) {
        val last = (_status.value as? ObdStatus.Connected)
        when (event) {
            is TripEvent.Initialized -> {
                current.adapter = event.adapter
                _status.value = ObdStatus.Connected(name, event.adapter.identity)
                val rejected = if (event.adapter.rejected.isEmpty()) "" else "; refused ${event.adapter.rejected.joinToString()}"
                diagnostics.log("Adapter: ${event.adapter.identity}$rejected")
            }
            TripEvent.ReadStarted -> _status.value = ObdStatus.Reading(name, last?.lastKm ?: (_status.value as? ObdStatus.Reading)?.lastKm)
            is TripEvent.Reading -> {
                _status.value = ObdStatus.Connected(
                    adapterName = name,
                    identity = current.adapter?.identity,
                    lastKm = event.km ?: lastKm(),
                    lastReadAt = event.readAt,
                )
                if (event.km == null) {
                    diagnostics.log("Counter ${event.counterKm} km since codes cleared, but no manual odometer reading to add it to")
                } else {
                    diagnostics.log("Read ${event.km} km${if (event.queued) ", queued for upload" else ""}")
                }
            }
            is TripEvent.ReadFailed -> {
                _status.value = ObdStatus.Connected(name, current.adapter?.identity, lastKm(), last?.lastReadAt)
                diagnostics.log("Read failed: ${event.failure.reason} ${event.failure.detail}")
            }
            is TripEvent.Ended -> diagnostics.log("Trip end (${event.reason})${event.queuedKm?.let { ", queued $it km" } ?: ""}")
        }
    }

    private fun lastKm(): Int? = when (val s = _status.value) {
        is ObdStatus.Connected -> s.lastKm
        is ObdStatus.Reading -> s.lastKm
        else -> null
    }

    private fun fail(error: ObdError, detail: String?): ObdError {
        _status.value = ObdStatus.Error(error, detail)
        diagnostics.log("Error: $error${detail?.let { " ($it)" } ?: ""}")
        return error
    }

    // ── Settings screen ──────────────────────────────────────────────────────────────────────────────────────

    /** Reads the odometer once; nothing is stored or uploaded. */
    suspend fun readNow(): ReadNowResult {
        live?.let { l -> l.adapter?.let { return readWith(l.setup, l.session, l.tap, it) } }
        val s = setup()
        val request = s?.settings?.profile?.resolved()?.request.orEmpty()
        return oneOff(
            s,
            failed = { error, detail -> ReadNowResult(request, error = error, detail = detail) },
        ) { session, tap ->
            val adapter = try {
                ElmInit.run(session, s!!.settings.profile.resolved())
            } catch (e: ElmInitException) {
                val error = if (e.reason == ElmInitException.Reason.NOT_ELM) ObdError.NOT_ELM else ObdError.NO_REPLY
                return@oneOff ReadNowResult(request, raw = tap.last?.raw, error = error, detail = e.message)
            }
            readWith(s!!, session, tap, adapter)
        }
    }

    /** Sends one command as typed (no init when a one-off connection is needed). */
    suspend fun sendRaw(command: String): RawResult {
        val text = command.trim()
        live?.let { return RawResult(text, it.session.send(text, RAW_TIMEOUT, retries = 0)) }
        return oneOff(setup(), failed = { error, detail -> RawResult(text, error = error, detail = detail) }) { session, _ ->
            RawResult(text, session.send(text, RAW_TIMEOUT, retries = 0))
        }
    }

    private suspend fun readWith(s: ObdSetup, session: ElmSession, tap: TranscriptTap, adapter: ElmAdapter): ReadNowResult {
        val profile = s.settings.profile.resolved()
        val read = OdometerReader(session, profile, adapter.format).read()
        val raw = tap.rawFor(profile.request).ifEmpty { null }
        return when (read) {
            is OdometerRead.Failed -> ReadNowResult(profile.request, raw = raw, failure = read)
            is OdometerRead.Value -> {
                val bytes = read.response.bytes.mapIndexed(::IndexedByte)
                if (profile.kind == OdometerKind.CODES_CLEARED) {
                    val manual = s.settings.vehicleId?.let { tripStore(s.profileId).latestManual(it) }
                    val km = CodesClearedOffset.step(s.settings.anchor, manual, read.km).odometerKm
                    ReadNowResult(profile.request, raw, bytes, read.raw, km, counterKm = read.km, error = if (km == null) ObdError.NO_MANUAL_READING else null)
                } else {
                    ReadNowResult(profile.request, raw, bytes, read.raw, read.km)
                }
            }
        }
    }

    private suspend fun <T> oneOff(
        s: ObdSetup?,
        failed: (ObdError, String?) -> T,
        block: suspend (ElmSession, TranscriptTap) -> T,
    ): T {
        val adapter = s?.settings?.adapter ?: return failed(ObdError.NO_ADAPTER, null)
        val transport = transports(adapter.transport) ?: return failed(ObdError.TRANSPORT_UNAVAILABLE, adapter.transport.name)
        val conn = transport.open(adapter, waitForDevice = false)
        try {
            diagnostics.log("One-off connection to ${adapter.name ?: adapter.address}")
            when (val ready = awaitReady(conn, adapter.name, connectTimeout)) {
                null -> return failed(ObdError.CONNECT_FAILED, "no connection within $connectTimeout")
                is LinkState.Closed -> return failed(ObdError.of(ready.reason) ?: ObdError.CONNECT_FAILED, ready.detail ?: ready.reason.name)
                else -> Unit
            }
            return coroutineScope {
                val readerScope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
                val tap = TranscriptTap(diagnostics)
                val session = ElmSession(conn.link, readerScope, tap, clock)
                try {
                    block(session, tap)
                } finally {
                    session.close()
                    readerScope.cancel()
                }
            }
        } finally {
            conn.close()
            if (_status.value is ObdStatus.Pairing && live == null && !_running.value) _status.value = ObdStatus.Disconnected
        }
    }

    private fun tripConfig(s: ObdSetup) = TripConfig(
        profileId = s.profileId,
        vehicleId = s.settings.vehicleId!!,
        profile = s.settings.profile.resolved(),
        pollInterval = s.settings.pollInterval,
        policy = s.settings.policy(s.displayMiles),
        lastSentKm = s.settings.lastSentKm,
        anchor = s.settings.anchor,
    )

    companion object {
        val CONNECT_TIMEOUT: Duration = 30.seconds

        /** After a failed attempt in standby, before the next one. */
        val RETRY_DELAY: Duration = 30.seconds

        /** After a trip, before waiting for the adapter again. */
        val REARM_DELAY: Duration = 5.seconds
        val RAW_TIMEOUT: Duration = 5.seconds

        /** Errors the user has to fix; standby stops instead of retrying. */
        private val FATAL = setOf(
            ObdError.NO_ADAPTER, ObdError.NO_VEHICLE, ObdError.PAIRING_FAILED, ObdError.NO_UART, ObdError.NOT_ELM,
            ObdError.PERMISSION_MISSING, ObdError.TRANSPORT_UNAVAILABLE, ObdError.BLUETOOTH_UNAVAILABLE,
        )

        fun problemOf(s: ObdSetup?): ObdError? = when {
            s?.settings?.adapter == null -> ObdError.NO_ADAPTER
            s.settings.vehicleId == null -> ObdError.NO_VEHICLE
            else -> null
        }

        /** Whether a [RawResult] reached the adapter at all. */
        fun RawResult.answered(): Boolean = reply != null && reply.status != ElmStatus.DISCONNECTED && reply.status != ElmStatus.TIMEOUT
    }
}

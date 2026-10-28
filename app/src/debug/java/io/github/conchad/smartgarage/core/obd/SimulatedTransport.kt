package io.github.conchad.smartgarage.core.obd

import io.github.conchad.smartgarage.core.obd.data.SimulatorSettings
import io.github.conchad.smartgarage.core.obd.link.AdapterConnection
import io.github.conchad.smartgarage.core.obd.link.CloseReason
import io.github.conchad.smartgarage.core.obd.link.LinkState
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import io.github.conchad.smartgarage.core.obd.link.SimulatorTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * "Simulated adapter" (debug builds only, D-A80): one [SimulatedElmLink] car that drives while connected and parks
 * when the link closes, on a clock that runs [SimulatorSettings.timeScale] times faster than real time. The odometer
 * carries over between connections and setting changes, so connect → read → queue → sync tests end to end.
 */
class SimulatedTransport(
    private val context: CoroutineContext,
    private val realClock: () -> Long = System::currentTimeMillis,
    private val connectDelayMillis: Long = 300,
) : SimulatorTransport {
    @Volatile
    override var settings: SimulatorSettings = SimulatorSettings()

    private var car: SimulatedElmLink? = null
    private var carSettings: SimulatorSettings? = null
    private var virtualBase = 0L
    private var realBase = 0L
    private var scale = 1.0

    /** Simulated time: real time since the car was made, times the scale. */
    private fun virtualNow(): Long = virtualBase + ((realClock() - realBase) * scale).toLong()

    override val odometerKm: Double @Synchronized get() = car?.odometerKm ?: settings.startOdometerKm

    @Synchronized
    private fun car(): SimulatedElmLink {
        val wanted = settings
        val existing = car
        if (existing != null && carSettings == wanted) return existing
        // New speed or scale: a new car where the old one stopped (or at the start value the first time).
        val odometer = existing?.odometerKm ?: wanted.startOdometerKm
        virtualBase = if (existing != null) virtualNow() else realClock()
        realBase = realClock()
        scale = wanted.timeScale.coerceAtLeast(1.0)
        return SimulatedElmLink(clock = ::virtualNow, startOdometerKm = odometer, speedKmh = wanted.speedKmh, driving = false)
            .also { car = it; carSettings = wanted }
    }

    override fun open(adapter: ObdAdapter, waitForDevice: Boolean): AdapterConnection = Connection(car())

    private inner class Connection(private val car: SimulatedElmLink) : AdapterConnection, ElmLink {
        private val scope = CoroutineScope(context + SupervisorJob(context[Job]))
        private val _state = MutableStateFlow<LinkState>(LinkState.Connecting)
        override val state: StateFlow<LinkState> = _state.asStateFlow()
        override val link: ElmLink get() = this
        private val received = Channel<ByteArray>(Channel.UNLIMITED)
        override val incoming: Flow<ByteArray> = received.receiveAsFlow()
        override val maxWriteBytes: Int get() = car.maxWriteBytes

        init {
            // Subscribed before any write, so no reply is missed.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { car.incoming.collect { received.send(it) } }
            scope.launch {
                delay(connectDelayMillis)
                synchronized(this@SimulatedTransport) { car.driving = true }
                _state.value = LinkState.Ready
            }
        }

        override suspend fun write(bytes: ByteArray) {
            if (_state.value is LinkState.Closed) return
            car.write(bytes)
        }

        override fun close() {
            if (_state.value is LinkState.Closed) return
            synchronized(this@SimulatedTransport) { car.driving = false }
            _state.value = LinkState.Closed(CloseReason.CLOSED)
            received.close()
            scope.cancel()
        }
    }
}

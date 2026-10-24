package io.github.conchad.smartgarage.core.obd.trip

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.obd.FakeTripStore
import io.github.conchad.smartgarage.core.obd.OdometerProfiles
import io.github.conchad.smartgarage.core.obd.SimulatedTransport
import io.github.conchad.smartgarage.core.obd.data.ObdDiagnostics
import io.github.conchad.smartgarage.core.obd.data.ObdSettings
import io.github.conchad.smartgarage.core.obd.data.SimulatorSettings
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import io.github.conchad.smartgarage.core.obd.link.ObdTransportKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The whole connect → init → read → queue → trip end flow against the debug simulator, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedTripTest {
    private val store = FakeTripStore()
    private var settings = ObdSettings(
        adapter = ObdAdapter.simulated(),
        vehicleId = "v1",
        profile = OdometerProfiles.HONDA_CLUSTER,
        lastSentKm = 84_200,
        simulator = SimulatorSettings(speedKmh = 60.0, timeScale = 1.0, startOdometerKm = 84_210.0),
    )

    private fun TestScope.manager(sim: SimulatedTransport): ObdSessionManager {
        sim.settings = settings.simulator
        return ObdSessionManager(
            scope = backgroundScope,
            transports = { if (it == ObdTransportKind.SIMULATED) sim else null },
            setup = { ObdSetup("p1", settings, displayMiles = true) },
            tripStore = { store },
            diagnostics = ObdDiagnostics { testScheduler.currentTime },
            clock = { testScheduler.currentTime },
        )
    }

    private fun TestScope.simulator() = SimulatedTransport(backgroundScope.coroutineContext, realClock = { testScheduler.currentTime })

    @Test
    fun `a simulated drive stores every poll, queues past 25 mi and once more at the end`() = runTest {
        val sim = simulator()
        val manager = manager(sim)

        manager.start(keepWaiting = false)
        advanceTimeBy(1.seconds); runCurrent()
        val connected = manager.status.value as ObdStatus.Connected
        assertThat(connected.identity).isEqualTo("ELM327 v1.5")
        assertThat(connected.lastKm).isEqualTo(84_210)
        assertThat(store.queued).isEmpty() // 10 km past the last upload

        // 60 km/h, a reading every 5 minutes: 5 km each. 25 mi = 40.2 km past 84 200 → the 84 245 reading.
        repeat(8) { advanceTimeBy(5.minutes); runCurrent() }
        assertThat(store.readings.map { it.km }).containsExactly(84_210, 84_215, 84_220, 84_225, 84_230, 84_235, 84_240, 84_245, 84_250).inOrder()
        assertThat(store.queuedKm).containsExactly(84_245)
        assertThat(store.readings.last().rawReply).contains("18DAF160")

        // Read now shares the running session: WiCAN-numbered bytes and the computed value.
        val now = manager.readNow()
        assertThat(now.error).isNull()
        assertThat(now.km).isEqualTo(84_250)
        assertThat(now.bytes.map { it.label }.take(3)).containsExactly("B0", "B1", "B2").inOrder()
        assertThat(now.bytes[0].hex).isEqualTo("10")
        assertThat(store.readings).hasSize(9) // nothing stored for Read now

        manager.stop()
        assertThat(manager.status.value).isEqualTo(ObdStatus.Disconnected)
        assertThat(manager.running.value).isFalse()
        assertThat(store.queuedKm).containsExactly(84_245, 84_250).inOrder()

        // Parked while disconnected: the odometer doesn't move.
        val parked = sim.odometerKm
        advanceTimeBy(60.minutes)
        assertThat(sim.odometerKm).isEqualTo(parked)
    }

    @Test
    fun `read now and raw commands work without a running session`() = runTest {
        val sim = simulator()
        val manager = manager(sim)

        val read = manager.readNow()
        assertThat(read.error).isNull()
        assertThat(read.km).isEqualTo(84_210)
        assertThat(read.raw).contains("18DAF160")
        assertThat(read.bytes.size).isAtLeast(15)

        val raw = manager.sendRaw("ATRV")
        assertThat(raw.reply?.text).isEqualTo("12.6V")
        assertThat(manager.status.value).isEqualTo(ObdStatus.Disconnected)
        assertThat(store.readings).isEmpty()
    }

    @Test
    fun `the codes-cleared profile shows the counter and asks for a manual reading`() = runTest {
        settings = settings.copy(profile = OdometerProfiles.CODES_CLEARED)
        val manager = manager(simulator())

        val read = manager.readNow()

        assertThat(read.counterKm).isEqualTo(1_234)
        assertThat(read.km).isNull()
        assertThat(read.error).isEqualTo(ObdError.NO_MANUAL_READING)
    }
}

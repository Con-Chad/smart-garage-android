package io.github.conchad.smartgarage.core.obd.trip

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.obd.FakeTripStore
import io.github.conchad.smartgarage.core.obd.OdometerProfiles
import io.github.conchad.smartgarage.core.obd.ScriptedElmLink
import io.github.conchad.smartgarage.core.obd.TestConnection
import io.github.conchad.smartgarage.core.obd.TestTransport
import io.github.conchad.smartgarage.core.obd.data.ObdDiagnostics
import io.github.conchad.smartgarage.core.obd.data.ObdSettings
import io.github.conchad.smartgarage.core.obd.link.CloseReason
import io.github.conchad.smartgarage.core.obd.link.LinkState
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import io.github.conchad.smartgarage.core.obd.link.ObdTransportKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ObdSessionManagerTest {
    private val store = FakeTripStore()
    private var settings = ObdSettings(adapter = ObdAdapter("AA:BB:CC:DD:EE:FF", "WiCAN"), vehicleId = "v1", profile = OdometerProfiles.STANDARD)
    private val diagnostics = ObdDiagnostics { 0 }

    private fun TestScope.elm() = ScriptedElmLink(backgroundScope) { command, _ ->
        when {
            command == "ATZ" || command == "ATI" -> "ELM327 v2.3\r\r>"
            command.startsWith("AT") -> "OK\r\r>"
            command == "01A6" -> "7E80641A6%08X\r\r>".format(840_000)
            else -> "NO DATA\r\r>"
        }
    }

    private fun TestScope.manager(transport: TestTransport) = ObdSessionManager(
        scope = backgroundScope,
        transports = { if (it == ObdTransportKind.BLE) transport else null },
        setup = { ObdSetup("p1", settings, displayMiles = false) },
        tripStore = { store },
        diagnostics = diagnostics,
        clock = { testScheduler.currentTime },
    )

    @Test
    fun `standby waits for the adapter again after each trip`() = runTest {
        val transport = TestTransport { TestConnection(elm(), backgroundScope) }
        val manager = manager(transport)

        manager.start(keepWaiting = true)
        runCurrent()
        assertThat(manager.status.value).isInstanceOf(ObdStatus.Connected::class.java)
        transport.opened.single().drop()
        runCurrent()
        assertThat(manager.status.value).isEqualTo(ObdStatus.Disconnected)
        advanceTimeBy(ObdSessionManager.REARM_DELAY + 1.seconds); runCurrent()
        assertThat(transport.opened).hasSize(2)
        assertThat(transport.opened.first().closed).isTrue()
        assertThat(store.readings).hasSize(2)
        assertThat(manager.running.value).isTrue()

        manager.stop()
        assertThat(manager.running.value).isFalse()
        assertThat(transport.opened.last().closed).isTrue()
        assertThat(diagnostics.connectionLog.value.map { it.message }).contains("Trip ended: DISCONNECTED")
    }

    @Test
    fun `pairing shows while bonding, then the trip runs`() = runTest {
        lateinit var conn: TestConnection
        val transport = TestTransport { TestConnection(elm(), backgroundScope, initial = LinkState.Connecting).also { conn = it } }
        val manager = manager(transport)

        manager.start(keepWaiting = false)
        runCurrent()
        assertThat(manager.status.value).isEqualTo(ObdStatus.Connecting("WiCAN", waiting = false))
        conn.mutableState.value = LinkState.Pairing
        runCurrent()
        assertThat(manager.status.value).isEqualTo(ObdStatus.Pairing("WiCAN"))
        conn.mutableState.value = LinkState.Ready
        runCurrent()
        assertThat((manager.status.value as ObdStatus.Connected).lastKm).isEqualTo(84_000)
        manager.stop()
    }

    @Test
    fun `a refused bond is an error the user has to fix, standby stops`() = runTest {
        val transport = TestTransport { TestConnection(elm(), backgroundScope, initial = LinkState.Closed(CloseReason.PAIRING_FAILED, "bond refused")) }
        val manager = manager(transport)

        manager.start(keepWaiting = true)
        runCurrent()
        assertThat(manager.status.value).isEqualTo(ObdStatus.Error(ObdError.PAIRING_FAILED, "bond refused"))
        assertThat(manager.running.value).isFalse()
        assertThat(transport.opened).hasSize(1)
    }

    @Test
    fun `no connection in time fails a direct attempt but standby retries`() = runTest {
        val transport = TestTransport { TestConnection(elm(), backgroundScope, initial = LinkState.Connecting) }
        val manager = manager(transport)

        manager.start(keepWaiting = false)
        advanceTimeBy(ObdSessionManager.CONNECT_TIMEOUT + 1.seconds); runCurrent()
        assertThat((manager.status.value as ObdStatus.Error).reason).isEqualTo(ObdError.CONNECT_FAILED)
        assertThat(manager.running.value).isFalse()

        val dropping = TestTransport { TestConnection(elm(), backgroundScope, initial = LinkState.Closed(CloseReason.CONNECT_FAILED, "133")) }
        val standby = manager(dropping)
        standby.start(keepWaiting = true)
        advanceTimeBy(ObdSessionManager.RETRY_DELAY * 2 + 1.seconds); runCurrent()
        assertThat(dropping.opened.size).isAtLeast(3)
        assertThat(standby.running.value).isTrue()
        standby.stop()
    }

    @Test
    fun `missing adapter or vehicle is reported, nothing connects`() = runTest {
        val transport = TestTransport { TestConnection(elm(), backgroundScope) }
        settings = settings.copy(vehicleId = null)
        val manager = manager(transport)

        manager.start()
        runCurrent()
        assertThat(manager.status.value).isEqualTo(ObdStatus.Error(ObdError.NO_VEHICLE))
        settings = ObdSettings()
        assertThat(manager.readNow().error).isEqualTo(ObdError.NO_ADAPTER)
        assertThat(manager.sendRaw("ATI").error).isEqualTo(ObdError.NO_ADAPTER)
        assertThat(transport.opened).isEmpty()
    }

    @Test
    fun `a simulated adapter in a build without the simulator is unavailable`() = runTest {
        settings = settings.copy(adapter = ObdAdapter.simulated())
        val manager = manager(TestTransport { TestConnection(elm(), backgroundScope) })

        manager.start()
        runCurrent()
        assertThat((manager.status.value as ObdStatus.Error).reason).isEqualTo(ObdError.TRANSPORT_UNAVAILABLE)
    }
}

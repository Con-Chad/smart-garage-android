package io.github.conchad.smartgarage.core.obd

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.model.OdometerLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The debug simulator end to end through [ElmSession], [ElmInit] and [OdometerReader]. */
class SimulatedElmLinkTest {
    private var now = 0L
    private val hour = 3_600_000L

    private suspend fun connect(scope: CoroutineScope, sim: SimulatedElmLink, profile: OdometerProfile, log: ElmTranscriptLog? = null): OdometerReader {
        val session = ElmSession(sim, scope, log, clock = { now })
        val adapter = ElmInit.run(session, profile)
        return OdometerReader(session, profile, adapter.format)
    }

    private fun km(read: OdometerRead): Int {
        assertThat(read).isInstanceOf(OdometerRead.Value::class.java)
        return (read as OdometerRead.Value).km
    }

    @Test
    fun `Honda cluster odometer rises while driving`() = runTest {
        val sim = SimulatedElmLink(clock = { now }, startOdometerKm = 84_210.0, speedKmh = 60.0)
        val log = ElmTranscriptLog()
        val reader = connect(backgroundScope, sim, OdometerProfiles.HONDA_CLUSTER, log)

        val first = reader.read() as OdometerRead.Value
        assertThat(first.km).isEqualTo(84_210)
        assertThat(first.response.ecu).isEqualTo("18DAF160")
        assertThat(first.response.bytes.take(3)).containsExactly(0x10, 0x0F, 0x62).inOrder()
        assertThat(first.response.bytes[8]).isEqualTo(0x21)

        now += hour / 2
        assertThat(km(reader.read())).isEqualTo(84_240)
        sim.driving = false
        now += hour
        assertThat(km(reader.read())).isEqualTo(84_240)

        // Writes never exceed the WiCAN Pro's 20 bytes; the request went out with its count digit.
        assertThat(sim.writes.maxOf { it.size }).isAtMost(20)
        assertThat(sim.commands).contains("2270229")
        assertThat(log.entries().first().command).isEqualTo("ATZ")
        assertThat(log.entries().last().status).isEqualTo(ElmStatus.DATA)
    }

    @Test
    fun `the same reply printed without headers reads the same bytes`() = runTest {
        val sim = SimulatedElmLink(clock = { now }, startOdometerKm = 99_999.0)
        val session = ElmSession(sim, backgroundScope)
        ElmInit.run(session, OdometerProfiles.HONDA_CLUSTER)
        val withHeaders = session.send("2270229")
        assertThat(session.send("ATH0").status).isEqualTo(ElmStatus.OK)
        val without = session.send("2270229")
        assertThat(without.lines.first()).isEqualTo("00F")

        val request = ObdRequest.parse("2270229")
        val a = (ObdReplyParser.parse(withHeaders.lines, request, ElmFormat(canIdChars = 8)) as ObdParse.Positive).response
        val b = (ObdReplyParser.parse(without.lines, request, ElmFormat(headers = false)) as ObdParse.Positive).response
        assertThat(b.bytes).isEqualTo(a.bytes.take(b.bytes.size))
        assertThat(ByteExpression.parse("[B12:B14]").evaluate(b.bytes)).isEqualTo(99_999.0)
    }

    @Test
    fun `standard PID A6 with protocol search on an 11-bit car`() = runTest {
        val sim = SimulatedElmLink(clock = { now }, startOdometerKm = 84_210.55, nativeProtocol = 6)
        val reader = connect(backgroundScope, sim, OdometerProfiles.STANDARD)
        val read = reader.read() as OdometerRead.Value
        assertThat(read.raw).isWithin(1e-9).of(84_210.5)
        assertThat(read.km).isEqualTo(84_210)
        assertThat(read.response.ecu).isEqualTo("7E8")
        // ATDPN told the reader the CAN ID width once.
        assertThat(sim.commands.count { it == "ATDPN" }).isEqualTo(1)
        reader.read()
        assertThat(sim.commands.count { it == "ATDPN" }).isEqualTo(1)
    }

    @Test
    fun `a car without A6 says NO DATA`() = runTest {
        val sim = SimulatedElmLink(clock = { now }).apply { supportsStandardOdometer = false }
        val read = connect(backgroundScope, sim, OdometerProfiles.STANDARD).read()
        assertThat(read).isEqualTo(OdometerRead.Failed(OdometerRead.Reason.ADAPTER, "NO DATA", ElmStatus.NO_DATA))
    }

    @Test
    fun `codes-cleared counter through the offset`() = runTest {
        val sim = SimulatedElmLink(clock = { now }, startOdometerKm = 84_000.0, speedKmh = 40.0, codesClearedAtKm = 83_000.0)
        val reader = connect(backgroundScope, sim, OdometerProfiles.CODES_CLEARED)
        val manual = OdometerLog("m", "v", odometerKm = 84_000, recordedAt = "2026-10-24T08:00:00Z")

        var step = CodesClearedOffset.step(null, manual, km(reader.read()))
        assertThat(step.anchor?.counterAtBase).isEqualTo(1_000)
        assertThat(step.odometerKm).isEqualTo(84_000)
        now += hour
        step = CodesClearedOffset.step(step.anchor, manual, km(reader.read()))
        assertThat(step.odometerKm).isEqualTo(84_040)
        sim.clearCodes()
        now += hour / 4
        step = CodesClearedOffset.step(step.anchor, manual, km(reader.read()))
        assertThat(step.odometerKm).isEqualTo(84_050)
    }

    @Test
    fun `injected faults - timeouts and CAN errors are retried, a refused command is not`() = runTest {
        val sim = SimulatedElmLink(clock = { now }, startOdometerKm = 50_000.0)
        val reader = connect(backgroundScope, sim, OdometerProfiles.HONDA_CLUSTER)
        sim.inject(SimulatedElmLink.Fault.TIMEOUT)
        assertThat(km(reader.read())).isEqualTo(50_000)
        sim.inject(SimulatedElmLink.Fault.CAN_ERROR)
        assertThat(km(reader.read())).isEqualTo(50_000)
        sim.inject(SimulatedElmLink.Fault.UNKNOWN_COMMAND)
        assertThat((reader.read() as OdometerRead.Failed).status).isEqualTo(ElmStatus.UNKNOWN_COMMAND)
        sim.inject(SimulatedElmLink.Fault.CAN_ERROR, SimulatedElmLink.Fault.CAN_ERROR)
        assertThat((reader.read() as OdometerRead.Failed).status).isEqualTo(ElmStatus.CAN_ERROR)
        assertThat(km(reader.read())).isEqualTo(50_000)
    }

    @Test
    fun `without the cluster header the Honda request finds nothing`() = runTest {
        val sim = SimulatedElmLink(clock = { now })
        val noHeader = OdometerProfiles.custom(OdometerProfiles.HONDA_CLUSTER).copy(headerCommand = "")
        val read = connect(backgroundScope, sim, noHeader).read() as OdometerRead.Failed
        assertThat(read.status).isEqualTo(ElmStatus.NO_DATA)
    }
}

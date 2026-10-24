package io.github.conchad.smartgarage.core.obd.trip

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.model.OdometerLog
import io.github.conchad.smartgarage.core.obd.ElmInitException
import io.github.conchad.smartgarage.core.obd.ElmSession
import io.github.conchad.smartgarage.core.obd.FakeTripStore
import io.github.conchad.smartgarage.core.obd.OdometerProfiles
import io.github.conchad.smartgarage.core.obd.ScriptedElmLink
import io.github.conchad.smartgarage.core.obd.TestConnection
import io.github.conchad.smartgarage.core.obd.UploadPolicy
import io.github.conchad.smartgarage.core.obd.data.ReadingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.minutes

/** Trip logic over a scripted adapter: what gets stored, what gets queued, when a trip ends. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripRunnerTest {
    /** 01A6 answers with [odometer] (km, ×10 on the wire) or with [reply] when set. */
    private var odometer = 84_000.0
    private var reply: String? = null
    private var counter = 1_000

    private fun TestScope.adapter() = ScriptedElmLink(backgroundScope) { command, _ ->
        when {
            command == "ATZ" -> "\r\rELM327 v1.5\r\r>"
            command == "ATI" -> "ELM327 v1.5\r\r>"
            command.startsWith("AT") -> "OK\r\r>"
            // Headers on, spaces off: CAN ID, PCI length, 41 + PID, data.
            command == "01A6" -> reply ?: "7E80641A6%08X\r\r>".format((odometer * 10).toLong())
            command == "0131" -> reply ?: "7E8044131%04X\r\r>".format(counter)
            else -> "NO DATA\r\r>"
        }
    }

    private val miles = UploadPolicy(displayMiles = true) // 25 mi = 40.2 km
    private fun config(lastSentKm: Int? = null, policy: UploadPolicy = miles, profile: io.github.conchad.smartgarage.core.obd.OdometerProfile = OdometerProfiles.STANDARD) =
        TripConfig("p1", "v1", profile, 5.minutes, policy, lastSentKm, anchor = null)

    @Test
    fun `reads at once, every poll, queues past the threshold and once more at trip end`() = runTest {
        val store = FakeTripStore()
        val conn = TestConnection(adapter(), backgroundScope)
        val tap = TranscriptTap(null)
        val session = ElmSession(conn.link, backgroundScope, tap, clock = { testScheduler.currentTime })
        var tripIds = 0
        val events = mutableListOf<TripEvent>()
        val runner = TripRunner(config(lastSentKm = 83_990), store, tap, { testScheduler.currentTime }, { "t${++tripIds}" }) { events += it }

        val trip = async { runner.run(conn, session) }
        runCurrent()
        assertThat(store.readings.map { it.km }).containsExactly(84_000)
        assertThat(store.queued).isEmpty() // 10 km past the last upload: under 25 mi

        odometer = 84_030.0
        advanceTimeBy(5.minutes); runCurrent()
        odometer = 84_041.0
        advanceTimeBy(5.minutes); runCurrent()
        assertThat(store.queuedKm).containsExactly(84_041)
        odometer = 84_050.0
        advanceTimeBy(5.minutes); runCurrent()
        assertThat(store.readings.map { it.km }).containsExactly(84_000, 84_030, 84_041, 84_050).inOrder()
        assertThat(store.readings.map { it.rawReply }.first()).contains("7E8")

        conn.drop()
        assertThat(trip.await()).isEqualTo(TripEndReason.DISCONNECTED)
        // The last reading (84 050) goes up at trip end: higher than 84 041.
        assertThat(store.queuedKm).containsExactly(84_041, 84_050).inOrder()
        assertThat(store.readings.map { it.tripId }.toSet()).containsExactly("t1")
        assertThat(events.filterIsInstance<TripEvent.Ended>().single()).isEqualTo(TripEvent.Ended(TripEndReason.DISCONNECTED, 84_050))
    }

    @Test
    fun `nothing sent yet uploads the first reading, and trip end without progress sends nothing`() = runTest {
        val store = FakeTripStore()
        val conn = TestConnection(adapter(), backgroundScope)
        val session = ElmSession(conn.link, backgroundScope)
        val runner = TripRunner(config(lastSentKm = null), store, TranscriptTap(null), { testScheduler.currentTime })

        val trip = async { runner.run(conn, session) }
        runCurrent()
        assertThat(store.queuedKm).containsExactly(84_000)
        advanceTimeBy(5.minutes); runCurrent() // parked: same value
        conn.drop()
        trip.await()
        assertThat(store.queuedKm).containsExactly(84_000)
        assertThat(store.readings).hasSize(2)
    }

    @Test
    fun `upload on trip end off waits for the distance even at the end`() = runTest {
        val store = FakeTripStore()
        val conn = TestConnection(adapter(), backgroundScope)
        val session = ElmSession(conn.link, backgroundScope)
        val policy = UploadPolicy(minDistance = 25.0, displayMiles = false, uploadOnTripEnd = false)
        val runner = TripRunner(config(lastSentKm = 83_990, policy = policy), store, TranscriptTap(null), { testScheduler.currentTime })

        val trip = async { runner.run(conn, session) }
        runCurrent()
        conn.drop()
        trip.await()
        assertThat(store.queued).isEmpty()
        assertThat(store.readings.single().state).isEqualTo(ReadingState.LOCAL)
    }

    @Test
    fun `the car going quiet while the adapter stays up ends the trip, the next answer starts another`() = runTest {
        val store = FakeTripStore()
        val conn = TestConnection(adapter(), backgroundScope)
        val session = ElmSession(conn.link, backgroundScope)
        var ids = 0
        val runner = TripRunner(config(lastSentKm = 83_990), store, TranscriptTap(null), { testScheduler.currentTime }, { "t${++ids}" })

        val trip = async { runner.run(conn, session) }
        runCurrent()
        odometer = 84_005.0
        advanceTimeBy(5.minutes); runCurrent()
        reply = "NO DATA\r\r>"
        repeat(TripRunner.IGNITION_OFF_FAILURES) { advanceTimeBy(5.minutes); runCurrent() }
        assertThat(store.queuedKm).containsExactly(84_005) // trip end: past the last sent value

        reply = null
        odometer = 84_006.0
        advanceTimeBy(5.minutes); runCurrent()
        assertThat(store.readings.last().tripId).isEqualTo("t2")
        conn.drop()
        trip.await()
        assertThat(store.queuedKm).containsExactly(84_005, 84_006).inOrder()
    }

    @Test
    fun `cancelling (service stopped) still makes the trip-end decision`() = runTest {
        val store = FakeTripStore()
        val conn = TestConnection(adapter(), backgroundScope)
        val session = ElmSession(conn.link, backgroundScope)
        val runner = TripRunner(config(lastSentKm = 83_990), store, TranscriptTap(null), { testScheduler.currentTime })

        val trip = async { runner.run(conn, session) }
        runCurrent()
        trip.cancel()
        runCurrent()
        assertThat(store.queuedKm).containsExactly(84_000)
    }

    @Test
    fun `codes cleared adds the counter to the latest manual reading and saves the anchor`() = runTest {
        val store = FakeTripStore()
        val conn = TestConnection(adapter(), backgroundScope)
        val session = ElmSession(conn.link, backgroundScope)
        val runner = TripRunner(config(lastSentKm = 50_000, profile = OdometerProfiles.CODES_CLEARED), store, TranscriptTap(null), { testScheduler.currentTime })

        val trip = async { runner.run(conn, session) }
        runCurrent()
        // No manual reading yet: stored with the counter only, nothing queued.
        assertThat(store.readings.single().km).isNull()
        assertThat(store.readings.single().counterKm).isEqualTo(1_000)
        assertThat(store.queued).isEmpty()

        store.manual = OdometerLog("o1", "v1", 50_010, "2026-10-20T10:00:00Z")
        counter = 1_005
        advanceTimeBy(5.minutes); runCurrent()
        counter = 1_050
        advanceTimeBy(5.minutes); runCurrent()
        assertThat(store.readings.map { it.km }).containsExactly(null, 50_010, 50_055).inOrder()
        assertThat(store.anchor?.manualLogId).isEqualTo("o1")
        assertThat(store.anchor?.lastKm).isEqualTo(50_055)
        assertThat(store.queuedKm).containsExactly(50_055)
        conn.drop()
        trip.await()
    }

    @Test(expected = ElmInitException::class)
    fun `something that isn't an ELM327 fails init`() = runTest {
        val link = ScriptedElmLink(backgroundScope) { _, _ -> "HELLO\r>" }
        val conn = TestConnection(link, backgroundScope)
        TripRunner(config(), FakeTripStore(), TranscriptTap(null), { 0 }).run(conn, ElmSession(conn.link, backgroundScope))
    }
}

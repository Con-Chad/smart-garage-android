package io.github.conchad.smartgarage.core.obd

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ElmSessionTest {
    @Test
    fun `a reply split over many notifications completes at the prompt`() = runTest {
        val link = ScriptedElmLink(backgroundScope, chunk = 3) { _, _ -> "SEARCHING...\r7E8 06 41 A6 00 0C D6 59\r\r>" }
        val session = ElmSession(link, backgroundScope)
        val reply = session.send("01A6")
        assertThat(reply.status).isEqualTo(ElmStatus.DATA)
        assertThat(reply.lines).containsExactly("7E8 06 41 A6 00 0C D6 59")
        assertThat(reply.raw).isEqualTo("SEARCHING...\r7E8 06 41 A6 00 0C D6 59\r\r")
    }

    @Test
    fun `commands end in CR and are written in chunks of maxWriteBytes`() = runTest {
        val link = ScriptedElmLink(backgroundScope, maxWriteBytes = 20) { _, _ -> "OK\r\r>" }
        val session = ElmSession(link, backgroundScope)
        val command = "ATFCSD300000;ATFCSM1;ATCRA1"
        session.send(command)
        assertThat(link.writes.map { it.size }).containsExactly(20, command.length + 1 - 20).inOrder()
        assertThat(link.writes.joinToString("") { String(it) }).isEqualTo("$command\r")
    }

    @Test
    fun `only one command is in flight at a time`() = runTest {
        val link = ScriptedElmLink(backgroundScope, replyDelayMs = 100) { c, _ -> "$c OK\r\r>" }
        val session = ElmSession(link, backgroundScope)
        val replies = listOf(async { session.send("ATE0") }, async { session.send("ATH1") }, async { session.send("ATS0") }).awaitAll()
        assertThat(link.events).containsExactly(
            "write ATE0", "reply ATE0", "write ATH1", "reply ATH1", "write ATS0", "reply ATS0",
        ).inOrder()
        assertThat(replies.map { it.text }).containsExactly("ATE0 OK", "ATH1 OK", "ATS0 OK").inOrder()
    }

    @Test
    fun `a timeout is retried and both attempts reach the transcript`() = runTest {
        val link = ScriptedElmLink(backgroundScope) { _, attempt -> if (attempt == 0) null else "41 0D 32\r\r>" }
        val log = ElmTranscriptLog()
        val session = ElmSession(link, backgroundScope, log, clock = { 42L })
        val reply = session.send("010D", timeout = 1.seconds)
        assertThat(reply.status).isEqualTo(ElmStatus.DATA)
        assertThat(log.entries().map { it.status to it.attempt }).containsExactly(ElmStatus.TIMEOUT to 0, ElmStatus.DATA to 1).inOrder()
        assertThat(log.entries().last()).isEqualTo(ElmTranscriptEntry(42L, "010D", "41 0D 32\r\r", ElmStatus.DATA, 1))
    }

    @Test
    fun `without retries a silent adapter is a timeout`() = runTest {
        val link = ScriptedElmLink(backgroundScope) { _, _ -> null }
        val session = ElmSession(link, backgroundScope)
        val reply = session.send("010D", timeout = 1.seconds, retries = 0)
        assertThat(reply.status).isEqualTo(ElmStatus.TIMEOUT)
        assertThat(reply.raw).isNull()
    }

    @Test
    fun `retryable errors are sent again, others are not`() = runTest {
        val link = ScriptedElmLink(backgroundScope) { c, attempt ->
            when {
                c == "0131" && attempt == 0 -> "CAN ERROR\r\r>"
                c == "0131" -> "41 31 04 D2\r\r>"
                else -> "NO DATA\r\r>"
            }
        }
        val session = ElmSession(link, backgroundScope)
        assertThat(session.send("0131").lines).containsExactly("41 31 04 D2")
        assertThat(session.send("01A6").status).isEqualTo(ElmStatus.NO_DATA)
        assertThat(link.events.count { it == "write 01A6" }).isEqualTo(1)
    }

    @Test
    fun `a late reply to a timed-out attempt doesn't answer the next command`() = runTest {
        // The first ATI reply only comes after the timeout; the retry's own reply must win.
        val link = ScriptedElmLink(backgroundScope, replyDelayMs = 1_100) { c, attempt -> "$c#$attempt\r\r>" }
        val session = ElmSession(link, backgroundScope)
        val reply = session.send("ATI", timeout = 1.seconds)
        assertThat(reply.status).isEqualTo(ElmStatus.TIMEOUT)
        val next = session.send("ATRV", timeout = 2.seconds)
        assertThat(next.text).isEqualTo("ATRV#0")
    }

    @Test
    fun `a closed link reports disconnected`() = runTest {
        val gone = CompletableDeferred<Unit>()
        val link = object : ElmLink {
            override val incoming: Flow<ByteArray> = flow { gone.await() }
            override suspend fun write(bytes: ByteArray) = Unit
        }
        val session = ElmSession(link, backgroundScope)
        gone.complete(Unit)
        testScheduler.runCurrent() // advanceUntilIdle skips backgroundScope work
        assertThat(session.isOpen).isFalse()
        assertThat(session.send("ATI").status).isEqualTo(ElmStatus.DISCONNECTED)
    }

    @Test
    fun `transcript log keeps the newest entries`() {
        val log = ElmTranscriptLog(capacity = 3)
        repeat(5) { log.record(ElmTranscriptEntry(it.toLong(), "C$it", "", ElmStatus.OK, 0)) }
        assertThat(log.entries().map { it.command }).containsExactly("C2", "C3", "C4").inOrder()
    }
}

package io.github.conchad.smartgarage.core.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One command/reply pair for the diagnostics log. [raw] is empty after a timeout. */
data class ElmTranscriptEntry(val atMillis: Long, val command: String, val raw: String, val status: ElmStatus, val attempt: Int)

fun interface ElmTranscript {
    fun record(entry: ElmTranscriptEntry)
}

/** Keeps the newest [capacity] entries (the diagnostics screen shows the last 100). Thread-safe. */
class ElmTranscriptLog(private val capacity: Int = 100) : ElmTranscript {
    private val entries = ArrayDeque<ElmTranscriptEntry>()

    @Synchronized
    override fun record(entry: ElmTranscriptEntry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun entries(): List<ElmTranscriptEntry> = entries.toList()
}

/**
 * The command queue on an [ElmLink]: exactly one command in flight, each ended with CR and written in chunks of
 * [ElmLink.maxWriteBytes]; a reply is complete at the ">" prompt however many notifications it took. A command that
 * times out or gets a [ElmStatus.retryable] reply is sent again up to `retries` times.
 *
 * Starts reading [ElmLink.incoming] at construction (undispatched, so a hot flow is subscribed before the first write)
 * and stops when [scope] is cancelled or on [close].
 */
class ElmSession(
    private val link: ElmLink,
    scope: CoroutineScope,
    private val transcript: ElmTranscript? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val replies = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()
    // Touched only by the reader coroutine.
    private val pending = StringBuilder()
    // After a timeout the adapter may still answer; that reply must not be taken for the next command's.
    private var lateReplyPossible = false

    private val reader: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            link.incoming.collect(::receive)
        } finally {
            replies.close()
        }
    }

    val isOpen: Boolean get() = reader.isActive

    suspend fun send(command: String, timeout: Duration = DEFAULT_TIMEOUT, retries: Int = DEFAULT_RETRIES): ElmReply =
        mutex.withLock { sendLocked(command.trim(), timeout, retries) }

    private suspend fun sendLocked(command: String, timeout: Duration, retries: Int): ElmReply {
        var attempt = 0
        while (true) {
            if (lateReplyPossible) {
                lateReplyPossible = false
                withTimeoutOrNull(LATE_REPLY_GRACE) { replies.receiveCatching() }
            }
            drainStale()
            val reply = if (replies.isClosedForReceive) {
                ElmReply.failed(command, ElmStatus.DISCONNECTED)
            } else {
                write(command)
                val raw = withTimeoutOrNull(timeout) { replies.receiveCatching() }
                when {
                    raw == null -> ElmReply.failed(command, ElmStatus.TIMEOUT)
                    raw.isClosed -> ElmReply.failed(command, ElmStatus.DISCONNECTED)
                    else -> ElmReply.of(command, raw.getOrThrow())
                }
            }
            if (reply.status == ElmStatus.TIMEOUT) lateReplyPossible = true
            transcript?.record(ElmTranscriptEntry(clock(), command, reply.raw.orEmpty(), reply.status, attempt))
            if (!reply.status.retryable || attempt >= retries) return reply
            attempt++
        }
    }

    override fun close() {
        reader.cancel()
    }

    private suspend fun write(command: String) {
        val bytes = "$command\r".toByteArray(Charsets.US_ASCII)
        val size = link.maxWriteBytes.coerceAtLeast(1)
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + size, bytes.size)
            link.write(bytes.copyOfRange(at, end))
            at = end
        }
    }

    private fun drainStale() {
        while (replies.tryReceive().isSuccess) Unit
    }

    private fun receive(chunk: ByteArray) {
        for (b in chunk) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == PROMPT) {
                replies.trySend(pending.toString())
                pending.setLength(0)
            } else if (c != '\u0000') {
                pending.append(c)
            }
        }
    }

    companion object {
        const val PROMPT = '>'
        val DEFAULT_TIMEOUT: Duration = 2.seconds
        const val DEFAULT_RETRIES = 1
        /** How long the command after a timeout waits for the timed-out one's reply before it is sent. */
        val LATE_REPLY_GRACE: Duration = 500.milliseconds
    }
}

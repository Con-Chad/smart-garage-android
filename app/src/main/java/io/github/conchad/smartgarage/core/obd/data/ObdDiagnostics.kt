package io.github.conchad.smartgarage.core.obd.data

import io.github.conchad.smartgarage.core.obd.ElmTranscript
import io.github.conchad.smartgarage.core.obd.ElmTranscriptEntry
import io.github.conchad.smartgarage.core.obd.ElmTranscriptLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** One line of the connection log: connect, disconnect, pairing, errors, uploads. */
data class ConnectionEvent(val atMillis: Long, val message: String)

/**
 * The diagnostics screen's data (D-A73): the last [TRANSCRIPT_SIZE] commands and replies and the last [LOG_SIZE]
 * connection events, in memory for the process (the foreground service keeps it alive while connected). Thread-safe.
 */
class ObdDiagnostics(private val clock: () -> Long = System::currentTimeMillis) : ElmTranscript {
    private val transcriptLog = ElmTranscriptLog(TRANSCRIPT_SIZE)
    private val _transcript = MutableStateFlow<List<ElmTranscriptEntry>>(emptyList())
    val transcript: StateFlow<List<ElmTranscriptEntry>> = _transcript.asStateFlow()

    private val events = ArrayDeque<ConnectionEvent>()
    private val _connectionLog = MutableStateFlow<List<ConnectionEvent>>(emptyList())
    val connectionLog: StateFlow<List<ConnectionEvent>> = _connectionLog.asStateFlow()

    @Synchronized
    override fun record(entry: ElmTranscriptEntry) {
        transcriptLog.record(entry)
        _transcript.value = transcriptLog.entries()
    }

    @Synchronized
    fun log(message: String) {
        events.addLast(ConnectionEvent(clock(), message))
        while (events.size > LOG_SIZE) events.removeFirst()
        _connectionLog.value = events.toList()
    }

    /** Plain text for sharing: [header] lines first, then the connection log and the transcript, oldest first. */
    fun report(header: List<String>): String = buildString {
        appendLine("Smart Garage OBD diagnostics")
        header.forEach { appendLine(it) }
        appendLine()
        appendLine("== Connection log ==")
        connectionLog.value.forEach { appendLine("${time(it.atMillis)}  ${it.message}") }
        appendLine()
        appendLine("== Commands (last $TRANSCRIPT_SIZE) ==")
        transcript.value.forEach { e ->
            val retry = if (e.attempt > 0) " (retry ${e.attempt})" else ""
            appendLine("${time(e.atMillis)}  > ${e.command}$retry  [${e.status}]")
            e.raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { appendLine("    $it") }
        }
    }

    private fun time(millis: Long) = Instant.ofEpochMilli(millis).toString()

    companion object {
        const val TRANSCRIPT_SIZE = 100
        const val LOG_SIZE = 200
    }
}

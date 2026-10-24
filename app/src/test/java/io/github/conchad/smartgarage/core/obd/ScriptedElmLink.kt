package io.github.conchad.smartgarage.core.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * A test link: [script] gets each complete command and how often it was sent before, and returns the raw reply
 * (with the prompt) or null for silence. Replies go out after [replyDelayMs] in [chunk]-byte notifications.
 */
class ScriptedElmLink(
    private val scope: CoroutineScope,
    private val chunk: Int = 20,
    private val replyDelayMs: Long = 0,
    override val maxWriteBytes: Int = ElmLink.DEFAULT_MAX_WRITE_BYTES,
    private val script: (command: String, attempt: Int) -> String?,
) : ElmLink {
    val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1024)
    override val incoming: SharedFlow<ByteArray> = flow
    val writes = mutableListOf<ByteArray>()
    val events = mutableListOf<String>()
    private val input = StringBuilder()
    private val counts = mutableMapOf<String, Int>()

    override suspend fun write(bytes: ByteArray) {
        writes += bytes
        for (b in bytes) {
            val c = b.toInt().toChar()
            if (c != '\r') {
                input.append(c)
                continue
            }
            val command = input.toString()
            input.setLength(0)
            val attempt = counts[command] ?: 0
            counts[command] = attempt + 1
            events += "write $command"
            val reply = script(command, attempt) ?: continue
            scope.launch {
                delay(replyDelayMs)
                events += "reply $command"
                reply.toByteArray().asList().chunked(chunk).forEach { flow.emit(it.toByteArray()) }
            }
        }
    }
}

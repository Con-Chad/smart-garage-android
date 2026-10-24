package io.github.conchad.smartgarage.core.obd

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A pretend ELM327 (v1.5) plugged into a 2016 Honda HR-V, for debug builds only (src/debug: never in the release APK).
 * Keeps the adapter's state (echo, linefeeds, spaces, headers, protocol, header) and prints replies the way a real one
 * does for that state, split into notifications of at most [chunkBytes] ending in ">".
 *
 * Answers:
 * - 22 70 22 with header DA60F1 on a 29-bit protocol: Honda's cluster, three ISO-TP frames from 18DAF160, odometer
 *   (km, 24-bit) where WiCAN's [B12:B14] reads it;
 * - 01 A6 (odometer, 0.1 km) unless [supportsStandardOdometer] is off, 01 31 (km since codes cleared), 01 00;
 * - NO DATA for anything else; "?" for unknown AT commands.
 *
 * The odometer rises at [speedKmh] while [driving], by [clock]. [inject] queues faults for the next OBD requests.
 * One writer at a time (the [ElmSession]); not thread-safe.
 */
class SimulatedElmLink(
    private val clock: () -> Long = System::currentTimeMillis,
    startOdometerKm: Double = 84_210.0,
    speedKmh: Double = 60.0,
    driving: Boolean = true,
    /** The car's own protocol, found by ATSP0's search: 6 = ISO 15765-4 CAN 11-bit 500 kbaud. */
    private val nativeProtocol: Int = 6,
    var supportsStandardOdometer: Boolean = true,
    /** Where the car was when codes were last cleared, for 0131. */
    var codesClearedAtKm: Double = startOdometerKm - 1_234,
    override val maxWriteBytes: Int = ElmLink.DEFAULT_MAX_WRITE_BYTES,
    private val chunkBytes: Int = 20,
) : ElmLink {
    enum class Fault { TIMEOUT, CAN_ERROR, UNKNOWN_COMMAND, NO_DATA, STOPPED }

    private val out = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4096)
    override val incoming: Flow<ByteArray> = out

    /** Every write as received, for tests (chunk sizes). */
    val writes = mutableListOf<ByteArray>()
    /** Every complete command as received. */
    val commands = mutableListOf<String>()

    private val input = StringBuilder()
    private val faults = ArrayDeque<Fault>()

    private var odometer = startOdometerKm
    private var lastTick = clock()

    var speedKmh: Double = speedKmh
        set(value) { advance(); field = value }

    var driving: Boolean = driving
        set(value) { advance(); field = value }

    val odometerKm: Double get() { advance(); return odometer }

    // Adapter state, as after ATZ.
    private var echo = true
    private var linefeeds = false
    private var spaces = true
    private var headers = false
    private var protocol = 0
    private var autoProtocol = true
    private var searched = false
    private var header: String? = null

    fun inject(vararg fault: Fault) {
        faults.addAll(fault)
    }

    /** "Clear DTCs": 0131 starts again from 0. */
    fun clearCodes() {
        codesClearedAtKm = odometerKm
    }

    override suspend fun write(bytes: ByteArray) {
        writes += bytes.copyOf()
        for (b in bytes) {
            val c = (b.toInt() and 0xFF).toChar()
            if (c == '\r') {
                val command = input.toString().trim()
                input.setLength(0)
                if (command.isNotEmpty()) respond(command)
            } else {
                input.append(c)
            }
        }
    }

    private fun advance() {
        val now = clock()
        if (driving) odometer += speedKmh * (now - lastTick) / 3_600_000.0
        lastTick = now
    }

    private suspend fun respond(command: String) {
        commands += command
        val echoed = if (echo) command else null
        val c = command.filterNot(Char::isWhitespace).uppercase()
        val lines: List<String>? = if (c.startsWith("AT")) at(c.drop(2)) else obd(c)
        if (lines == null) return // a timeout: no prompt ever comes
        val eol = if (linefeeds) "\r\n" else "\r"
        val text = buildString {
            if (echoed != null) append(echoed).append(eol)
            lines.forEach { append(it).append(eol) }
            append(eol).append('>')
        }
        text.toByteArray(Charsets.US_ASCII).asList().chunked(chunkBytes.coerceAtLeast(1)).forEach {
            out.emit(it.toByteArray())
        }
    }

    private fun at(arg: String): List<String> {
        fun flag(prefix: String, set: (Boolean) -> Unit): List<String>? =
            if (arg.length == prefix.length + 1 && arg.startsWith(prefix) && arg.last() in "01") {
                set(arg.last() == '1')
                listOf("OK")
            } else {
                null
            }
        return when {
            arg == "Z" || arg == "WS" -> { reset(); listOf("", VERSION) }
            arg == "I" -> listOf(VERSION)
            arg == "D" -> { reset(); listOf("OK") }
            arg == "RV" -> listOf("12.6V")
            arg == "DPN" -> listOf(if (autoProtocol) "A${activeProtocol().toString(16).uppercase()}" else protocol.toString(16).uppercase())
            arg == "DP" -> listOf(if (activeProtocol() in listOf(7, 9)) "ISO 15765-4 (CAN 29/500)" else "ISO 15765-4 (CAN 11/500)")
            arg.startsWith("SP") || arg.startsWith("TP") -> setProtocol(arg.drop(2))
            arg.startsWith("SH") -> {
                val h = arg.drop(2)
                if (h.length in listOf(3, 6, 8) && h.all { it.isDigit() || it in 'A'..'F' }) { header = h; listOf("OK") } else listOf("?")
            }
            arg.startsWith("ST") || arg.startsWith("AT") || arg.startsWith("CAF") || arg.startsWith("M") ||
                arg.startsWith("CRA") || arg.startsWith("FC") || arg.startsWith("CP") -> listOf("OK")
            else -> flag("E") { echo = it } ?: flag("L") { linefeeds = it } ?: flag("S") { spaces = it }
                ?: flag("H") { headers = it } ?: listOf("?")
        }
    }

    private fun setProtocol(arg: String): List<String> {
        val auto = arg.startsWith("A")
        val p = arg.removePrefix("A").singleOrNull()?.digitToIntOrNull(16) ?: return listOf("?")
        if (p > 0xC) return listOf("?")
        autoProtocol = auto || p == 0
        protocol = p
        searched = false
        return listOf("OK")
    }

    /** Automatic ("ATSP0", "ATSPA7") finds the car's own protocol. */
    private fun activeProtocol(): Int = if (autoProtocol) nativeProtocol else protocol

    private fun reset() {
        echo = true
        linefeeds = false
        spaces = true
        headers = false
        protocol = 0
        autoProtocol = true
        searched = false
        header = null
    }

    private fun obd(request: String): List<String>? {
        if (request.isEmpty() || !request.all { it.isDigit() || it in 'A'..'F' }) return listOf("?")
        when (faults.removeFirstOrNull()) {
            Fault.TIMEOUT -> return null
            Fault.CAN_ERROR -> return listOf("CAN ERROR")
            Fault.UNKNOWN_COMMAND -> return listOf("?")
            Fault.NO_DATA -> return listOf("NO DATA")
            Fault.STOPPED -> return listOf("STOPPED")
            null -> Unit
        }
        val prefix = if (autoProtocol && !searched) listOf("SEARCHING...") else emptyList()
        searched = true
        val hex = request.take(request.length - request.length % 2)
        val bits29 = activeProtocol() in listOf(7, 9)
        val ecuId = if (bits29) "18DAF110" else "7E8"
        val reply: Pair<String, List<List<Int>>>? = when (hex) {
            "227022" -> if (bits29 && header == "DA60F1") "18DAF160" to hondaFrames() else null
            "01A6" -> if (supportsStandardOdometer) ecuId to sf(listOf(0x41, 0xA6) + be((odometerKm * 10).toLong(), 4)) else null
            "0131" -> ecuId to sf(listOf(0x41, 0x31) + be((odometerKm - codesClearedAtKm).toLong().coerceIn(0, 0xFFFF), 2))
            "0100" -> ecuId to sf(listOf(0x41, 0x00, 0xBE, 0x3F, 0xA8, 0x13))
            else -> null
        }
        if (reply == null) return prefix + "NO DATA"
        return prefix + format(reply.first, reply.second)
    }

    /** A single-frame reply: its length (the PCI byte), then the message. */
    private fun sf(message: List<Int>): List<List<Int>> = listOf(listOf(message.size) + message)

    /**
     * 62 70 22 + 12 data bytes as three frames: FF 10 0F 62 70 22 d0 d1 d2, CF 21 d3..d9, CF 22 d10 d11 + padding.
     * The odometer is d6..d8, i.e. WiCAN's B12..B14.
     */
    private fun hondaFrames(): List<List<Int>> {
        val odo = be(odometerKm.toLong().coerceIn(0, 0xFFFFFF), 3)
        val data = listOf(0x00, 0x2A, 0x10, 0x03, 0x00, 0x00) + odo + listOf(0x01, 0x00, 0x55)
        val message = listOf(0x62, 0x70, 0x22) + data
        return listOf(
            listOf(0x10, message.size) + message.take(6),
            listOf(0x21) + message.subList(6, 13),
            listOf(0x22) + message.drop(13) + List(5) { 0x00 },
        )
    }

    /** One CAN reply as the adapter prints it for the current ATH/ATS state. */
    private fun format(id: String, frames: List<List<Int>>): List<String> {
        val sep = if (spaces) " " else ""
        fun hex(bytes: List<Int>) = bytes.joinToString(sep) { "%02X".format(it) }
        if (headers) {
            val shownId = if (spaces && id.length == 8) id.chunked(2).joinToString(" ") else id
            return frames.map { "$shownId$sep${hex(it)}" }
        }
        // Headers off: the adapter drops PCI bytes too; a multi-frame reply prints its length then numbered lines.
        if (frames.size == 1) {
            val f = frames[0]
            return listOf(hex(f.drop(1).take(f[0] and 0x0F)))
        }
        val length = ((frames[0][0] and 0x0F) shl 8) or frames[0][1]
        var left = length
        return listOf("%03X".format(length)) + frames.mapIndexed { i, f ->
            val body = if (i == 0) f.drop(2) else f.drop(1)
            val shown = body.take(left).also { left -= it.size }
            "${(i and 0x0F).toString(16).uppercase()}:$sep${hex(shown)}"
        }
    }

    private fun be(value: Long, size: Int): List<Int> = (size - 1 downTo 0).map { ((value shr (it * 8)) and 0xFF).toInt() }

    companion object {
        const val VERSION = "ELM327 v1.5"
    }
}

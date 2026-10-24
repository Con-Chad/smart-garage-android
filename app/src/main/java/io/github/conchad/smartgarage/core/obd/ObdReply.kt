package io.github.conchad.smartgarage.core.obd

/**
 * A request as a WiCAN profile writes it, e.g. "2270229": service 0x22, DID 70 22, and a trailing odd digit that is
 * ELM's expected-response count (the adapter stops waiting after that many frames). Sent to the adapter as written.
 */
data class ObdRequest(val text: String, val bytes: List<Int>, val responseCount: Int?) {
    val service: Int get() = bytes[0]
    /** The PID/DID bytes a positive response repeats after its service byte. */
    val echo: List<Int> get() = bytes.drop(1)
    val positiveService: Int get() = (service + 0x40) and 0xFF

    companion object {
        fun parse(text: String): ObdRequest {
            val hex = text.filterNot(Char::isWhitespace)
            require(hex.length >= 2 && hex.all { it.isHexDigit() }) { "request \"$text\" isn't hex" }
            val count = if (hex.length % 2 == 1) hex.last().digitToInt(16) else null
            val bytes = hex.take(hex.length - hex.length % 2).chunked(2).map { it.toInt(16) }
            return ObdRequest(text.trim(), bytes, count)
        }

        fun parseOrNull(text: String): ObdRequest? = runCatching { parse(text) }.getOrNull()
    }
}

/**
 * How the adapter prints frames. [headers] is ATH1; [canIdChars] is the CAN ID width without spaces (ATS0): 3 for
 * 11-bit, 8 for 29-bit, null to guess per line (odd length means 3, even 8). Spaced lines (ATS1) need no hint.
 */
data class ElmFormat(val headers: Boolean = true, val canIdChars: Int? = null) {
    companion object {
        /** CAN ID width for an ELM protocol number (ATSPn / ATDPN): 6, 8 are 11-bit; 7, 9 are 29-bit. */
        fun canIdChars(protocol: Int?): Int? = when (protocol) {
            6, 8 -> 3
            7, 9 -> 8
            else -> null
        }

        /** The protocol an ATDPN reply names ("A6" while auto-detected, "6" when set); null if unreadable. */
        fun parseDpn(text: String): Int? {
            val t = text.trim().uppercase().removePrefix("A")
            return t.singleOrNull()?.digitToIntOrNull(16)
        }
    }
}

/**
 * One ECU's answer, numbered the way WiCAN numbers it so a WiCAN expression reads the same bytes: WiCAN keeps headers
 * on, strips only each frame's CAN ID, and concatenates everything else of every frame, ISO-TP PCI bytes included.
 * Single frame: B0 = length, B1 = 0x40 + service, then the PID/DID echo, then data. Multi-frame: B0 = 0x1L, B1 = length,
 * B2.. service + echo + data, B8 = 0x21 (the first consecutive frame's PCI), B9..B15 data, B16 = 0x22, and so on.
 * Replies printed without headers get the same numbering: the PCI bytes are rebuilt.
 */
data class ObdResponse(
    /** The answering ECU's CAN ID; null when the adapter printed no headers. */
    val ecu: String?,
    /** WiCAN numbering, for B-indexes. */
    val bytes: List<Int>,
    /** The ISO-TP message: service byte, echo, data (PCI and padding removed). */
    val message: List<Int>,
    private val echoSize: Int,
    /** Every ECU that answered, lowest first. */
    val ecus: List<String> = listOfNotNull(ecu),
) {
    /** The data after the service byte and PID/DID echo, for display. */
    val payload: List<Int> get() = message.drop(1 + echoSize)
}

sealed interface ObdParse {
    /** The ECU answered the request. */
    data class Positive(val response: ObdResponse) : ObdParse

    /** "7F service NRC". [pending] (0x78) means the ECU is still working on it. */
    data class Negative(val ecu: String?, val service: Int, val code: Int) : ObdParse {
        val pending: Boolean get() = code == RESPONSE_PENDING
        val codeName: String get() = NRC_NAMES[code] ?: "0x%02X".format(code)
    }

    /** Data that doesn't answer this request (another service or PID); [response] is the lowest header's. */
    data class Unmatched(val response: ObdResponse) : ObdParse

    data class Invalid(val reason: String) : ObdParse

    companion object {
        const val RESPONSE_PENDING = 0x78
        private val NRC_NAMES = mapOf(
            0x10 to "generalReject",
            0x11 to "serviceNotSupported",
            0x12 to "subFunctionNotSupported",
            0x13 to "incorrectMessageLength",
            0x14 to "responseTooLong",
            0x21 to "busyRepeatRequest",
            0x22 to "conditionsNotCorrect",
            0x31 to "requestOutOfRange",
            0x33 to "securityAccessDenied",
            0x78 to "responsePending",
            0x7E to "subFunctionNotSupportedInActiveSession",
            0x7F to "serviceNotSupportedInActiveSession",
        )
    }
}

/**
 * Turns an adapter's data lines into an [ObdResponse] with WiCAN byte numbering. Accepts headers on (11-bit "7E8",
 * 29-bit "18DAF160", with or without spaces; spaced 29-bit IDs print as four tokens) and headers off (plain single
 * frames, and ELM's numbered multi-frame form "014 / 0: .. / 1: .."). When several ECUs answer, the one whose message
 * answers the request wins, lowest CAN ID first; WiCAN uses the lowest header too (`priority_data`).
 *
 * Differs from WiCAN on purpose: a vehicle-specific PID there concatenates every ECU's frames into one array; here
 * each ECU's frames are kept apart (same numbering when one ECU answers, which is the normal case), and a 0x78
 * "response pending" frame in front of the real answer is skipped instead of shifting every index by 4.
 */
object ObdReplyParser {
    fun parse(raw: String, request: ObdRequest, format: ElmFormat = ElmFormat()): ObdParse =
        parse(ElmReply.clean(raw, request.text), request, format)

    fun parse(lines: List<String>, request: ObdRequest, format: ElmFormat = ElmFormat()): ObdParse {
        val data = lines.map { it.trim().uppercase() }.filter { it.isNotEmpty() && ElmReply.HEX_LINE.matches(it) }
        if (data.isEmpty()) return ObdParse.Invalid("no data lines")
        val numbered = data.any { NUMBERED.matches(it) }
        if (numbered || !format.headers) return choose(headerless(data), request, data)
        val result = choose(withHeaders(data, format.canIdChars), request, data)
        if (result is ObdParse.Positive || result is ObdParse.Negative) return result
        // An adapter that ignored ATH1 prints bare messages; take that reading only if it answers the request.
        val bare = choose(headerless(data), request, data)
        return if (bare is ObdParse.Positive || bare is ObdParse.Negative) bare else result
    }

    private class Message(val ecu: String?, val frames: List<List<Int>>) {
        val bytes: List<Int> = frames.flatten()
        val declaredLength: Int
        val message: List<Int>

        init {
            val first = frames.first()
            if (first[0] shr 4 == 1 && first.size >= 2) {
                declaredLength = ((first[0] and 0x0F) shl 8) or first[1]
                message = (first.drop(2) + frames.drop(1).flatMap { it.drop(1) }).take(declaredLength)
            } else if ((first[0] and 0x0F) == 0 && first.size >= 2) {
                // CAN FD single frame escape: 00 LL.
                declaredLength = first[1]
                message = first.drop(2).take(declaredLength)
            } else {
                declaredLength = first[0] and 0x0F
                message = first.drop(1).take(declaredLength)
            }
        }

        val complete: Boolean get() = message.size >= declaredLength && declaredLength > 0
        val headerValue: Long get() = ecu?.toLongOrNull(16) ?: Long.MAX_VALUE

        fun answers(request: ObdRequest) = message.firstOrNull() == request.positiveService &&
            message.drop(1).take(request.echo.size) == request.echo

        fun negativeTo(request: ObdRequest) = message.size >= 3 && message[0] == 0x7F && message[1] == request.service
    }

    private fun choose(messages: List<Message>, request: ObdRequest, lines: List<String>): ObdParse {
        if (messages.isEmpty()) return ObdParse.Invalid("no frames in \"${lines.joinToString(" / ")}\"")
        val ecus = messages.mapNotNull { it.ecu }.distinct().sortedBy { it.toLongOrNull(16) ?: Long.MAX_VALUE }
        fun Message.response() = ObdResponse(ecu, bytes, message, request.echo.size, ecus)

        val positive = messages.filter { it.answers(request) }.sortedWith(compareBy({ !it.complete }, { it.headerValue }))
        positive.firstOrNull()?.let { best ->
            if (!best.complete) {
                return ObdParse.Invalid("incomplete reply from ${best.ecu ?: "the ECU"}: ${best.message.size} of ${best.declaredLength} bytes")
            }
            return ObdParse.Positive(best.response())
        }
        val negative = messages.filter { it.negativeTo(request) }
            .sortedWith(compareBy({ it.message[2] == ObdParse.RESPONSE_PENDING }, { it.headerValue }))
        negative.firstOrNull()?.let { return ObdParse.Negative(it.ecu, it.message[1], it.message[2]) }
        return ObdParse.Unmatched(messages.minBy { it.headerValue }.response())
    }

    /** ATH1: group frames by CAN ID (in arrival order), then split each ECU's frames into ISO-TP messages by PCI. */
    private fun withHeaders(lines: List<String>, canIdChars: Int?): List<Message> {
        val byEcu = LinkedHashMap<String, MutableList<List<Int>>>()
        for (line in lines) {
            val (id, bytes) = frame(line, canIdChars) ?: continue
            byEcu.getOrPut(id) { mutableListOf() } += bytes
        }
        return byEcu.flatMap { (id, frames) -> isoTpMessages(id, frames) }
    }

    private fun frame(line: String, canIdChars: Int?): Pair<String, List<Int>>? {
        val tokens = line.split(WHITESPACE)
        val (id, hex) = if (tokens.size > 1) {
            when (tokens[0].length) {
                3, 8 -> tokens[0] to tokens.drop(1).joinToString("")
                // ATS1 prints a 29-bit ID as four byte tokens: "18 DA F1 60 10 14 ..".
                2 -> if (tokens.size > 4) tokens.take(4).joinToString("") to tokens.drop(4).joinToString("") else return null
                else -> return null
            }
        } else {
            val s = tokens[0]
            val width = canIdChars ?: if (s.length % 2 == 1) 3 else 8
            if (s.length <= width) return null
            s.take(width) to s.drop(width)
        }
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        return id to hex.chunked(2).map { it.toInt(16) }
    }

    private fun isoTpMessages(ecu: String, frames: List<List<Int>>): List<Message> {
        val groups = mutableListOf<MutableList<List<Int>>>()
        var current: MutableList<List<Int>>? = null
        for (f in frames) {
            when (f[0] shr 4) {
                0 -> { groups += mutableListOf(f); current = null }
                1 -> current = mutableListOf(f).also { groups += it }
                2 -> current?.add(f)
                else -> Unit // 3 = flow control, never part of an answer
            }
        }
        return groups.map { Message(ecu, it) }
    }

    /**
     * ATH0: the adapter dropped the CAN ID and PCI bytes. Rebuild the PCI so the numbering matches ATH1: a numbered
     * block ("014", "0: ..", "1: ..") becomes 1L LL + line 0, then 0x2N + line N; a plain line becomes a single frame
     * (or, longer than 7 bytes, the same multi-frame layout).
     */
    private fun headerless(lines: List<String>): List<Message> {
        val messages = mutableListOf<Message>()
        var length: Int? = null
        var block = mutableListOf<List<Int>>()
        fun flush() {
            if (block.isEmpty()) return
            val first = block.first()
            val total = length ?: block.sumOf { it.size }
            val frames = listOf(listOf(0x10 or ((total shr 8) and 0x0F), total and 0xFF) + first) +
                block.drop(1).mapIndexed { i, bytes -> listOf(0x20 or ((i + 1) and 0x0F)) + bytes }
            messages += Message(null, frames)
            block = mutableListOf()
            length = null
        }
        for (line in lines) {
            val compact = line.filterNot(Char::isWhitespace)
            when {
                LENGTH.matches(compact) -> { flush(); length = compact.toInt(16) }
                NUMBERED.matches(line) -> block += hexBytes(compact.substringAfter(':')) ?: continue
                else -> {
                    flush()
                    val bytes = hexBytes(compact) ?: continue
                    messages += if (bytes.size <= 7) {
                        Message(null, listOf(listOf(bytes.size) + bytes))
                    } else {
                        Message(null, listOf(listOf(0x10 or (bytes.size shr 8), bytes.size and 0xFF) + bytes.take(6)) +
                            bytes.drop(6).chunked(7).mapIndexed { i, chunk -> listOf(0x20 or ((i + 1) and 0x0F)) + chunk })
                    }
                }
            }
        }
        flush()
        return messages
    }

    private fun hexBytes(hex: String): List<Int>? =
        if (hex.isEmpty() || hex.length % 2 != 0) null else hex.chunked(2).map { it.toInt(16) }

    private val WHITESPACE = Regex("\\s+")
    private val NUMBERED = Regex("[0-9A-F]\\s*:.*")
    private val LENGTH = Regex("[0-9A-F]{3}")
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

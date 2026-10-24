package io.github.conchad.smartgarage.core.obd

/** What an adapter's reply to one command amounts to. [retryable] ones are sent again by [ElmSession]. */
enum class ElmStatus(val retryable: Boolean = false) {
    /** "OK" (an AT setting was taken). */
    OK,
    /** Hex lines: a reply from the car (positive or negative; [ObdReplyParser] tells which). */
    DATA,
    /** Anything else readable, e.g. ATI's "ELM327 v1.5" or ATRV's "12.6V". */
    TEXT,
    /** Only the prompt came back. */
    EMPTY,
    NO_DATA,
    /** "?": the adapter doesn't know the command. */
    UNKNOWN_COMMAND,
    CAN_ERROR(retryable = true),
    STOPPED(retryable = true),
    BUFFER_FULL(retryable = true),
    UNABLE_TO_CONNECT,
    /** "BUS INIT: ...ERROR" (K-line / J1850 wake-up failed). */
    BUS_INIT_ERROR,
    /** BUS ERROR, BUS BUSY, FB ERROR, DATA ERROR, <RX ERROR, ERRnn, LV RESET, ACT ALERT. */
    BUS_ERROR,
    /** No prompt within the command's timeout. */
    TIMEOUT(retryable = true),
    /** The link closed (adapter out of range or turned off). */
    DISCONNECTED,
}

/**
 * One command's reply: [raw] exactly as received (for the diagnostics log; null on a timeout), [lines] with the
 * prompt, echo, blank lines and "SEARCHING..." removed.
 */
data class ElmReply(val command: String, val raw: String?, val lines: List<String>, val status: ElmStatus) {
    val text: String get() = lines.joinToString("\n")

    companion object {
        fun of(command: String, raw: String): ElmReply {
            val lines = clean(raw, command)
            return ElmReply(command, raw, lines, classify(lines))
        }

        fun failed(command: String, status: ElmStatus) = ElmReply(command, null, emptyList(), status)

        /**
         * Splits a reply into its meaningful lines: drops the prompt, unprintable bytes (some adapters send NULs), the
         * command's echo (first line only, so ATE0 itself or an adapter that ignores it doesn't matter), blank lines,
         * "SEARCHING..." (alone or in front of the first line) and a successful "BUS INIT: ...OK".
         */
        fun clean(raw: String, command: String): List<String> {
            val echo = command.filterNot(Char::isWhitespace).uppercase()
            val lines = raw.filter { it == '\r' || it == '\n' || it in ' '..'~' }
                .replace(">", "")
                .split('\r', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList()
            if (echo.isNotEmpty() && lines.firstOrNull()?.filterNot(Char::isWhitespace)?.uppercase() == echo) {
                lines.removeAt(0)
            }
            return lines.map { it.removePrefix(SEARCHING).trim() }
                .filter { it.isNotEmpty() && !(it.startsWith(BUS_INIT) && it.endsWith("OK")) }
        }

        fun classify(lines: List<String>): ElmStatus {
            if (lines.isEmpty()) return ElmStatus.EMPTY
            val upper = lines.map { it.uppercase() }
            return when {
                upper.any { it == "?" } -> ElmStatus.UNKNOWN_COMMAND
                upper.any { "UNABLE TO CONNECT" in it } -> ElmStatus.UNABLE_TO_CONNECT
                upper.any { it.startsWith(BUS_INIT) && "ERROR" in it } -> ElmStatus.BUS_INIT_ERROR
                upper.any { "CAN ERROR" in it } -> ElmStatus.CAN_ERROR
                upper.any { "BUFFER FULL" in it } -> ElmStatus.BUFFER_FULL
                upper.any { "STOPPED" in it } -> ElmStatus.STOPPED
                upper.any { "NO DATA" in it } -> ElmStatus.NO_DATA
                upper.any { line -> BUS_ERRORS.any { it in line } || ERR_CODE.matches(line) } -> ElmStatus.BUS_ERROR
                upper.all { it == "OK" } -> ElmStatus.OK
                upper.all { HEX_LINE.matches(it) } -> ElmStatus.DATA
                else -> ElmStatus.TEXT
            }
        }

        private const val SEARCHING = "SEARCHING..."
        private const val BUS_INIT = "BUS INIT"
        private val BUS_ERRORS = listOf("BUS ERROR", "BUS BUSY", "FB ERROR", "DATA ERROR", "RX ERROR", "LV RESET", "ACT ALERT")
        private val ERR_CODE = Regex("ERR\\d\\d")
        /** A data line: hex with optional spaces, optionally an ISO-TP line number ("0:") in front. */
        internal val HEX_LINE = Regex("(?:[0-9A-F]\\s*:)?[0-9A-F\\s]+")
    }
}

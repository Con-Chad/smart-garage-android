package io.github.conchad.smartgarage.core.obd

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ElmInitException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        /** Nothing came back (wrong device, adapter asleep) or the link closed. */
        NO_REPLY,
        /** Something answered, but not like an ELM327 / STN. */
        NOT_ELM,
    }
}

/** The adapter after [ElmInit.run]: what ATI said, how replies will be printed, and profile commands it refused. */
data class ElmAdapter(val identity: String, val format: ElmFormat, val rejected: List<String>)

/**
 * Brings an adapter to a known state: ATZ, ATI (to tell an ELM-style adapter from anything else), then echo off,
 * linefeeds off, spaces off, headers on, adaptive timing, then the profile's init and header commands.
 * Headers on like WiCAN, so a profile's byte numbering holds ([ObdResponse.bytes]).
 */
object ElmInit {
    val BASE_COMMANDS = listOf("ATE0", "ATL0", "ATS0", "ATH1", "ATAT1")

    /** Without these the session can't work; the rest of [BASE_COMMANDS] are refused by some clones and skipped. */
    private val REQUIRED = setOf("ATE0", "ATH1")

    /**
     * Profile commands WiCAN never sends either (it filters ATH0, ATS0, ATE1 out of profiles) plus resets, which would
     * undo the setup: a profile written for another tool can't turn headers off and shift every index.
     */
    private val SKIPPED = setOf("ATH0", "ATE1", "ATZ", "ATWS", "ATD")

    val RESET_TIMEOUT: Duration = 5.seconds

    suspend fun run(session: ElmSession, profile: OdometerProfile): ElmAdapter {
        val reset = session.send("ATZ", RESET_TIMEOUT)
        if (reset.status == ElmStatus.TIMEOUT || reset.status == ElmStatus.DISCONNECTED) {
            throw ElmInitException(ElmInitException.Reason.NO_REPLY, "no reply to ATZ")
        }
        val ati = session.send("ATI")
        val identity = ati.text.ifBlank { reset.text }
        if (!looksLikeElm(ati.text) && !looksLikeElm(reset.text)) {
            throw ElmInitException(ElmInitException.Reason.NOT_ELM, "not an ELM327-compatible adapter: \"$identity\"")
        }
        val rejected = mutableListOf<String>()
        for (command in BASE_COMMANDS) {
            val reply = session.send(command)
            when {
                reply.status == ElmStatus.TIMEOUT || reply.status == ElmStatus.DISCONNECTED ->
                    throw ElmInitException(ElmInitException.Reason.NO_REPLY, "no reply to $command")
                reply.status == ElmStatus.OK -> Unit
                command in REQUIRED ->
                    throw ElmInitException(ElmInitException.Reason.NOT_ELM, "$command refused: \"${reply.text}\"")
                else -> rejected += command
            }
        }
        val commands = profileCommands(profile)
        for (command in commands) {
            val reply = session.send(command)
            if (reply.status == ElmStatus.TIMEOUT || reply.status == ElmStatus.DISCONNECTED) {
                throw ElmInitException(ElmInitException.Reason.NO_REPLY, "no reply to $command")
            }
            if (reply.status != ElmStatus.OK) rejected += command
        }
        return ElmAdapter(identity, ElmFormat(headers = true, canIdChars = ElmFormat.canIdChars(protocolOf(commands))), rejected)
    }

    /** The profile's init then header commands, split on ";" (and line breaks), without the [SKIPPED] ones. */
    fun profileCommands(profile: OdometerProfile): List<String> =
        (split(profile.initCommands) + split(profile.headerCommand)).filterNot { it.compact() in SKIPPED }

    fun split(commands: String): List<String> = commands.split(';', '\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** The protocol the last ATSP/ATTP sets; null for automatic ("ATSP0", "ATSPA6") or none. */
    fun protocolOf(commands: List<String>): Int? {
        val last = commands.map { it.compact() }.lastOrNull { it.startsWith("ATSP") || it.startsWith("ATTP") } ?: return null
        val arg = last.drop(4)
        if (arg.length != 1) return null
        return arg[0].digitToIntOrNull(16)?.takeIf { it != 0 }
    }

    /**
     * Lenient: "ELM327 v1.5", "ELM327 v2.3" (WiCAN), "STN2255 v5.10" / "OBDLink CX", or any version-like text that
     * clones print. Garbage (empty, "?", unprintable bytes, no version) is not.
     */
    fun looksLikeElm(text: String): Boolean {
        val t = text.trim().uppercase()
        if (t.isEmpty() || t == "?" || t == "OK" || t.any { it !in ' '..'~' && it != '\n' }) return false
        if (listOf("ELM32", "STN", "OBDLINK", "WICAN", "OBDII").any { it in t }) return true
        return VERSION.containsMatchIn(t)
    }

    private val VERSION = Regex("""\bV?\d+\.\d+[A-Z]?\b""")

    private fun String.compact() = filterNot(Char::isWhitespace).uppercase()
}

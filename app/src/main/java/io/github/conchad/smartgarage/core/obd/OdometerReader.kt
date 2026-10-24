package io.github.conchad.smartgarage.core.obd

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface OdometerRead {
    /**
     * [km] is the profile's value in whole km ([OdometerUnits.toKm]); for [OdometerKind.CODES_CLEARED] that is the
     * 0131 counter, which the caller turns into an odometer with [CodesClearedOffset].
     */
    data class Value(val km: Int, val raw: Double, val response: ObdResponse) : OdometerRead

    data class Failed(val reason: Reason, val detail: String, val status: ElmStatus? = null) : OdometerRead

    enum class Reason {
        /** The adapter replied with an error or nothing (status says which: NO_DATA when the car is off, ...). */
        ADAPTER,
        /** The ECU refused the request (7F); [Failed.detail] names the code. */
        NEGATIVE,
        /** The ECU is still working on it (7F .. 78) and no answer followed. */
        PENDING,
        /** Data that doesn't answer the request, or frames missing. Never evaluated: no wrong odometer uploads. */
        UNEXPECTED,
        /** The expression failed (an index past the reply, division by zero) or gave a non-odometer value. */
        EXPRESSION,
    }
}

/**
 * Reads one profile over an initialized session ([ElmInit.run]). With an automatic protocol the CAN ID width is
 * looked up once with ATDPN after the first data reply, so spaceless ATH1 lines split at the right place.
 */
class OdometerReader(private val session: ElmSession, val profile: OdometerProfile, format: ElmFormat) {
    private var format = format
    private var searched = false
    private var askedProtocol = false
    private val request = ObdRequest.parse(profile.request)
    private val expression = ByteExpression.parse(profile.expression)

    suspend fun read(): OdometerRead {
        // The first request after ATSP0 makes the adapter search protocols, which takes several seconds.
        val reply = session.send(profile.request, if (searched) REQUEST_TIMEOUT else FIRST_REQUEST_TIMEOUT)
        searched = true
        if (reply.status != ElmStatus.DATA) {
            return OdometerRead.Failed(OdometerRead.Reason.ADAPTER, reply.text.ifEmpty { reply.status.name }, reply.status)
        }
        if (format.headers && format.canIdChars == null && !askedProtocol) {
            askedProtocol = true
            val dpn = session.send("ATDPN")
            ElmFormat.canIdChars(ElmFormat.parseDpn(dpn.text))?.let { format = format.copy(canIdChars = it) }
        }
        return when (val parsed = ObdReplyParser.parse(reply.lines, request, format)) {
            is ObdParse.Positive -> evaluate(parsed.response)
            is ObdParse.Negative -> OdometerRead.Failed(
                if (parsed.pending) OdometerRead.Reason.PENDING else OdometerRead.Reason.NEGATIVE,
                "${parsed.ecu ?: "ECU"}: ${parsed.codeName}",
            )
            is ObdParse.Unmatched -> OdometerRead.Failed(
                OdometerRead.Reason.UNEXPECTED,
                "reply from ${parsed.response.ecu ?: "ECU"} doesn't answer ${profile.request}",
            )
            is ObdParse.Invalid -> OdometerRead.Failed(OdometerRead.Reason.UNEXPECTED, parsed.reason)
        }
    }

    private fun evaluate(response: ObdResponse): OdometerRead {
        val raw = try {
            expression.evaluate(response.bytes)
        } catch (e: ByteExpressionException) {
            return OdometerRead.Failed(OdometerRead.Reason.EXPRESSION, e.message.orEmpty())
        }
        val km = OdometerUnits.toKm(raw, profile.unit, profile.multiplier)
            ?: return OdometerRead.Failed(OdometerRead.Reason.EXPRESSION, "$raw isn't a distance")
        return OdometerRead.Value(km, raw, response)
    }

    companion object {
        val REQUEST_TIMEOUT: Duration = 3.seconds
        val FIRST_REQUEST_TIMEOUT: Duration = 12.seconds
    }
}

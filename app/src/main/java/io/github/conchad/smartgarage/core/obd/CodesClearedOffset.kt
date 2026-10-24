package io.github.conchad.smartgarage.core.obd

import io.github.conchad.smartgarage.core.model.OdometerLog
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import kotlinx.serialization.Serializable

/**
 * Where the "distance since codes cleared" counter (PID 0131) was pinned to a real odometer value. Persisted per
 * vehicle so a trip continues from the last one.
 */
@Serializable
data class CodesClearedAnchor(
    /** The manual reading this chain started from; a different one means the user typed a newer value. */
    val manualLogId: String,
    val baseKm: Int,
    val counterAtBase: Int,
    /** The last odometer worked out, the base after the counter resets. */
    val lastKm: Int,
)

/**
 * For cars without an odometer PID: odometer = the last MANUAL reading + how far the 0131 counter has moved since it
 * was first seen after that reading. Distance between the manual reading and the first counter value is not counted
 * (the counter's value then isn't known), so the result can lag; the next manual reading corrects it.
 *
 * Re-anchors when a newer manual reading appears, or when the counter goes DOWN (codes were cleared, or the 16-bit
 * counter wrapped at 65 535 km): the chain then continues from the last odometer with the counter counted from 0.
 */
object CodesClearedOffset {
    /** Readings the app uploaded from the adapter; everything else (typed, from a receipt, from the server) is manual. */
    const val OBD_SOURCE = "obd"

    data class Step(val anchor: CodesClearedAnchor?, val odometerKm: Int?)

    fun latestManual(logs: List<OdometerLog>): OdometerLog? = logs.filter { it.source != OBD_SOURCE }
        .maxWithOrNull(compareBy<OdometerLog> { it.recordedAt.toInstantOrNull() }.thenBy { it.recordedAt })

    /** Without any manual reading there is nothing to add the counter to: no odometer, and no anchor yet. */
    fun step(anchor: CodesClearedAnchor?, latestManual: OdometerLog?, counterKm: Int): Step {
        if (latestManual == null) return Step(anchor, null)
        if (anchor == null || anchor.manualLogId != latestManual.id) {
            val km = latestManual.odometerKm
            return Step(CodesClearedAnchor(latestManual.id, km, counterKm, km), km)
        }
        if (counterKm < anchor.counterAtBase) {
            val km = anchor.lastKm + counterKm
            return Step(anchor.copy(baseKm = anchor.lastKm, counterAtBase = 0, lastKm = km), km)
        }
        val km = anchor.baseKm + (counterKm - anchor.counterAtBase)
        return Step(anchor.copy(lastKm = km), km)
    }
}

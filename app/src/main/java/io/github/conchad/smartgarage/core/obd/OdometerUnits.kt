package io.github.conchad.smartgarage.core.obd

import io.github.conchad.smartgarage.core.format.Formatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.floor

/** The unit a profile's expression yields (WiCAN's "unit" field). */
@Serializable
enum class DistanceUnit {
    @SerialName("km") KM,
    @SerialName("mi") MI;

    companion object {
        /** WiCAN profiles write "km"; "mi"/"mile(s)" too for imperial clusters. Unknown text is null. */
        fun parse(text: String?): DistanceUnit? = when (text?.trim()?.lowercase()) {
            null, "", "km", "kilometers", "kilometres" -> KM
            "mi", "mile", "miles" -> MI
            else -> null
        }
    }
}

/**
 * The server stores whole km; the user's display unit (mi or km) is only a preference ([Formatter.miles]).
 */
object OdometerUnits {
    /**
     * A profile's value × multiplier in its [unit] as whole km, rounded DOWN like a cluster display, so a reading
     * never claims distance not yet driven (01A6's 0.1 km steps and mi → km both land on fractions). Null for a
     * value that can't be an odometer: NaN, infinite, negative or beyond Int.
     */
    fun toKm(value: Double, unit: DistanceUnit, multiplier: Double = 1.0): Int? {
        val km = value * multiplier * if (unit == DistanceUnit.MI) Formatter.KM_PER_MILE else 1.0
        if (!km.isFinite() || km < 0 || km >= Int.MAX_VALUE) return null
        return floor(km).toInt()
    }

    /** A distance in the user's display unit as km (not rounded; thresholds compare against it). */
    fun displayToKm(value: Double, miles: Boolean): Double = if (miles) value * Formatter.KM_PER_MILE else value
}

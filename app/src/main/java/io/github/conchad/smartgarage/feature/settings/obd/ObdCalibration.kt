package io.github.conchad.smartgarage.feature.settings.obd

import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.obd.DistanceUnit
import io.github.conchad.smartgarage.core.obd.OdometerKind
import io.github.conchad.smartgarage.core.obd.OdometerProfile
import kotlin.math.abs
import kotlin.math.max

/**
 * "What does your dash say?" (D-A74): the user's dash reading against the adapter's, and when they differ by a clean
 * factor (mi read as km, a missing /10 …) the profile change that makes them agree.
 */
sealed interface Calibration {
    /** Within [ObdCalibration.matches]: nothing to fix. */
    data object Matches : Calibration

    /** [ratio] = dash / read is [factor]; [profile] is the current one with the fix applied (always CUSTOM). */
    data class Fix(val factor: CleanFactor, val ratio: Double, val profile: OdometerProfile) : Calibration

    /** Off by something that isn't a unit or a power of ten: the expression itself needs looking at. */
    data class NoCleanFactor(val ratio: Double, val differenceKm: Double) : Calibration

    /** Codes-cleared readings are a counter plus the last manual reading, and a zero read can't be scaled. */
    data object NotApplicable : Calibration
}

/** The factors a wrong profile is typically off by. [label] is what the screen shows ("×1.609"). */
enum class CleanFactor(val factor: Double, val label: String) {
    /** The car counts miles, the profile says km. */
    MILES_AS_KM(Formatter.KM_PER_MILE, "×1.609"),

    /** The car counts km, the profile says miles. */
    KM_AS_MILES(1 / Formatter.KM_PER_MILE, "×0.621"),
    TIMES_TEN(10.0, "×10"),
    TENTH(0.1, "×0.1"),
    TIMES_HUNDRED(100.0, "×100"),
    HUNDREDTH(0.01, "×0.01"),
}

object ObdCalibration {
    /** How far apart two clean-factor ratios may be: dash rounding and a few km driven since the read. */
    const val FACTOR_TOLERANCE = 0.015

    /** A difference of at most this many km, or [MATCH_FRACTION] of the reading, is the same odometer. */
    const val MATCH_KM = 3.0
    const val MATCH_FRACTION = 0.001

    fun matches(readKm: Double, dashKm: Double): Boolean = abs(dashKm - readKm) <= max(MATCH_KM, readKm * MATCH_FRACTION)

    /**
     * [readKm]: what "Read now" made of the reply, in km. [dashKm]: the dash reading, converted to km. [customName]
     * names the custom profile a built-in becomes once changed (localized by the caller).
     */
    fun check(readKm: Int, dashKm: Double, profile: OdometerProfile, customName: String?): Calibration {
        if (profile.kind == OdometerKind.CODES_CLEARED || readKm <= 0 || !dashKm.isFinite() || dashKm <= 0) {
            return Calibration.NotApplicable
        }
        val read = readKm.toDouble()
        if (matches(read, dashKm)) return Calibration.Matches
        val ratio = dashKm / read
        val factor = CleanFactor.entries.firstOrNull { abs(ratio / it.factor - 1) <= FACTOR_TOLERANCE }
            ?: return Calibration.NoCleanFactor(ratio, dashKm - read)
        return Calibration.Fix(factor, ratio, fixed(profile, factor, customName))
    }

    /**
     * The unit switches when that is the fix (km ↔ mi, the multiplier stays as written); anything else scales the
     * multiplier. A built-in becomes a custom copy so its stored kind no longer resolves back to the original.
     */
    fun fixed(profile: OdometerProfile, factor: CleanFactor, customName: String?): OdometerProfile {
        val base = profile.resolved()
        val changed = when {
            factor == CleanFactor.MILES_AS_KM && base.unit == DistanceUnit.KM -> base.copy(unit = DistanceUnit.MI)
            factor == CleanFactor.KM_AS_MILES && base.unit == DistanceUnit.MI -> base.copy(unit = DistanceUnit.KM)
            else -> base.copy(multiplier = base.multiplier * factor.factor)
        }
        return if (changed.kind == OdometerKind.CUSTOM) changed else changed.copy(kind = OdometerKind.CUSTOM, name = changed.name ?: customName)
    }
}

/** Parsing what the user typed into the screen's number fields. */
object ObdInputs {
    private val GROUPED = Regex("\\d{1,3}([.,' ]\\d{3})+")

    /**
     * An odometer as typed on either side of the Atlantic: "84,512", "84.512", "84 512" and "84512" are all 84512;
     * a single separator before one or two digits is a decimal ("84512.4", "84512,4"). Null when it isn't a number.
     */
    fun odometer(text: String): Double? {
        val t = text.trim().replace(' ', ' ').replace(' ', ' ')
        if (t.isEmpty()) return null
        if (GROUPED.matches(t)) return t.filter(Char::isDigit).toDoubleOrNull()
        val grouped = Regex("(\\d{1,3}(?:[.,' ]\\d{3})+)[.,](\\d{1,2})").matchEntire(t)
        if (grouped != null) return "${grouped.groupValues[1].filter(Char::isDigit)}.${grouped.groupValues[2]}".toDoubleOrNull()
        return t.replace(',', '.').replace(" ", "").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    }

    /** Poll minutes: whole minutes, null when not a number (the store clamps to 1–60). */
    fun minutes(text: String): Int? = text.trim().toIntOrNull()

    /** A distance threshold in the display unit, "," or "." as the decimal separator. */
    fun distance(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }

    /** A minimum distance as the field shows it: no ".0" on whole numbers. */
    fun distanceText(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()
}

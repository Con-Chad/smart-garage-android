package io.github.conchad.smartgarage.ui.glass

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.conchad.smartgarage.core.data.AuroraChoice
import kotlin.math.abs

/**
 * The user's aurora choices (D-A44): the app-wide one from Settings and the per-vehicle overrides. Provided by the
 * shell; outside it (the setup flows) the default is "match the vehicle everywhere", i.e. the pre-D-A44 behaviour.
 */
@Immutable
data class AuroraChoices(
    val appDefault: AuroraChoice = AuroraChoice.MatchVehicle,
    val overrides: Map<String, AuroraChoice> = emptyMap(),
) {
    /** The app-wide picked color (legibility-clamped), or null when Settings says "match vehicle color". */
    val appTint: Int? get() = (appDefault as? AuroraChoice.Custom)?.let { AuroraTintResolver.legible(it.argb) }
}

val LocalAuroraChoices = compositionLocalOf { AuroraChoices() }

/**
 * Resolution order (D-A44): the vehicle's own override, else the Settings choice, else the car's color
 * ([VehicleTint]). Pure Kotlin on packed ARGB so it is unit-tested; null means "nothing to tint with", and the
 * caller falls back to its theme color as before.
 */
object AuroraTintResolver {
    /** Picked colors are kept inside these HSL lightness bounds so the ribbons never blind or vanish. */
    const val MIN_LIGHTNESS = 0.28f

    /** As light as the steel the car tint already uses for white cars, so text on the aurora reads the same. */
    const val MAX_LIGHTNESS = 0.70f

    fun resolve(override: AuroraChoice?, appDefault: AuroraChoice, vehicleColor: String?): Int? =
        when (val choice = override ?: appDefault) {
            AuroraChoice.MatchVehicle -> VehicleTint.argb(vehicleColor)
            is AuroraChoice.Custom -> legible(choice.argb)
        }

    /** [argb] with its HSL lightness clamped to [MIN_LIGHTNESS]..[MAX_LIGHTNESS], hue and saturation kept. */
    fun legible(argb: Int): Int {
        val (h, s, l) = hsl(argb)
        val clamped = l.coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
        return if (clamped == l) argb or (0xFF shl 24) else fromHsl(h, s, clamped)
    }

    /** True when dark ink ([GlassTokens.onPale]) reads better on [argb] than white (WCAG relative luminance). */
    fun isPale(argb: Int): Boolean {
        fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val lum = 0.2126 * lin((argb shr 16) and 0xFF) + 0.7152 * lin((argb shr 8) and 0xFF) + 0.0722 * lin(argb and 0xFF)
        // White and onPale (#1B2130) give the same contrast at a luminance of about 0.21; above it the ink wins.
        return lum > 0.21
    }

    internal fun hsl(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d < 1e-4f) return Triple(0f, 0f, l)
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return Triple((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    internal fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = Math.round((v + m).coerceIn(0f, 1f) * 255f)
        return (0xFF shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}

/** The aurora color for a vehicle after [AuroraTintResolver.resolve], or the theme primary when nothing applies. */
@Composable
fun vehicleAuroraColor(vehicleId: String?, vehicleColor: String?): Color {
    val choices = LocalAuroraChoices.current
    val override = vehicleId?.let { choices.overrides[it] }
    return AuroraTintResolver.resolve(override, choices.appDefault, vehicleColor)?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
}

/**
 * Sets the shell aurora for a vehicle screen (D-A44): like [AuroraTint], with the vehicle's override and the
 * Settings choice applied before its own color.
 */
@Composable
fun AuroraTint(vehicleId: String?, vehicleColor: String?) {
    AuroraTint(vehicleAuroraColor(vehicleId, vehicleColor))
}

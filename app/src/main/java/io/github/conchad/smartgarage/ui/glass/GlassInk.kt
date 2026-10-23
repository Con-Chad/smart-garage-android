package io.github.conchad.smartgarage.ui.glass

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/*
 * Pill ink (D-A43): the labels and little arrows of the pills and text actions that sit over the aurora ("View all",
 * "Quick info", the group headers' "See all", Home's "Swipe to view more" / "Add vehicle") are white, not the gold
 * sheen, and turn near-black ([GlassTokens.onPale]) when what is behind them is light: the light theme, or a pale
 * aurora (white, silver, a pale user-chosen tint). The check runs on the tint the shell actually draws
 * ([AuroraTintHolder.effective]), so it follows any theme colour, not only the car's.
 */

/**
 * White on the pill while it keeps at least [PILL_INK_MIN_CONTRAST] against the estimated backdrop, else
 * [GlassTokens.onPale]. The user asked for white wherever it reads, so white wins any backdrop it clears 3:1 on
 * (WCAG's floor for UI components and bold labels) rather than whichever of the two contrasts more.
 */
fun pillInk(tokens: GlassTokens, tint: Color): Color {
    val backdrop = estimatedPillBackdrop(tokens, tint)
    return if (contrastRatio(tokens.onPhoto, backdrop) >= PILL_INK_MIN_CONTRAST) tokens.onPhoto else tokens.onPale
}

/**
 * Roughly what is behind a pill label: the aurora's main ribbon colour ([VehicleTint.palette]) at the ribbons' mean
 * opacity over the page background, under the pill's own fill. Calibrated on the Pixel against a green car's Home
 * (measured backdrops 0.19–0.28 relative luminance; this estimates 0.23).
 */
internal fun estimatedPillBackdrop(tokens: GlassTokens, tint: Color): Color {
    val ribbon = VehicleTint.palette(tint)[0].copy(alpha = tokens.auroraAlpha * RIBBON_COVERAGE)
    return tokens.pill.compositeOver(ribbon.compositeOver(tokens.pageBackground))
}

/** WCAG 2 contrast ratio between two opaque colours, 1..21. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** The pill ink for the tint the shell is drawing now. */
@Composable
fun rememberPillInk(): Color {
    val tokens = glass
    val tint = LocalAuroraTint.current.effective ?: MaterialTheme.colorScheme.primary
    return remember(tokens, tint) { pillInk(tokens, tint) }
}

/** Text buttons that sit on the aurora (the group headers' actions) in the pill ink. */
@Composable
fun glassInkTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = rememberPillInk())

/** The three ribbons' mean opacity multiplier (1, 0.82, 0.65 in [AuroraBackground]). */
private const val RIBBON_COVERAGE = 0.82f

/** White pill labels must clear this against the backdrop; below it they go dark. */
private const val PILL_INK_MIN_CONTRAST = 3f

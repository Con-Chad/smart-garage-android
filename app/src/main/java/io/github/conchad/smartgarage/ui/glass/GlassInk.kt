package io.github.conchad.smartgarage.ui.glass

import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

/*
 * Pill ink (D-A43): the labels and little arrows of the pills and text actions that sit over the aurora ("View all",
 * "Quick info", the group headers' "See all", Home's "Swipe to view more" / "Add vehicle") are white, not the gold
 * sheen, and turn to the dark ink ([GlassTokens.inkDark], D-A49, D-A70) when that contrasts more with what is behind them:
 * the light theme, or a pale aurora (white, silver, a pale user-chosen tint). The check runs on the tint the shell actually draws
 * ([AuroraTintHolder.effective]), so it follows any theme colour, not only the car's.
 *
 * D-A46: inside the shell that choice is refined per pixel row by the adaptive ink ([auroraInk], [rememberPillInkSpec]):
 * the label follows the aurora actually behind the pill, light or dark by the same rule as text (D-A70). [pillInk] remains the composed colour, used where there is no aurora map.
 */

/**
 * The pill label's composed colour (D-A70): the same rule as all text on the aurora ([AdaptiveInk.choose]),
 * [GlassTokens.onPhoto] or [GlassTokens.inkDark], whichever contrasts more with the estimated backdrop. (D-A49 kept
 * white down to 3:1; text now aims for 4.5:1 everywhere.)
 */
fun pillInk(tokens: GlassTokens, tint: Color): Color {
    val backdrop = estimatedPillBackdrop(tokens, tint)
    return if (AdaptiveInk.choose(backdrop.luminance()) == 0f) tokens.onPhoto else tokens.inkDark
}

/**
 * D-A70: these tokens with the text colours used on glass panels ([GlassTokens.textPrimary], [GlassTokens.textSecondary]
 * and the static metal, [GlassTokens.metalDim] / [GlassTokens.metalMid]) each made [AdaptiveInk.legible] against
 * every backdrop a panel can sit on over this aurora ([AuroraLumaMap.luminanceRange] under [GlassTokens.panel]).
 * Where the theme's colours already clear 4.5:1 (most tints) they come back unchanged; over a bright aurora on the
 * dark theme the grey secondary text lightens, over a dark one on the light theme it darkens, and where even the
 * theme's ink can't reach 4.5:1 the panel fill itself gets denser (up to [MAX_PANEL_ALPHA]). The headings' glass strip
 * is densified the same way for their adaptive ink ([headingStripOver], D-A73). Unchanged with no map.
 */
fun GlassTokens.legibleOnPanels(map: AuroraLumaMap?): GlassTokens {
    if (map == null) return this
    // The panel itself first: where the theme's secondary text (its faintest) can't clear 4.5:1 over the brightest
    // (dark theme) or darkest (light theme) aurora, the fill gets just dense enough that it does, up to
    // [MAX_PANEL_ALPHA], so the cards keep their two text tones. Measured with half the panel's top gloss on the light
    // end, which is where the cards' titles sit.
    // The gloss only brightens, so it only counts toward the light end (worse for light text, better for dark).
    fun range(fill: Color): ClosedFloatingPointRange<Float> {
        val glossed = panelHighlight.copy(alpha = panelHighlight.alpha / 2f).compositeOver(fill)
        return map.luminanceRange(fill).start..map.luminanceRange(glossed).endInclusive
    }
    fun reads(fill: Color): Boolean {
        val r = range(fill)
        return AdaptiveInk.worstContrast(textSecondary.luminance(), r.start, r.endInclusive) >= PANEL_TEXT_CONTRAST
    }
    var fill = panel
    if (!reads(fill)) {
        var a = panel.alpha
        var b = MAX_PANEL_ALPHA
        if (reads(panel.copy(alpha = b))) {
            repeat(12) {
                val m = (a + b) / 2f
                if (reads(panel.copy(alpha = m))) b = m else a = m
            }
        }
        fill = panel.copy(alpha = b)
    }
    // Then each text colour, against what is left: unchanged where it reads, else moved toward its ink.
    val r = range(fill)
    val lo = r.start
    val hi = r.endInclusive
    fun fix(c: Color) = AdaptiveInk.legible(c, lo, hi, onPhoto, inkDark, PANEL_TEXT_CONTRAST)
    val fixed = copy(
        panel = fill,
        textPrimary = fix(textPrimary),
        textSecondary = fix(textSecondary),
        metalDim = fix(metalDim),
        metalMid = fix(metalMid),
        headingStrip = headingStripOver(map),
    )
    return if (fixed == this) this else fixed
}

/**
 * D-A73: [GlassTokens.headingStrip] just dense enough (up to [MAX_PANEL_ALPHA]) that one of the two adaptive inks
 * ([GlassTokens.onPhoto], [GlassTokens.inkDark]) clears [HEADING_STRIP_CONTRAST] over every backdrop the strip can be
 * over on this aurora ([AuroraLumaMap.luminanceRange]). The heading on it keeps the D-A70 rule ([AdaptiveInk.choose]
 * against the aurora under the strip, [rememberHeadingStripInkSpec]), which then lands on that ink wherever it sits.
 */
fun GlassTokens.headingStripOver(map: AuroraLumaMap): Color {
    fun reads(fill: Color): Boolean {
        val r = map.luminanceRange(fill)
        return maxOf(
            AdaptiveInk.worstContrast(onPhoto.luminance(), r.start, r.endInclusive),
            AdaptiveInk.worstContrast(inkDark.luminance(), r.start, r.endInclusive),
        ) >= HEADING_STRIP_CONTRAST
    }
    if (reads(headingStrip)) return headingStrip
    var a = headingStrip.alpha
    var b = MAX_PANEL_ALPHA
    if (reads(headingStrip.copy(alpha = b))) {
        repeat(12) {
            val m = (a + b) / 2f
            if (reads(headingStrip.copy(alpha = m))) b = m else a = m
        }
    }
    return headingStrip.copy(alpha = b)
}

/** The adaptive ink of a heading on its glass strip (D-A73): the D-A70 rule against the aurora under [GlassTokens.headingStrip]. */
@Composable
fun rememberHeadingStripInkSpec(): AuroraInkSpec {
    val strip = glass.headingStrip
    return remember(strip) { AuroraInkSpec { strip } }
}

/**
 * What a heading on its glass strip aims for (D-A73): the contrast from which the adaptive ink needs no halo
 * ([AdaptiveInk.HALO_FROM], 5.5:1), so the glyphs stay clean on the glass and keep a margin over 4.5:1 for the
 * aurora drifting under the strip.
 */
const val HEADING_STRIP_CONTRAST = AdaptiveInk.HALO_FROM

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

/**
 * The adaptive pill ink (D-A46, D-A48: the same continuous mapping as text) against the aurora under the pill's own fill,
 * and under a glass card's panel too when [inCard] (the vehicle cards' "View all" / "Quick info").
 */
@Composable
fun rememberPillInkSpec(inCard: Boolean): AuroraInkSpec {
    val tokens = glass
    return remember(tokens, inCard) {
        val overlay = if (inCard) pillInCardOverlay(tokens) else tokens.pill
        AuroraInkSpec { overlay }
    }
}

/** Text buttons that sit on the aurora (the group headers' actions) in the pill ink. */
@Composable
fun glassInkTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = rememberPillInk())

/**
 * What the panels' text aims for over the aurora's colour map (D-A70): 4.5:1 with a margin, since the map is the
 * aurora's average over its drift and the moment's backdrop under a card runs a little either side of it (measured
 * on the Pixel: up to ~10 %).
 */
private const val PANEL_TEXT_CONTRAST = 5f

/** The densest a glass panel gets to keep its text legible over a bright aurora (D-A70). */
private const val MAX_PANEL_ALPHA = 0.75f

/** The three ribbons' mean opacity multiplier (1, 0.82, 0.65 in [AuroraBackground]). */
private const val RIBBON_COVERAGE = 0.82f

/**
 * The fill of an emphasised pill on the aurora with a fixed white label (D-A52, Home's "Add vehicle"): the theme
 * colour [tint] taken down to at most [EMPHASIS_VALUE] brightness (hue and saturation kept, so it reads as a strong
 * tint of the aurora rather than a grey hole) at [EMPHASIS_ALPHA], dense enough that [GlassTokens.onPhoto] keeps
 * 4.5:1 over even the lightest aurora band.
 */
fun emphasisPillFill(tint: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(tint.toArgb(), hsv)
    hsv[2] = minOf(hsv[2], EMPHASIS_VALUE)
    return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = EMPHASIS_ALPHA)
}

/** The emphasised pill's rim: [GlassTokens.onPhoto] at this opacity, 1.5 dp (D-A52). */
const val EMPHASIS_RIM_ALPHA = 0.6f

private const val EMPHASIS_VALUE = 0.34f
private const val EMPHASIS_ALPHA = 0.88f

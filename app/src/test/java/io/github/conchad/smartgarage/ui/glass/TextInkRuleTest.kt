package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * D-A70: the one text-colour rule, checked across every preset of the theme colour picker in both themes. The aurora is
 * stood in for by a map of its extremes (the page alone, and each ribbon colour over it at full ribbon strength), which
 * brackets anything the real, softer aurora draws.
 */
class TextInkRuleTest {
    private fun lum(c: Color) = AdaptiveInk.relativeLuminance(c.red, c.green, c.blue)

    /** Each ribbon of [tint]'s palette over the page as the theme blends it (screen on dark, normal on light), and the page. */
    private fun extremesMap(tokens: GlassTokens, tint: Color): AuroraLumaMap {
        val page = tokens.pageBackground
        val a = tokens.auroraAlpha
        val cells = listOf(page) + VehicleTint.palette(tint).map { ribbon ->
            if (tokens.dark) {
                // Screen: 1 − (1 − a·s)(1 − d), per channel.
                Color(
                    red = 1f - (1f - a * ribbon.red) * (1f - page.red),
                    green = 1f - (1f - a * ribbon.green) * (1f - page.green),
                    blue = 1f - (1f - a * ribbon.blue) * (1f - page.blue),
                )
            } else {
                ribbon.copy(alpha = a).compositeOver(page)
            }
        }
        val rgb = FloatArray(cells.size * 3)
        cells.forEachIndexed { i, c ->
            rgb[i * 3] = c.red
            rgb[i * 3 + 1] = c.green
            rgb[i * 3 + 2] = c.blue
        }
        return AuroraLumaMap(1, cells.size, rgb)
    }

    private val presets = AuroraPresets.map { Color(AuroraTintResolver.legible(it)) }

    @Test
    fun `legible keeps a colour that already reads and moves one that doesn't just far enough`() {
        val grey = DarkGlassTokens.textSecondary
        // On a dark range it reads as is.
        assertThat(AdaptiveInk.legible(grey, 0.0f, 0.05f, DarkGlassTokens.onPhoto, DarkGlassTokens.inkDark)).isEqualTo(grey)
        // On a brighter one it lightens until the worst end clears 4.5:1, and no further.
        val moved = AdaptiveInk.legible(grey, 0.02f, 0.14f, DarkGlassTokens.onPhoto, DarkGlassTokens.inkDark)
        assertThat(lum(moved)).isGreaterThan(lum(grey))
        assertThat(AdaptiveInk.worstContrast(lum(moved), 0.02f, 0.14f)).isAtLeast(AdaptiveInk.MIN_CONTRAST)
        assertThat(AdaptiveInk.worstContrast(lum(moved), 0.02f, 0.14f)).isLessThan(AdaptiveInk.MIN_CONTRAST + 0.1f)
        // A dark colour darkens toward the dark ink on a darker range.
        val blueGrey = LightGlassTokens.textSecondary
        val darker = AdaptiveInk.legible(blueGrey, 0.40f, 0.9f, LightGlassTokens.onPhoto, LightGlassTokens.inkDark)
        assertThat(lum(darker)).isLessThan(lum(blueGrey))
        assertThat(AdaptiveInk.worstContrast(lum(darker), 0.40f, 0.9f)).isAtLeast(AdaptiveInk.MIN_CONTRAST)
        // Where nothing on its side can reach it, all the way to that end.
        assertThat(AdaptiveInk.legible(grey, 0.0f, 0.6f, DarkGlassTokens.onPhoto, DarkGlassTokens.inkDark)).isEqualTo(DarkGlassTokens.onPhoto)
    }

    @Test
    fun `worst contrast is 1 inside the range and the nearer end's outside it`() {
        assertThat(AdaptiveInk.worstContrast(0.3f, 0.2f, 0.4f)).isEqualTo(1f)
        assertThat(AdaptiveInk.worstContrast(0.9f, 0.1f, 0.2f)).isWithin(1e-4f).of(AdaptiveInk.contrast(0.9f, 0.2f))
        assertThat(AdaptiveInk.worstContrast(0.01f, 0.3f, 0.6f)).isWithin(1e-4f).of(AdaptiveInk.contrast(0.01f, 0.3f))
    }

    @Test
    fun `luminance range spans the map under the overlay`() {
        val map = AuroraLumaMap(1, 2, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f))
        val bare = map.luminanceRange(Color.Transparent)
        assertThat(bare.start).isWithin(1e-4f).of(0f)
        assertThat(bare.endInclusive).isWithin(1e-4f).of(1f)
        val veiled = map.luminanceRange(Color.Black.copy(alpha = 0.5f))
        assertThat(veiled.endInclusive).isLessThan(0.5f)
    }

    @Test
    fun `card text clears 4_5 to 1 on every preset in both themes`() {
        for (tokens in listOf(DarkGlassTokens, LightGlassTokens)) {
            for (tint in presets) {
                val map = extremesMap(tokens, tint)
                val fixed = tokens.legibleOnPanels(map)
                // Over the panel as it is drawn: denser where even the ink needed it.
                val range = map.luminanceRange(tokens.panelHighlight.copy(alpha = tokens.panelHighlight.alpha / 2f).compositeOver(fixed.panel))
                assertThat(fixed.panel.alpha).isAtLeast(tokens.panel.alpha)
                for ((name, c) in listOf("primary" to fixed.textPrimary, "secondary" to fixed.textSecondary, "metalDim" to fixed.metalDim, "metalMid" to fixed.metalMid)) {
                    val worst = AdaptiveInk.worstContrast(lum(c), range.start, range.endInclusive)
                    // This bracket is harsher than any real frame (full-strength ribbons, no veil): where even the
                    // ink can't reach 4.5:1 on it the colour is the ink itself, and still close.
                    if (c == tokens.onPhoto || c == tokens.inkDark) {
                        assertWithMessage("$name on ${tint.value} dark=${tokens.dark}").that(worst).isAtLeast(4.2f)
                    } else {
                        assertWithMessage("$name on ${tint.value} dark=${tokens.dark}").that(worst).isAtLeast(AdaptiveInk.MIN_CONTRAST)
                    }
                }
                // The hierarchy survives: secondary text is never further from the backdrop than primary.
                val mid = (range.start + range.endInclusive) / 2f
                assertThat(AdaptiveInk.contrast(lum(fixed.textSecondary), mid)).isAtMost(AdaptiveInk.contrast(lum(fixed.textPrimary), mid) + 1e-3f)
            }
        }
    }

    @Test
    fun `text on the aurora takes the better ink on every preset in both themes, 4_5 to 1 outside the crossover`() {
        for (tokens in listOf(DarkGlassTokens, LightGlassTokens)) {
            for (tint in presets) {
                val map = extremesMap(tokens, tint)
                for (i in 0 until map.rows) {
                    val backdrop = map.luminanceAt(0.5f, (i + 0.5f) / map.rows)
                    val ink = AdaptiveInk.ink(AdaptiveInk.choose(backdrop), tokens.onPhoto, tokens.inkDark)
                    val c = AdaptiveInk.contrast(ink.luminance(), backdrop)
                    val light = AdaptiveInk.contrast(tokens.onPhoto.luminance(), backdrop)
                    val dark = AdaptiveInk.contrast(tokens.inkDark.luminance(), backdrop)
                    assertThat(c).isAtLeast(maxOf(light, dark) * 0.92f)
                    if (backdrop <= 0.16f || backdrop >= 0.25f) assertThat(c).isAtLeast(AdaptiveInk.MIN_CONTRAST)
                    // In the gap, the halo makes up the shortfall.
                    if (c < AdaptiveInk.MIN_CONTRAST) assertThat(AdaptiveInk.haloStrength(ink.luminance(), backdrop)).isGreaterThan(0f)
                }
            }
        }
    }
}

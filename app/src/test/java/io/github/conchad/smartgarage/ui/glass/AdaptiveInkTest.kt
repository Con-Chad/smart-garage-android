package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-A46: the backdrop luminance → ink mapping and the aurora colour map. */
class AdaptiveInkTest {
    private val light = DarkGlassTokens.onPhoto
    private val dark = DarkGlassTokens.onPale
    private val lightLum = light.luminance()
    private val darkLum = dark.luminance()

    private fun grey(l: Float): Color {
        // An sRGB grey of relative luminance l.
        val c = if (l <= 0.0031308f) l * 12.92f else (1.055f * Math.pow(l.toDouble(), 1 / 2.4).toFloat() - 0.055f)
        return Color(c, c, c)
    }

    private fun inkOn(backdrop: Float, minContrast: Float): Color {
        val (lo, hi) = AdaptiveInk.band(lightLum, darkLum, minContrast)
        return AdaptiveInk.ink(AdaptiveInk.mix(backdrop, lo, hi), light, dark)
    }

    @Test
    fun `relative luminance matches compose`() {
        for (c in listOf(Color.White, Color.Black, light, dark, Color(0xFF2E9A55), Color(0xFF9FB3C8))) {
            assertEquals(c.luminance(), AdaptiveInk.relativeLuminance(c.red, c.green, c.blue), 1e-3f)
        }
    }

    @Test
    fun `text band is thin and centred where both inks contrast equally`() {
        val (lo, hi) = AdaptiveInk.band(lightLum, darkLum, AdaptiveInk.TEXT_MIN_CONTRAST)
        val c = AdaptiveInk.crossover(lightLum, darkLum)
        assertEquals(contrastRatio(light, grey(c)), contrastRatio(dark, grey(c)), 0.05f)
        assertEquals(AdaptiveInk.BAND, hi - lo, 1e-4f)
        assertTrue(lo < c && c < hi)
        // The band sits inside the gap no ink can bridge at 4.5:1.
        assertTrue(lo > AdaptiveInk.lightLimit(lightLum, 4.5f) && hi < AdaptiveInk.darkLimit(darkLum, 4.5f))
        // Its edges still give at least 4:1.
        assertTrue(contrastRatio(light, grey(lo)) >= 4f)
        assertTrue(contrastRatio(dark, grey(hi)) >= 4f)
    }

    @Test
    fun `outside the band text ink is the better of the two and clears 4_5 to 1 outside the gap`() {
        val (lo, hi) = AdaptiveInk.band(lightLum, darkLum, AdaptiveInk.TEXT_MIN_CONTRAST)
        val gapLo = AdaptiveInk.lightLimit(lightLum, 4.5f)
        val gapHi = AdaptiveInk.darkLimit(darkLum, 4.5f)
        var l = 0f
        while (l <= 1f) {
            if (l <= lo - 0.002f || l >= hi + 0.002f) {
                val g = grey(l)
                val ink = inkOn(l, AdaptiveInk.TEXT_MIN_CONTRAST)
                val best = maxOf(contrastRatio(light, g), contrastRatio(dark, g))
                assertEquals("backdrop $l", best, contrastRatio(ink, g), 0.05f)
                if (l < gapLo - 0.002f || l > gapHi + 0.002f) assertTrue("backdrop $l", contrastRatio(ink, g) >= 4.45f)
            }
            l += 0.01f
        }
    }

    @Test
    fun `ends are exactly white and near-black`() {
        assertEquals(light, inkOn(0f, AdaptiveInk.TEXT_MIN_CONTRAST))
        assertEquals(light, inkOn(0.1f, AdaptiveInk.TEXT_MIN_CONTRAST))
        assertEquals(dark, inkOn(0.5f, AdaptiveInk.TEXT_MIN_CONTRAST))
        assertEquals(dark, inkOn(1f, AdaptiveInk.TEXT_MIN_CONTRAST))
    }

    @Test
    fun `mix is continuous and monotonic, passing through grey`() {
        val (lo, hi) = AdaptiveInk.band(lightLum, darkLum, AdaptiveInk.TEXT_MIN_CONTRAST)
        var previous = 0f
        var l = 0f
        while (l <= 1f) {
            val m = AdaptiveInk.mix(l, lo, hi)
            assertTrue(m >= previous)
            // No step larger than the band allows for a 0.001 change in luminance: no snapping.
            assertTrue("jump at $l", m - previous < 0.2f)
            previous = m
            l += 0.001f
        }
        val middle = AdaptiveInk.ink(0.5f, light, dark)
        assertTrue(middle.luminance() in darkLum..lightLum)
        assertTrue(middle.luminance() > darkLum + 0.1f && middle.luminance() < lightLum - 0.1f)
    }

    @Test
    fun `pills prefer white wherever it clears 3 to 1 (D-A43)`() {
        val (lo, hi) = AdaptiveInk.band(lightLum, darkLum, AdaptiveInk.PILL_MIN_CONTRAST)
        assertEquals(3f, contrastRatio(light, grey(hi)), 0.05f)
        assertEquals(AdaptiveInk.BAND, hi - lo, 1e-4f)
        // Dark ink at the low end of the band clears 4:1.
        assertTrue(contrastRatio(dark, grey(lo)) >= 4f)
        assertEquals(light, inkOn(hi - AdaptiveInk.BAND - 0.001f, AdaptiveInk.PILL_MIN_CONTRAST))
    }

    @Test
    fun `luma map samples bilinearly and composites the overlay`() {
        // Two rows: black over white, one column.
        val map = AuroraLumaMap(1, 2, floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f))
        assertEquals(0f, map.luminanceAt(0.5f, 0f), 1e-4f)
        assertEquals(1f, map.luminanceAt(0.5f, 1f), 1e-4f)
        // Halfway between the cell centres: sRGB 0.5 grey.
        val mid = map.luminanceAt(0.5f, 0.5f)
        assertEquals(Color(0.5f, 0.5f, 0.5f).luminance(), mid, 3e-3f) // Color quantises sRGB to 8 bits
        // Monotonic down the column.
        var previous = -1f
        for (i in 0..20) {
            val v = map.luminanceAt(0.5f, i / 20f)
            assertTrue(v >= previous)
            previous = v
        }
        // A half-opaque black overlay on white halves the sRGB value (the alpha as Color stores it).
        val half = Color.Black.copy(alpha = 0.5f)
        val v = 1f - half.alpha
        assertEquals(AdaptiveInk.relativeLuminance(v, v, v), map.luminanceAt(0.5f, 1f, half), 1e-4f)
        // Out of range clamps.
        assertEquals(1f, map.luminanceAt(2f, 3f), 1e-4f)
    }

    @Test
    fun `a dark-bottom light-top gradient gives white below and dark above`() {
        // The user's example: dark green at the bottom, light green at the top.
        val rows = 8
        val rgb = FloatArray(rows * 3)
        for (y in 0 until rows) {
            val t = 1f - y / (rows - 1f)
            rgb[y * 3] = 0.05f + 0.55f * t
            rgb[y * 3 + 1] = 0.20f + 0.75f * t
            rgb[y * 3 + 2] = 0.05f + 0.50f * t
        }
        val map = AuroraLumaMap(1, rows, rgb)
        val (lo, hi) = AdaptiveInk.band(lightLum, darkLum, AdaptiveInk.TEXT_MIN_CONTRAST)
        assertEquals(1f, AdaptiveInk.mix(map.luminanceAt(0.5f, 0f), lo, hi), 1e-4f)
        assertEquals(0f, AdaptiveInk.mix(map.luminanceAt(0.5f, 1f), lo, hi), 1e-4f)
    }

    @Test
    fun `settled ink is light or dark with hysteresis, never grey`() {
        assertEquals(0f, AdaptiveInk.settle(0f, 1f))
        assertEquals(1f, AdaptiveInk.settle(1f, 0f))
        // Hovering mid-band keeps whatever it was.
        assertEquals(0f, AdaptiveInk.settle(0.5f, 0f))
        assertEquals(1f, AdaptiveInk.settle(0.5f, 1f))
        assertEquals(0f, AdaptiveInk.settle(0.69f, 0f))
        assertEquals(1f, AdaptiveInk.settle(0.71f, 0f))
        assertEquals(0f, AdaptiveInk.settle(0.29f, 1f))
        var l = 0f
        while (l <= 1f) {
            val r = AdaptiveInk.settle(l, 0f)
            assertTrue(r == 0f || r == 1f)
            l += 0.05f
        }
    }
}

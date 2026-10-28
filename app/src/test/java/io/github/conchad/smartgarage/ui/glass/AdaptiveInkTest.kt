package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-A46, D-A49, D-A52, D-A70: the backdrop luminance → ink rule, the halo and the aurora colour map. */
class AdaptiveInkTest {
    private val light = DarkGlassTokens.onPhoto
    private val dark = DarkGlassTokens.inkDark
    private val lightLum = light.luminance()
    private val darkLum = dark.luminance()

    private fun inkOn(backdrop: Float): Color = AdaptiveInk.ink(AdaptiveInk.choose(backdrop), light, dark)

    @Test
    fun `relative luminance matches compose`() {
        for (c in listOf(Color.White, Color.Black, light, dark, Color(0xFF2E9A55), Color(0xFF9FB3C8))) {
            assertEquals(c.luminance(), AdaptiveInk.relativeLuminance(c.red, c.green, c.blue), 1e-3f)
        }
    }

    @Test
    fun `the crossover is where the light and dark inks contrast equally (D-A70)`() {
        val crossover = kotlin.math.sqrt((lightLum + 0.05f) * (darkLum + 0.05f)) - 0.05f
        assertEquals(crossover, AdaptiveInk.CENTER, 0.003f)
    }

    @Test
    fun `the ink is always one end, whichever contrasts more, never a grey between (D-A70)`() {
        var l = 0f
        while (l <= 1f) {
            val ink = inkOn(l)
            assertTrue("at $l", ink == light || ink == dark)
            val chosen = AdaptiveInk.contrast(ink.luminance(), l)
            val other = AdaptiveInk.contrast(if (ink == light) darkLum else lightLum, l)
            // Exactly the better one, give or take the hysteresis right at the crossover.
            assertTrue("at $l", chosen >= other / (AdaptiveInk.HYSTERESIS * AdaptiveInk.HYSTERESIS))
            l += 0.001f
        }
        // D-A49's grey band is gone: the old resting grey at 0.29 is now fully dark, 0.15 fully light.
        assertEquals(1f, AdaptiveInk.choose(0.29f))
        assertEquals(0f, AdaptiveInk.choose(0.15f))
    }

    @Test
    fun `near the crossover the previous ink stands, so it doesn't flicker (D-A70)`() {
        val justAbove = (AdaptiveInk.CENTER + 0.05f) * 1.01f - 0.05f
        val justBelow = (AdaptiveInk.CENTER + 0.05f) / 1.01f - 0.05f
        assertEquals(0f, AdaptiveInk.choose(justAbove, previous = 0f))
        assertEquals(1f, AdaptiveInk.choose(justBelow, previous = 1f))
        // With no previous ink, the nearer side.
        assertEquals(1f, AdaptiveInk.choose(justAbove))
        assertEquals(0f, AdaptiveInk.choose(justBelow))
        // Past the margin it flips whatever came before.
        val farAbove = (AdaptiveInk.CENTER + 0.05f) * 1.05f - 0.05f
        val farBelow = (AdaptiveInk.CENTER + 0.05f) / 1.05f - 0.05f
        assertEquals(1f, AdaptiveInk.choose(farAbove, previous = 0f))
        assertEquals(0f, AdaptiveInk.choose(farBelow, previous = 1f))
    }

    @Test
    fun `the chosen ink clears 4_5 to 1 everywhere outside the narrow crossover gap (D-A70)`() {
        var l = 0f
        while (l <= 1f) {
            val c = AdaptiveInk.contrast(inkOn(l).luminance(), l)
            // The best any pair of inks can do at the crossover; the halo makes up the rest there.
            assertTrue("at $l", c >= 3.7f)
            if (l <= 0.16f || l >= 0.25f) assertTrue("at $l: $c", c >= AdaptiveInk.MIN_CONTRAST)
            l += 0.001f
        }
    }

    @Test
    fun `a halo backs any ink short of 4_5 to 1, with a margin for the drift, and only then (D-A52, D-A70)`() {
        var l = 0f
        while (l <= 1f) {
            val ink = inkOn(l).luminance()
            val c = AdaptiveInk.contrast(ink, l)
            val halo = AdaptiveInk.haloStrength(ink, l)
            if (c >= AdaptiveInk.HALO_FROM) assertEquals("at $l", 0f, halo)
            // Short of 4.5:1: a clearly visible halo, not a trace.
            if (c < AdaptiveInk.MIN_CONTRAST) assertTrue("at $l", halo > 0.3f)
            l += 0.005f
        }
        // Full strength at or below 2:1; for a fixed ink, continuous in the backdrop (no step bigger than a few
        // percent per 0.001 of luminance), so it follows the ink's own low-pass without popping.
        assertEquals(1f, AdaptiveInk.haloStrength(0.5f, 0.5f))
        for (ink in listOf(lightLum, darkLum)) {
            var previous = AdaptiveInk.haloStrength(ink, 0f)
            l = 0.001f
            while (l <= 1f) {
                val h = AdaptiveInk.haloStrength(ink, l)
                assertTrue("jump at $l", kotlin.math.abs(h - previous) < 0.05f)
                previous = h
                l += 0.001f
            }
        }
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
    fun `above the lightest band content holds the ink of the band (D-A49)`() {
        // Dark at the top, lightest at row 5 of 8, dark again at the bottom: text rising past the band stays dark.
        val lums = floatArrayOf(0.05f, 0.1f, 0.2f, 0.3f, 0.45f, 0.9f, 0.3f, 0.05f)
        val rgb = FloatArray(lums.size * 3)
        for (i in lums.indices) for (k in 0..2) rgb[i * 3 + k] = lums[i]
        val map = AuroraLumaMap(1, lums.size, rgb)
        val band = (5 + 0.5f) / lums.size
        for (y in listOf(0f, 0.1f, 0.3f, 0.6f)) {
            assertEquals(band, map.holdY(0.5f, y), 1e-4f)
            assertEquals(1f, AdaptiveInk.choose(map.luminanceAt(0.5f, map.holdY(0.5f, y))), 1e-4f)
        }
        // At and below the band, its own position.
        assertEquals(0.7f, map.holdY(0.5f, 0.7f), 1e-6f)
        assertEquals(0.95f, map.holdY(0.5f, 0.95f), 1e-6f)
        // The held luminance never falls as text rises.
        var previous = 0f
        var y = 0.69f
        while (y >= 0f) {
            val l = map.luminanceAt(0.5f, map.holdY(0.5f, y))
            assertTrue("at $y", l >= previous - 1e-4f)
            previous = l
            y -= 0.01f
        }
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
        assertEquals(1f, AdaptiveInk.choose(map.luminanceAt(0.5f, 0f)), 1e-4f)
        assertEquals(0f, AdaptiveInk.choose(map.luminanceAt(0.5f, 1f)), 1e-4f)
    }
}

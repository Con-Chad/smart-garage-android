package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-A46, D-A48, D-A49: the backdrop luminance → ink mapping and the aurora colour map. */
class AdaptiveInkTest {
    private val light = DarkGlassTokens.onPhoto
    private val dark = DarkGlassTokens.inkDark
    private val lightLum = light.luminance()
    private val darkLum = dark.luminance()

    private fun inkOn(backdrop: Float): Color = AdaptiveInk.ink(AdaptiveInk.mix(backdrop), light, dark)

    @Test
    fun `relative luminance matches compose`() {
        for (c in listOf(Color.White, Color.Black, light, dark, Color(0xFF2E9A55), Color(0xFF9FB3C8))) {
            assertEquals(c.luminance(), AdaptiveInk.relativeLuminance(c.red, c.green, c.blue), 1e-3f)
        }
    }

    @Test
    fun `grey span is centred on 0_40 on the contrast axis (D-A48)`() {
        val lo = AdaptiveInk.BAND_LO
        val hi = AdaptiveInk.BAND_HI
        assertTrue(lo < AdaptiveInk.CENTER && AdaptiveInk.CENTER < hi)
        // Symmetric in ln(L + 0.05) around the centre within a few percent.
        val down = (AdaptiveInk.CENTER + 0.05f) / (lo + 0.05f)
        val up = (hi + 0.05f) / (AdaptiveInk.CENTER + 0.05f)
        assertEquals(down, up, 0.02f)
        // Mid-grey at the centre.
        assertEquals(0.5f, AdaptiveInk.mix(AdaptiveInk.CENTER), 0.03f)
    }

    @Test
    fun `ends are exactly the light and dark inks`() {
        assertEquals(light, inkOn(0f))
        assertEquals(light, inkOn(0.2f))
        assertEquals(light, inkOn(AdaptiveInk.BAND_LO))
        assertEquals(dark, inkOn(AdaptiveInk.BAND_HI))
        assertEquals(dark, inkOn(1f))
    }

    @Test
    fun `dark end is the dark grey, at least 4_5 to 1 wherever it is fully applied (D-A49)`() {
        assertEquals(Color(0xFF474B54), dark)
        var l = AdaptiveInk.BAND_HI
        while (l <= 1f) {
            assertEquals(dark, inkOn(l))
            assertTrue("contrast at $l", (l + 0.05f) / (darkLum + 0.05f) >= 4.5f)
            l += 0.01f
        }
    }

    @Test
    fun `white holds past the old 0_20 crossover`() {
        // D-A46 turned dark from about 0.21; the user wanted white further up the gradient.
        assertEquals(0f, AdaptiveInk.mix(0.25f))
        assertEquals(0f, AdaptiveInk.mix(0.30f))
    }

    @Test
    fun `mix is continuous and monotonic, resting on grey in mid tones`() {
        var previous = 0f
        var l = 0f
        while (l <= 1f) {
            val m = AdaptiveInk.mix(l)
            assertTrue(m >= previous)
            // A 0.001 change in luminance moves the ink by a few percent at most: no snapping.
            assertTrue("jump at $l", m - previous < 0.03f)
            previous = m
            l += 0.001f
        }
        val middle = inkOn(AdaptiveInk.CENTER)
        assertTrue(middle.luminance() > darkLum + 0.1f && middle.luminance() < lightLum - 0.1f)
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
        assertEquals(1f, AdaptiveInk.mix(map.luminanceAt(0.5f, 0f)), 1e-4f)
        assertEquals(0f, AdaptiveInk.mix(map.luminanceAt(0.5f, 1f)), 1e-4f)
    }
}

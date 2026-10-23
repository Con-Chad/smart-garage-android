package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderDividerTest {
    private fun hue(c: Color): Float {
        val hi = maxOf(c.red, c.green, c.blue)
        val lo = minOf(c.red, c.green, c.blue)
        val d = hi - lo
        val h = when (hi) {
            c.red -> 60f * (((c.green - c.blue) / d) % 6f)
            c.green -> 60f * ((c.blue - c.red) / d + 2f)
            else -> 60f * ((c.red - c.green) / d + 4f)
        }
        return (h + 360f) % 360f
    }

    @Test
    fun blueTintGivesAnOrangeAccent() {
        val accent = complementaryAccent(Color(0xFF2F6FD6), dark = true)
        val expected = (hue(Color(0xFF2F6FD6)) + HeaderDivider.HUE_OFFSET) % 360f
        assertEquals(expected, hue(accent), 2f)
        assertEquals(HeaderDivider.ALPHA, accent.alpha, 0.01f)
    }

    @Test
    fun neutralTintStillGetsAVisibleAccent() {
        val accent = complementaryAccent(Color(0xFF9FB3C8), dark = true)
        val chroma = maxOf(accent.red, accent.green, accent.blue) - minOf(accent.red, accent.green, accent.blue)
        assertTrue("chroma $chroma", chroma > 0.3f)
    }

    @Test
    fun darkHeaderGetsALighterLineThanLight() {
        val tint = Color(0xFFD0302C)
        val onDark = complementaryAccent(tint, dark = true)
        val onLight = complementaryAccent(tint, dark = false)
        assertTrue(onDark.red + onDark.green + onDark.blue > onLight.red + onLight.green + onLight.blue)
    }
}

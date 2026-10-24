package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlassInkTest {
    @Test
    fun `on the light theme every preset's pill takes the dark ink (D-A70)`() {
        for (argb in AuroraPresets) {
            assertThat(pillInk(LightGlassTokens, Color(argb))).isEqualTo(LightGlassTokens.inkDark)
        }
    }

    @Test
    fun `white, silver and pale tints switch to the dark ink`() {
        for (hex in listOf(0xFFFFFFFF, 0xFFE6E8EC, 0xFFC0C4CC, 0xFFFFF4C2)) {
            assertThat(pillInk(DarkGlassTokens, Color(hex))).isEqualTo(DarkGlassTokens.inkDark)
        }
    }

    @Test
    fun `the pill ink is whichever of the two inks contrasts more with the estimated backdrop (D-A70)`() {
        for (hex in listOf(0xFF000000, 0xFF2E9A55, 0xFFD4AF37, 0xFFE8C62A, 0xFF9FB3C8, 0xFFFFFFFF)) {
            for (tokens in listOf(DarkGlassTokens, LightGlassTokens)) {
                val tint = Color(hex)
                val ink = pillInk(tokens, tint)
                val backdrop = estimatedPillBackdrop(tokens, tint)
                val other = if (ink == tokens.onPhoto) tokens.inkDark else tokens.onPhoto
                assertThat(contrastRatio(ink, backdrop)).isAtLeast(contrastRatio(other, backdrop) * 0.92f)
            }
        }
    }

    @Test
    fun `the dark ink is the light theme's text colour (D-A70)`() {
        for (tokens in listOf(DarkGlassTokens, LightGlassTokens)) {
            assertThat(tokens.inkDark).isEqualTo(Color(0xFF1B2130))
            assertThat(tokens.inkDark).isEqualTo(LightGlassTokens.textPrimary)
            // 4.5:1 from a backdrop of L 0.25 up (D-A49's grey needed 0.50), and on the light theme's page.
            assertThat((0.25f + 0.05f) / (tokens.inkDark.luminance() + 0.05f)).isAtLeast(4.5f)
        }
        assertThat(contrastRatio(LightGlassTokens.inkDark, LightGlassTokens.pageBackground)).isAtLeast(4.5f)
    }

    @Test
    fun `contrast ratio spans 1 to 21`() {
        assertThat(contrastRatio(Color.White, Color.Black)).isWithin(0.01f).of(21f)
        assertThat(contrastRatio(Color.Gray, Color.Gray)).isWithin(0.001f).of(1f)
    }
}

package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlassInkTest {
    @Test
    fun `dark and saturated tints keep white pill labels on dark glass`() {
        for (hex in listOf(0xFF1F6B3A, 0xFF2E9A55, 0xFF1F3A7A, 0xFFD0302C, 0xFF7A3AB8)) {
            assertThat(pillInk(DarkGlassTokens, Color(hex))).isEqualTo(DarkGlassTokens.onPhoto)
        }
    }

    @Test
    fun `white, silver and pale tints switch to the dark grey ink`() {
        for (hex in listOf(0xFFFFFFFF, 0xFFE6E8EC, 0xFFC0C4CC, 0xFFFFF4C2)) {
            assertThat(pillInk(DarkGlassTokens, Color(hex))).isEqualTo(DarkGlassTokens.inkDark)
        }
    }

    @Test
    fun `the chosen ink clears 3 to 1 on the estimated backdrop wherever either ink can`() {
        for (hex in listOf(0xFF000000, 0xFF2E9A55, 0xFFD4AF37, 0xFFE8C62A, 0xFF9FB3C8, 0xFFFFFFFF)) {
            val tint = Color(hex)
            val ink = pillInk(DarkGlassTokens, tint)
            val backdrop = estimatedPillBackdrop(DarkGlassTokens, tint)
            val chosen = contrastRatio(ink, backdrop)
            // D-A49: the dark grey gives up a little on mid-tone estimates (a white car's, L ≈ 0.24): where neither ink
            // reaches 3:1 the chosen one is still within a hair of it.
            val other = contrastRatio(if (ink == DarkGlassTokens.onPhoto) DarkGlassTokens.inkDark else DarkGlassTokens.onPhoto, backdrop)
            if (other >= 3f) assertThat(chosen).isAtLeast(3f) else assertThat(chosen).isAtLeast(2.8f)
        }
    }

    @Test
    fun `the dark ink is a dark grey, not near-black, and still reads on the lightest backdrops (D-A49)`() {
        for (tokens in listOf(DarkGlassTokens, LightGlassTokens)) {
            val ink = tokens.inkDark
            assertThat(ink).isNotEqualTo(tokens.onPale)
            // A grey: neutral-ish, clearly above near-black, clearly below mid-grey.
            assertThat(ink.luminance()).isGreaterThan(0.05f)
            assertThat(ink.luminance()).isLessThan(0.10f)
            // 4.5:1 from the luminance where the ink first turns fully dark, and on the light theme's page.
            assertThat((AdaptiveInk.BAND_HI + 0.05f) / (ink.luminance() + 0.05f)).isAtLeast(4.5f)
        }
        assertThat(contrastRatio(LightGlassTokens.inkDark, LightGlassTokens.pageBackground)).isAtLeast(4.5f)
    }

    @Test
    fun `contrast ratio spans 1 to 21`() {
        assertThat(contrastRatio(Color.White, Color.Black)).isWithin(0.01f).of(21f)
        assertThat(contrastRatio(Color.Gray, Color.Gray)).isWithin(0.001f).of(1f)
    }
}

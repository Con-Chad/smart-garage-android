package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color
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
    fun `white, silver and pale tints switch to the dark ink`() {
        for (hex in listOf(0xFFFFFFFF, 0xFFE6E8EC, 0xFFC0C4CC, 0xFFFFF4C2)) {
            assertThat(pillInk(DarkGlassTokens, Color(hex))).isEqualTo(DarkGlassTokens.onPale)
        }
    }

    @Test
    fun `the chosen ink always clears 3 to 1 on the estimated backdrop`() {
        for (hex in listOf(0xFF000000, 0xFF2E9A55, 0xFFD4AF37, 0xFFE8C62A, 0xFF9FB3C8, 0xFFFFFFFF)) {
            val tint = Color(hex)
            val ink = pillInk(DarkGlassTokens, tint)
            assertThat(contrastRatio(ink, estimatedPillBackdrop(DarkGlassTokens, tint))).isAtLeast(3f)
        }
    }

    @Test
    fun `contrast ratio spans 1 to 21`() {
        assertThat(contrastRatio(Color.White, Color.Black)).isWithin(0.01f).of(21f)
        assertThat(contrastRatio(Color.Gray, Color.Gray)).isWithin(0.001f).of(1f)
    }
}

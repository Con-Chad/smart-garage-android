package io.github.conchad.smartgarage.ui.glass

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DitherTest {
    @Test
    fun `dither is a faint signed wash, balanced around zero`() {
        val px = ditherPixels(128, 0x5EEDL)
        var sum = 0
        for (p in px) {
            val a = p ushr 24
            val rgb = p and 0xFFFFFF
            assertThat(a).isAtMost(DITHER_AMPLITUDE)
            // Only a pure white or pure black wash (or nothing): never a tint.
            assertThat(rgb == 0xFFFFFF || rgb == 0).isTrue()
            sum += if (rgb == 0xFFFFFF) a else -a
        }
        // Signed mean within a tenth of a step: no overall lift or darkening of the page.
        assertThat(kotlin.math.abs(sum.toDouble() / px.size)).isLessThan(0.1)
        // And it is actually noise, not all transparent.
        assertThat(px.count { it ushr 24 > 0 }).isGreaterThan(px.size / 2)
    }
}

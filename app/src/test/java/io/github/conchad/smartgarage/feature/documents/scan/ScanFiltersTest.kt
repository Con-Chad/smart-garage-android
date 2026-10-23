package io.github.conchad.smartgarage.feature.documents.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ScanFiltersTest {
    @Test fun luminanceWeights() {
        assertThat(ScanFilters.luminance(0xFFFFFFFF.toInt())).isEqualTo(255)
        assertThat(ScanFilters.luminance(0xFF000000.toInt())).isEqualTo(0)
        assertThat(ScanFilters.luminance(0xFF00FF00.toInt())).isGreaterThan(ScanFilters.luminance(0xFFFF0000.toInt()))
    }

    @Test fun otsuSplitsTwoClasses() {
        val lum = IntArray(1000) { if (it < 600) 40 else 210 }
        val t = ScanFilters.otsu(lum)
        assertThat(t).isAtLeast(40)
        assertThat(t).isLessThan(210)
    }

    @Test fun adaptiveThresholdKeepsInkUnderAShadow() {
        // Paper fading from 230 to 110 left to right (a shadow), with ink 60 darker than the paper around it.
        val w = 120
        val h = 60
        val lum = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val paper = 230 - x
            if (y in 28..31 && x % 10 < 4) paper - 60 else paper
        }
        val out = ScanFilters.adaptiveThreshold(lum, w, h, window = 21)
        // Ink is black on both the bright and the shadowed side; plain paper is white on both.
        assertThat(out[29 * w + 1]).isEqualTo(0)
        assertThat(out[29 * w + 111]).isEqualTo(0)
        assertThat(out[10 * w + 5]).isEqualTo(255)
        assertThat(out[10 * w + 115]).isEqualTo(255)
        assertThat(out.all { it == 0 || it == 255 }).isTrue()
    }

    @Test fun detectsABrightPageOnADarkTable() {
        val w = 200
        val h = 150
        // A page rotated 10° around the centre, 120 × 90 px.
        val angle = Math.toRadians(10.0)
        val cx = 100.0
        val cy = 75.0
        val lum = IntArray(w * h) { i ->
            val x = i % w - cx
            val y = i / w - cy
            val u = x * cos(angle) + y * sin(angle)
            val v = -x * sin(angle) + y * cos(angle)
            if (kotlin.math.abs(u) <= 60 && kotlin.math.abs(v) <= 45) 220 else 50
        }
        val corners = ScanFilters.detectCorners(lum, w, h)
        assertThat(corners).isNotNull()
        fun expected(u: Double, v: Double) = Pt(((cx + u * cos(angle) - v * sin(angle)) / w).toFloat(), ((cy + u * sin(angle) + v * cos(angle)) / h).toFloat())
        val want = listOf(expected(-60.0, -45.0), expected(60.0, -45.0), expected(60.0, 45.0), expected(-60.0, 45.0))
        corners!!.zip(want).forEach { (got, exp) ->
            assertThat(got.x).isWithin(0.03f).of(exp.x)
            assertThat(got.y).isWithin(0.03f).of(exp.y)
        }
    }

    @Test fun noPageWhenThePhotoIsFlat() {
        assertThat(ScanFilters.detectCorners(IntArray(100 * 80) { 128 }, 100, 80)).isNull()
        // A tiny bright speck isn't a page either.
        val speck = IntArray(100 * 80) { if (it == 4040) 255 else 20 }
        assertThat(ScanFilters.detectCorners(speck, 100, 80)).isNull()
        assertThat(ScanFilters.detectCorners(IntArray(0), 0, 0)).isNull()
    }
}

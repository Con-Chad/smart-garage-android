package io.github.conchad.smartgarage.ui.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageMathTest {
    @Test
    fun `sample size is the largest power of two that keeps the long edge at or above the limit`() {
        assertThat(ImageMath.sampleSize(4000, 3000)).isEqualTo(2) // 2000 ≥ 1600; 1000 would be too small
        assertThat(ImageMath.sampleSize(8000, 6000)).isEqualTo(4)
        assertThat(ImageMath.sampleSize(1600, 1200)).isEqualTo(1)
        assertThat(ImageMath.sampleSize(3200, 1200)).isEqualTo(2)
        assertThat(ImageMath.sampleSize(800, 600)).isEqualTo(1)
    }

    @Test
    fun `target size caps the long edge and keeps the aspect ratio`() {
        assertThat(ImageMath.targetSize(4000, 3000)).isEqualTo(1600 to 1200)
        assertThat(ImageMath.targetSize(3000, 4000)).isEqualTo(1200 to 1600)
        assertThat(ImageMath.targetSize(2000, 1000)).isEqualTo(1600 to 800)
    }

    @Test
    fun `small photos are never upscaled`() {
        assertThat(ImageMath.targetSize(1200, 900)).isEqualTo(1200 to 900)
        assertThat(ImageMath.needsDownscale(1600, 1600)).isFalse()
        assertThat(ImageMath.needsDownscale(1601, 100)).isTrue()
    }

    @Test
    fun `decoder sample then final scale lands on the target`() {
        val (w, h) = 6000 to 4000
        val sample = ImageMath.sampleSize(w, h)
        val decoded = (w / sample) to (h / sample)
        assertThat(decoded.first).isAtLeast(ImageMath.MAX_EDGE_PX)
        assertThat(decoded.first).isLessThan(ImageMath.MAX_EDGE_PX * 2)
        assertThat(ImageMath.targetSize(decoded.first, decoded.second)).isEqualTo(1600 to 1067)
    }
}

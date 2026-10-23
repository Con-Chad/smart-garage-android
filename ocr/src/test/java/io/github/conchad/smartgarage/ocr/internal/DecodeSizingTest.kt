package io.github.conchad.smartgarage.ocr.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DecodeSizingTest {
    private fun sample(w: Int, h: Int) =
        ImageSampling.calculateInSampleSize(w, h, UriImageDecoder.MAX_DIMENSION, UriImageDecoder.MAX_PIXELS)

    private fun assertWithinCaps(w: Int, h: Int) {
        val s = sample(w, h)
        assertThat(Integer.bitCount(s)).isEqualTo(1) // power of two
        assertThat(w / s).isAtMost(UriImageDecoder.MAX_DIMENSION)
        assertThat(h / s).isAtMost(UriImageDecoder.MAX_DIMENSION)
        assertThat((w / s).toLong() * (h / s)).isAtMost(UriImageDecoder.MAX_PIXELS)
        if (s > 1) {
            // the next smaller power of two would break a cap: we never downsample more than needed
            val half = s / 2
            val fits = w / half <= UriImageDecoder.MAX_DIMENSION && h / half <= UriImageDecoder.MAX_DIMENSION &&
                (w / half).toLong() * (h / half) <= UriImageDecoder.MAX_PIXELS
            assertThat(fits).isFalse()
        }
    }

    @Test
    fun exactlyAtDimensionCapIsNotSampled() {
        assertThat(sample(4096, 2000)).isEqualTo(1)
    }

    @Test
    fun oneOverDimensionCapHalves() {
        assertThat(sample(4097, 1000)).isEqualTo(2)
        assertThat(sample(1000, 4097)).isEqualTo(2)
    }

    @Test
    fun pixelCapAppliesBelowDimensionCap() {
        // 4000 x 3001 = 12.004 MP: each side fits but the pixel budget does not.
        assertThat(sample(4000, 3000)).isEqualTo(1)
        assertThat(sample(4000, 3001)).isEqualTo(2)
    }

    @Test
    fun typicalCameraResolutionsStayWithinCaps() {
        listOf(4032 to 3024, 4080 to 3072, 8160 to 6144, 12000 to 9000, 50 to 20000, 1 to 1).forEach { (w, h) ->
            assertWithinCaps(w, h)
            assertWithinCaps(h, w)
        }
    }

    @Test
    fun pixel50MpSensorIsQuartered() {
        assertThat(sample(8160, 6144)).isEqualTo(4)
    }

    @Test
    fun invalidBoundsDoNotSample() {
        assertThat(sample(0, 100)).isEqualTo(1)
        assertThat(sample(-1, -1)).isEqualTo(1)
    }
}

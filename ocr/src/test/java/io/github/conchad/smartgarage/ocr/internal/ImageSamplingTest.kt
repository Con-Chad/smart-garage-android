// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: kotlin.test assertions rewritten for JUnit4 + Truth; PointF replaced by OcrPoint.
package io.github.conchad.smartgarage.ocr.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageSamplingTest {
    @Test
    fun keepsImagesWithinBoundsUnchanged() {
        assertThat(ImageSampling.calculateInSampleSize(1920, 1080, 4096, 12_000_000)).isEqualTo(1)
    }

    @Test
    fun samplesUntilDimensionAndPixelLimitsAreMet() {
        assertThat(ImageSampling.calculateInSampleSize(12000, 9000, 4096, 12_000_000)).isEqualTo(4)
    }

    @Test
    fun usesPowerOfTwoSampleSizes() {
        assertThat(ImageSampling.calculateInSampleSize(16000, 12000, 2048, 4_000_000)).isEqualTo(8)
    }
}

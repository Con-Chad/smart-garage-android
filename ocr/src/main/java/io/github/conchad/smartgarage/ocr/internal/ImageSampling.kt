// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
package io.github.conchad.smartgarage.ocr.internal

internal object ImageSampling {
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int,
        maxPixels: Long
    ): Int {
        if (width <= 0 || height <= 0) {
            return 1
        }

        var sampleSize = 1
        while (true) {
            val sampledWidth = width / sampleSize
            val sampledHeight = height / sampleSize
            val sampledPixels = sampledWidth.toLong() * sampledHeight
            if (
                sampledWidth <= maxDimension &&
                sampledHeight <= maxDimension &&
                sampledPixels <= maxPixels
            ) {
                return sampleSize
            }
            sampleSize *= 2
        }
    }
}

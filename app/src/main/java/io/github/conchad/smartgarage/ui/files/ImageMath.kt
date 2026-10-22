package io.github.conchad.smartgarage.ui.files

/**
 * Sizing for photos taken in the app (D-A23): a receipt shot on a 50 MP phone is far more than the server needs, so
 * the long edge is brought down to [MAX_EDGE_PX] before the file is queued. Pure Kotlin, unit-tested; the bitmap
 * work is in `CameraCapture`.
 */
object ImageMath {
    /** The long edge a captured photo is scaled down to; text on a receipt stays legible at this size. */
    const val MAX_EDGE_PX = 1600

    /** JPEG quality for the downscaled file. */
    const val JPEG_QUALITY = 80

    /**
     * The largest power-of-two `inSampleSize` that still leaves the long edge at or above [maxEdge], so the decoder
     * does the coarse shrinking cheaply and the final scale is at most 2:1.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Int {
        var sample = 1
        val longEdge = maxOf(width, height)
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** The final size: the long edge at most [maxEdge], aspect kept, never upscaled, never below one pixel. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Pair<Int, Int> {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxEdge || longEdge == 0) return width to height
        val scale = maxEdge.toDouble() / longEdge
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** Whether a photo of this size needs the downscale at all. */
    fun needsDownscale(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PX): Boolean = maxOf(width, height) > maxEdge
}

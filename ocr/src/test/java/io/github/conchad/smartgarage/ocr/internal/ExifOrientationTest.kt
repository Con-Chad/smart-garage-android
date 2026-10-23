package io.github.conchad.smartgarage.ocr.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * For each EXIF orientation, where the stored top-left (0,0) and top-right (w,0) corners of a w x h image must
 * land once upright (EXIF 2.3, tag 0x0112).
 */
class ExifOrientationTest {
    private val w = 400
    private val h = 300

    private fun corners(orientation: Int): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val t = ExifOrientation.transformFor(orientation)
        return t.mapPoint(0f, 0f, w, h) to t.mapPoint(w.toFloat(), 0f, w, h)
    }

    private fun upright(orientation: Int): Pair<Int, Int> =
        if (ExifOrientation.transformFor(orientation).swapsDimensions) h to w else w to h

    @Test
    fun normal() {
        assertThat(corners(ExifOrientation.NORMAL)).isEqualTo((0f to 0f) to (400f to 0f))
        assertThat(ExifOrientation.transformFor(ExifOrientation.NORMAL).isIdentity).isTrue()
    }

    @Test
    fun flipHorizontal() {
        assertThat(corners(ExifOrientation.FLIP_HORIZONTAL)).isEqualTo((400f to 0f) to (0f to 0f))
    }

    @Test
    fun rotate180() {
        assertThat(corners(ExifOrientation.ROTATE_180)).isEqualTo((400f to 300f) to (0f to 300f))
    }

    @Test
    fun flipVertical() {
        assertThat(corners(ExifOrientation.FLIP_VERTICAL)).isEqualTo((0f to 300f) to (400f to 300f))
    }

    @Test
    fun transposeMirrorsAcrossMainDiagonal() {
        // upright is 300 x 400; stored row 0 becomes the left column, top to bottom
        assertThat(corners(ExifOrientation.TRANSPOSE)).isEqualTo((0f to 0f) to (0f to 400f))
    }

    @Test
    fun rotate90Clockwise() {
        // stored row 0 becomes the right column, top to bottom
        assertThat(corners(ExifOrientation.ROTATE_90)).isEqualTo((300f to 0f) to (300f to 400f))
    }

    @Test
    fun transverseMirrorsAcrossAntiDiagonal() {
        // stored row 0 becomes the right column, bottom to top
        assertThat(corners(ExifOrientation.TRANSVERSE)).isEqualTo((300f to 400f) to (300f to 0f))
    }

    @Test
    fun rotate270() {
        // stored row 0 becomes the left column, bottom to top
        assertThat(corners(ExifOrientation.ROTATE_270)).isEqualTo((0f to 400f) to (0f to 0f))
    }

    @Test
    fun quarterTurnsSwapDimensions() {
        val swapped = (1..8).filter { ExifOrientation.transformFor(it).swapsDimensions }
        assertThat(swapped).containsExactly(5, 6, 7, 8)
        assertThat(upright(6)).isEqualTo(300 to 400)
    }

    @Test
    fun unknownOrientationIsIdentity() {
        listOf(0, 9, -1).forEach { assertThat(ExifOrientation.transformFor(it).isIdentity).isTrue() }
    }

    @Test
    fun matrixValuesFollowAndroidLayout() {
        assertThat(ExifOrientation.transformFor(ExifOrientation.ROTATE_90).matrixValues().toList())
            .containsExactly(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f).inOrder()
    }
}

package io.github.conchad.smartgarage.ocr.internal

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.ocr.OcrPoint
import io.github.conchad.smartgarage.ocr.TextBox
import org.junit.Test

class DetectionPostProcessorTest {

    private class ProbMap(val width: Int, val height: Int) {
        val values = FloatArray(width * height)

        fun fill(xs: IntRange, ys: IntRange, p: Float) = apply {
            for (y in ys) for (x in xs) values[y * width + x] = p
        }
    }

    private fun run(
        map: ProbMap,
        originalWidth: Int = map.width,
        originalHeight: Int = map.height,
        stopAfterFirst: Boolean = false
    ): List<Pair<TextBox, Float>> {
        val found = mutableListOf<Pair<TextBox, Float>>()
        DetectionPostProcessor.postprocess(map.values, originalWidth, originalHeight, map.width, map.height) { box, score ->
            found += box to score
            stopAfterFirst
        }
        return found
    }

    private fun box(left: Float, top: Float, right: Float = left + 20f, bottom: Float = top + 10f) =
        TextBox(listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom)))

    @Test
    fun findsUnclippedBoxAroundTextBlobScaledToOriginalImage() {
        // Pixels x 10..49, y 20..29: pixel-centre rect 39 x 9, unclip offset = 351 * 1.5 / 96 = 5.484.
        val map = ProbMap(64, 64).fill(10..49, 20..29, 0.9f)

        val results = run(map, originalWidth = 128, originalHeight = 128)

        assertThat(results).hasSize(1)
        val (textBox, score) = results.single()
        assertThat(score).isWithin(1e-5f).of(0.9f)
        val bounds = textBox.boundingRect()
        val offset = 351f * 1.5f / 96f
        assertThat(bounds.left).isWithin(0.05f).of((10f - offset) * 2)
        assertThat(bounds.top).isWithin(0.05f).of((20f - offset) * 2)
        assertThat(bounds.right).isWithin(0.05f).of((49f + offset) * 2)
        assertThat(bounds.bottom).isWithin(0.05f).of((29f + offset) * 2)
        // Clockwise from top-left.
        assertThat(textBox.points.first()).isEqualTo(textBox.points.minBy { it.x + it.y })
    }

    @Test
    fun dropsComponentsWithFewerThanFourPixels() {
        val map = ProbMap(64, 64)
            .fill(10..49, 20..29, 0.9f)
            .fill(55..57, 50..50, 0.9f)

        assertThat(run(map)).hasSize(1)
    }

    @Test
    fun dropsComponentsWhoseExpandedBoxIsBelowMinimumSide() {
        // 2 x 2 pixels: 1 x 1 rect, unclip offset 0.375, so the expanded side is 1.75 < MIN_SIZE.
        val map = ProbMap(32, 32).fill(5..6, 5..6, 0.95f)

        assertThat(run(map)).isEmpty()
    }

    @Test
    fun dropsComponentsScoringBelowBoxThreshold() {
        // Above the 0.3 binarisation threshold but below the 0.6 box threshold.
        val map = ProbMap(64, 64).fill(10..49, 20..29, 0.5f)

        assertThat(run(map)).isEmpty()
    }

    @Test
    fun handlerCanStopScanAfterFirstBox() {
        val map = ProbMap(64, 64)
            .fill(5..40, 5..12, 0.9f)
            .fill(5..30, 40..47, 0.9f)

        assertThat(run(map)).hasSize(2)
        assertThat(run(map, stopAfterFirst = true)).hasSize(1)
    }

    @Test
    fun sortsBoxesIntoRowsWithTenPixelTolerance() {
        val a = box(left = 200f, top = 100f)
        val b = box(left = 50f, top = 110f) // 10 px below a: same row, further left
        val c = box(left = 10f, top = 121f) // 11 px below b and 21 px below a: next row
        val d = box(left = 5f, top = 300f)

        assertThat(DetectionPostProcessor.sortBoxes(listOf(d, c, a, b))).containsExactly(b, a, c, d).inOrder()
    }

    @Test
    fun rowsAreAnchoredOnTheirTopmostBox() {
        val a = box(left = 100f, top = 100f)
        val b = box(left = 50f, top = 108f)
        val c = box(left = 0f, top = 116f) // within 10 of b but not of a: starts a new row

        assertThat(DetectionPostProcessor.sortBoxes(listOf(c, b, a))).containsExactly(b, a, c).inOrder()
    }
}

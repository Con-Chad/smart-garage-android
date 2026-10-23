package io.github.conchad.smartgarage.ocr.internal

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.ocr.OcrPoint
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PolygonGeometryTest {

    private fun assertSameCorners(actual: List<OcrPoint>, expected: List<OcrPoint>, tolerance: Float = 1e-3f) {
        assertThat(actual).hasSize(expected.size)
        for (corner in expected) {
            val nearest = actual.minOf { PolygonGeometry.distance(it, corner) }
            assertThat(nearest).isWithin(tolerance).of(0f)
        }
    }

    private fun rotate(p: OcrPoint, center: OcrPoint, radians: Double): OcrPoint {
        val dx = p.x - center.x
        val dy = p.y - center.y
        return OcrPoint(
            (center.x + dx * cos(radians) - dy * sin(radians)).toFloat(),
            (center.y + dx * sin(radians) + dy * cos(radians)).toFloat()
        )
    }

    @Test
    fun minAreaRectOfAxisAlignedRectangleIsItself() {
        val corners = listOf(OcrPoint(10f, 20f), OcrPoint(110f, 20f), OcrPoint(110f, 60f), OcrPoint(10f, 60f))
        val interior = listOf(OcrPoint(50f, 40f), OcrPoint(60f, 20f), OcrPoint(110f, 30f), OcrPoint(30f, 55f))

        val rect = PolygonGeometry.minimumAreaRectangle(corners + interior)

        assertSameCorners(rect, corners)
        assertThat(PolygonGeometry.calculateBoxArea(rect)).isWithin(1e-2f).of(4000f)
    }

    @Test
    fun minAreaRectRecoversRotatedRectangle() {
        val center = OcrPoint(200f, 200f)
        val angle = Math.toRadians(30.0)
        val corners = listOf(OcrPoint(150f, 180f), OcrPoint(250f, 180f), OcrPoint(250f, 220f), OcrPoint(150f, 220f))
            .map { rotate(it, center, angle) }
        val edgeMidpoints = listOf(OcrPoint(200f, 180f), OcrPoint(250f, 200f), OcrPoint(200f, 220f), OcrPoint(150f, 200f))
            .map { rotate(it, center, angle) }

        val rect = PolygonGeometry.minimumAreaRectangle(corners + edgeMidpoints + center)

        assertSameCorners(rect, corners, tolerance = 1e-2f)
        assertThat(PolygonGeometry.calculateBoxArea(rect)).isWithin(0.5f).of(4000f)
    }

    @Test
    fun minAreaRectOfCollinearPointsIsEmpty() {
        assertThat(PolygonGeometry.minimumAreaRectangle(listOf(OcrPoint(0f, 0f), OcrPoint(1f, 1f), OcrPoint(2f, 2f))))
            .isEmpty()
    }

    @Test
    fun convexHullDropsInteriorAndCollinearPoints() {
        val square = listOf(OcrPoint(0f, 0f), OcrPoint(4f, 0f), OcrPoint(4f, 4f), OcrPoint(0f, 4f))
        val extras = listOf(OcrPoint(2f, 2f), OcrPoint(2f, 0f), OcrPoint(4f, 1f), OcrPoint(1f, 3f))

        assertThat(PolygonGeometry.convexHull(square + extras)).containsExactlyElementsIn(square)
    }

    @Test
    fun unclipGrowsRectangleByOffsetAreaTimesRatioOverPerimeter() {
        // 100 x 20: offset = 2000 * 1.5 / 240 = 12.5, so the box becomes 125 x 45 = 5625.
        val clockwiseOnScreen = listOf(OcrPoint(0f, 0f), OcrPoint(100f, 0f), OcrPoint(100f, 20f), OcrPoint(0f, 20f))

        for (box in listOf(clockwiseOnScreen, clockwiseOnScreen.reversed())) {
            val unclipped = PolygonGeometry.unclipBox(box, unclipRatio = 1.5f)

            assertThat(PolygonGeometry.calculateBoxArea(unclipped) / PolygonGeometry.calculateBoxArea(box))
                .isWithin(1e-4f).of(5625f / 2000f)
            assertSameCorners(
                unclipped,
                listOf(OcrPoint(-12.5f, -12.5f), OcrPoint(112.5f, -12.5f), OcrPoint(112.5f, 32.5f), OcrPoint(-12.5f, 32.5f))
            )
        }
    }

    @Test
    fun unclipOfDegenerateBoxIsEmpty() {
        assertThat(PolygonGeometry.unclipBox(listOf(OcrPoint(0f, 0f), OcrPoint(1f, 1f)), 1.5f)).isEmpty()
        assertThat(PolygonGeometry.unclipBox(List(4) { OcrPoint(3f, 3f) }, 1.5f)).isEmpty()
    }

    @Test
    fun ordersPointsClockwiseFromTopLeft() {
        val topLeft = OcrPoint(10f, 10f)
        val topRight = OcrPoint(90f, 12f)
        val bottomRight = OcrPoint(92f, 40f)
        val bottomLeft = OcrPoint(8f, 38f)

        val ordered = PolygonGeometry.orderPointsClockwise(listOf(bottomRight, topLeft, bottomLeft, topRight))

        assertThat(ordered).containsExactly(topLeft, topRight, bottomRight, bottomLeft).inOrder()
    }

    @Test
    fun ordersRotatedQuadStartingAtSmallestXPlusY() {
        val center = OcrPoint(100f, 100f)
        val angle = Math.toRadians(20.0)
        val tl = rotate(OcrPoint(50f, 80f), center, angle)
        val tr = rotate(OcrPoint(150f, 80f), center, angle)
        val br = rotate(OcrPoint(150f, 120f), center, angle)
        val bl = rotate(OcrPoint(50f, 120f), center, angle)

        assertThat(PolygonGeometry.orderPointsClockwise(listOf(br, bl, tr, tl)))
            .containsExactly(tl, tr, br, bl).inOrder()
    }

    @Test
    fun orderingLeavesNonQuadsUntouched() {
        val triangle = listOf(OcrPoint(5f, 5f), OcrPoint(0f, 0f), OcrPoint(9f, 0f))

        assertThat(PolygonGeometry.orderPointsClockwise(triangle)).isEqualTo(triangle)
    }

    @Test
    fun connectedComponentsAreEightConnected() {
        val map = arrayOf(
            booleanArrayOf(true, false, false, false, false),
            booleanArrayOf(false, true, false, false, true),
            booleanArrayOf(false, false, true, false, true),
            booleanArrayOf(false, false, false, false, false)
        )

        val components = PolygonGeometry.extractConnectedComponents(map)

        assertThat(components.map { it.size }).containsExactly(3, 2)
        assertThat(components.first { it.size == 3 })
            .containsExactly(OcrPoint(0f, 0f), OcrPoint(1f, 1f), OcrPoint(2f, 2f))
    }

    @Test
    fun boxScoreAveragesProbabilitiesInsideQuad() {
        val probMap = Array(10) { y -> FloatArray(10) { x -> if (x in 2..5 && y in 2..5) 0.8f else 0.1f } }
        val quad = listOf(OcrPoint(2f, 2f), OcrPoint(6f, 2f), OcrPoint(6f, 6f), OcrPoint(2f, 6f))

        assertThat(PolygonGeometry.calculateBoxScore(probMap, quad)).isWithin(1e-6f).of(0.8f)
    }

    @Test
    fun clipsToImageBounds() {
        val clipped = PolygonGeometry.clipBoxToImageBounds(listOf(OcrPoint(-3f, 5f), OcrPoint(70f, 40f)), 64, 32)

        assertThat(clipped).containsExactly(OcrPoint(0f, 5f), OcrPoint(63f, 31f)).inOrder()
    }
}

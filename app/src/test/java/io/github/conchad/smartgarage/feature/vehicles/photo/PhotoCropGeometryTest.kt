package io.github.conchad.smartgarage.feature.vehicles.photo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoCropGeometryTest {
    private val aspect = 16f / 9f
    private val tol = 0.01f

    private fun assertRect(actual: CropRect, left: Float, top: Float, right: Float, bottom: Float) {
        assertThat(actual.left).isWithin(tol).of(left)
        assertThat(actual.top).isWithin(tol).of(top)
        assertThat(actual.right).isWithin(tol).of(right)
        assertThat(actual.bottom).isWithin(tol).of(bottom)
    }

    @Test fun turnedSizeSwapsOnOddTurns() {
        assertThat(PhotoCropGeometry.turnedSize(400, 300, 0)).isEqualTo(400 to 300)
        assertThat(PhotoCropGeometry.turnedSize(400, 300, 1)).isEqualTo(300 to 400)
        assertThat(PhotoCropGeometry.turnedSize(400, 300, 2)).isEqualTo(400 to 300)
        assertThat(PhotoCropGeometry.turnedSize(400, 300, 7)).isEqualTo(300 to 400)
    }

    @Test fun startingFrameIsTheLargestCentredAspectRect() {
        // 1600 × 1200 landscape: full width, 900 tall, centred.
        assertRect(PhotoCropGeometry.cropRect(CropState(), 1600f, 1200f, aspect), 0f, 150f, 1600f, 1050f)
        // A panorama 4000 × 1000: full height, 1777.8 wide, centred.
        assertRect(PhotoCropGeometry.cropRect(CropState(), 4000f, 1000f, aspect), 1111.11f, 0f, 2888.89f, 1000f)
    }

    @Test fun zoomShrinksTheFrameAroundItsCentre() {
        val rect = PhotoCropGeometry.cropRect(CropState(zoom = 2f), 1600f, 1200f, aspect)
        assertRect(rect, 400f, 375f, 1200f, 825f)
    }

    @Test fun zoomAndCentreAreClampedSoTheFrameStaysOnThePhoto() {
        val clamped = PhotoCropGeometry.clamp(CropState(zoom = 50f, centerX = -1f, centerY = 2f), 1600f, 1200f, aspect)
        assertThat(clamped.zoom).isEqualTo(PhotoCropGeometry.MAX_ZOOM)
        val rect = PhotoCropGeometry.cropRect(clamped, 1600f, 1200f, aspect)
        assertThat(rect.left).isWithin(tol).of(0f)
        assertThat(rect.bottom).isWithin(tol).of(1200f)
        // Below zoom 1 is not allowed: the frame never grows past the photo.
        assertThat(PhotoCropGeometry.clamp(CropState(zoom = 0.2f), 1600f, 1200f, aspect).zoom).isEqualTo(1f)
    }

    @Test fun dragMovesThePhotoWithTheFinger() {
        // Frame 800 px wide on a 1600 px wide crop at zoom 2: 1 screen px = 1 image px. Dragging the photo 100 px
        // left moves the crop 100 px right.
        val start = CropState(zoom = 2f)
        val moved = PhotoCropGeometry.transform(start, 1600f, 1200f, aspect, 800f, 0f, 0f, panX = -100f, panY = 0f, zoomBy = 1f)
        assertRect(PhotoCropGeometry.cropRect(moved, 1600f, 1200f, aspect), 500f, 375f, 1300f, 825f)
    }

    @Test fun pinchKeepsThePointUnderTheFingers() {
        // Zoom ×2 about a point 200 screen px right of the frame centre. At zoom 1 (frame 800 px for a 1600 px crop,
        // scale 0.5) that point is image x = 800 + 400 = 1200; after, at scale 1, it must still be 200 px right of
        // centre, so the new centre is x = 1000.
        val zoomed = PhotoCropGeometry.transform(CropState(), 1600f, 1200f, aspect, 800f, 200f, 0f, 0f, 0f, zoomBy = 2f)
        assertThat(zoomed.zoom).isWithin(tol).of(2f)
        assertThat(zoomed.centerX * 1600f).isWithin(tol).of(1000f)
        assertThat(zoomed.centerY).isWithin(tol).of(0.5f)
    }

    @Test fun rotateTurnsAQuarterAndStartsTheFrameOver() {
        val turned = PhotoCropGeometry.rotate(CropState(turns = 3, zoom = 3f, centerX = 0.2f, centerY = 0.8f))
        assertThat(turned).isEqualTo(CropState(turns = 0))
        assertThat(PhotoCropGeometry.rotate(CropState()).turns).isEqualTo(1)
    }

    @Test fun cropRectMapsBackToTheSourceAfterAQuarterTurn() {
        // 400 × 300 source, turned once: 300 × 400. Frame: full width 300, 168.75 tall, centred.
        val state = CropState(turns = 1)
        val turned = PhotoCropGeometry.cropRect(state, 300f, 400f, aspect)
        assertRect(turned, 0f, 115.625f, 300f, 284.375f)
        // Turned (x', y') came from source (y', 300 − x'): a horizontal band of the turned photo is a vertical band
        // of the source.
        assertRect(PhotoCropGeometry.toSource(turned, 400, 300, 1), 115.625f, 0f, 284.375f, 300f)
    }

    @Test fun cropRectMapsBackAfterTurnsZoomAndPan() {
        // Zoomed into the turned photo's top-left corner.
        val state = PhotoCropGeometry.clamp(CropState(turns = 1, zoom = 3f, centerX = 0f, centerY = 0f), 300f, 400f, aspect)
        val turned = PhotoCropGeometry.cropRect(state, 300f, 400f, aspect)
        assertRect(turned, 0f, 0f, 100f, 56.25f)
        // The turned top-left is the source's bottom-left.
        assertRect(PhotoCropGeometry.toSource(turned, 400, 300, 1), 0f, 200f, 56.25f, 300f)
        // Half a turn: the turned top-left is the source's bottom-right.
        val half = PhotoCropGeometry.cropRect(CropState(turns = 2, zoom = 2f, centerX = 0f, centerY = 0f), 400f, 300f, aspect)
        assertRect(PhotoCropGeometry.toSource(half, 400, 300, 2), 400f - half.right, 300f - half.bottom, 400f, 300f)
        // Three turns: the turned top-left is the source's top-right.
        val three = PhotoCropGeometry.cropRect(state.copy(turns = 3), 300f, 400f, aspect)
        assertRect(PhotoCropGeometry.toSource(three, 400, 300, 3), 400f - 56.25f, 0f, 400f, 100f)
    }

    @Test fun noTurnLeavesTheRectAlone() {
        val rect = CropRect(10f, 20f, 30f, 40f)
        assertThat(PhotoCropGeometry.toSource(rect, 400, 300, 0)).isEqualTo(rect)
    }

    @Test fun pixelBoundsStayInsideTheSource() {
        assertThat(PhotoCropGeometry.pixelBounds(CropRect(-2f, 115.6f, 300.4f, 284.4f), 300, 400).toList()).isEqualTo(listOf(0, 116, 300, 168))
        assertThat(PhotoCropGeometry.pixelBounds(CropRect(299.9f, 399.9f, 300f, 400f), 300, 400).toList()).isEqualTo(listOf(299, 399, 1, 1))
    }
}

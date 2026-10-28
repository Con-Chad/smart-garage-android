package io.github.conchad.smartgarage.feature.vehicles.photo

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the crop sits on a vehicle photo (D-A82): the image turned clockwise by [turns] quarter turns, then a crop
 * frame of the card's aspect, [zoom] times smaller than the largest such frame that fits, centred at
 * ([centerX], [centerY]) as fractions of the turned image. Kept in image terms rather than screen pixels, so a
 * device rotation (a different frame size) shows the same crop.
 */
data class CropState(val turns: Int = 0, val zoom: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f)

/** A rectangle in image pixels. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * The pure maths behind the vehicle photo crop screen (D-A82), unit-tested; the bitmaps are in [PhotoCropImaging].
 * "Turned" sizes and rects are in the image after its quarter turns; "source" ones in the upright decoded photo.
 */
object PhotoCropGeometry {
    /** Pinch-zoom stops at this many times the starting (whole-width or whole-height) frame. */
    const val MAX_ZOOM = 5f

    /** The photo is decoded with its long edge at most this, so a 50 MP picture can't run the app out of memory. */
    const val MAX_EDGE = 2048

    /** JPEG quality of the cropped photo that is uploaded. */
    const val JPEG_QUALITY = 90

    /** One accessibility zoom step (TalkBack's "Zoom in" / "Zoom out" custom actions). */
    const val ZOOM_STEP = 1.25f

    /** The image size after [turns] clockwise quarter turns. */
    fun turnedSize(width: Int, height: Int, turns: Int): Pair<Int, Int> = if (normalTurns(turns) % 2 == 0) width to height else height to width

    fun normalTurns(turns: Int): Int = ((turns % 4) + 4) % 4

    /** The crop frame's size in turned-image pixels: the largest [aspect] rect that fits, shrunk by [zoom]. */
    fun cropSize(imageWidth: Float, imageHeight: Float, aspect: Float, zoom: Float): Pair<Float, Float> {
        val width = min(imageWidth, imageHeight * aspect) / zoom
        return width to width / aspect
    }

    /** [state] with the zoom in 1..[MAX_ZOOM] and the centre moved so the frame stays wholly on the image. */
    fun clamp(state: CropState, imageWidth: Float, imageHeight: Float, aspect: Float): CropState {
        val zoom = state.zoom.coerceIn(1f, MAX_ZOOM)
        val (cropWidth, cropHeight) = cropSize(imageWidth, imageHeight, aspect, zoom)
        val halfX = (cropWidth / 2f / imageWidth).coerceAtMost(0.5f)
        val halfY = (cropHeight / 2f / imageHeight).coerceAtMost(0.5f)
        return state.copy(zoom = zoom, centerX = state.centerX.coerceIn(halfX, 1f - halfX), centerY = state.centerY.coerceIn(halfY, 1f - halfY))
    }

    /** The crop in turned-image pixels. */
    fun cropRect(state: CropState, imageWidth: Float, imageHeight: Float, aspect: Float): CropRect {
        val s = clamp(state, imageWidth, imageHeight, aspect)
        val (cropWidth, cropHeight) = cropSize(imageWidth, imageHeight, aspect, s.zoom)
        val cx = s.centerX * imageWidth
        val cy = s.centerY * imageHeight
        return CropRect(cx - cropWidth / 2f, cy - cropHeight / 2f, cx + cropWidth / 2f, cy + cropHeight / 2f)
    }

    /** Screen pixels per turned-image pixel when the crop frame is [frameWidth] wide on screen. */
    fun displayScale(state: CropState, imageWidth: Float, imageHeight: Float, aspect: Float, frameWidth: Float): Float =
        frameWidth / cropSize(imageWidth, imageHeight, aspect, state.zoom.coerceIn(1f, MAX_ZOOM)).first

    /**
     * One step of a pinch / drag: [zoomBy] about the fingers' centroid ([centroidX], [centroidY], screen pixels from
     * the frame's centre) so the point under them stays put, then [panX], [panY] screen pixels of drag (the photo
     * follows the finger, so the crop moves the other way). Clamped.
     */
    fun transform(
        state: CropState,
        imageWidth: Float,
        imageHeight: Float,
        aspect: Float,
        frameWidth: Float,
        centroidX: Float,
        centroidY: Float,
        panX: Float,
        panY: Float,
        zoomBy: Float,
    ): CropState {
        val start = clamp(state, imageWidth, imageHeight, aspect)
        val scale = displayScale(start, imageWidth, imageHeight, aspect, frameWidth)
        // The image point under the centroid.
        val qx = start.centerX * imageWidth + centroidX / scale
        val qy = start.centerY * imageHeight + centroidY / scale
        val zoomed = start.copy(zoom = (start.zoom * zoomBy).coerceIn(1f, MAX_ZOOM))
        val newScale = displayScale(zoomed, imageWidth, imageHeight, aspect, frameWidth)
        val cx = (qx - centroidX / newScale - panX / newScale) / imageWidth
        val cy = (qy - centroidY / newScale - panY / newScale) / imageHeight
        return clamp(zoomed.copy(centerX = cx, centerY = cy), imageWidth, imageHeight, aspect)
    }

    /** Rotate 90° clockwise: a quarter turn more, and the frame starts over (whole width / height, centred). */
    fun rotate(state: CropState): CropState = CropState(turns = normalTurns(state.turns + 1))

    /**
     * [rect] in the image turned by [turns] mapped back onto the upright source ([sourceWidth] × [sourceHeight]).
     * One clockwise turn takes source (x, y) to (H − y, x), so each step back takes (x', y') in a w' × h' image to
     * (y', w' − x').
     */
    fun toSource(rect: CropRect, sourceWidth: Int, sourceHeight: Int, turns: Int): CropRect {
        var (w, h) = turnedSize(sourceWidth, sourceHeight, turns).let { it.first.toFloat() to it.second.toFloat() }
        var ax = rect.left
        var ay = rect.top
        var bx = rect.right
        var by = rect.bottom
        repeat(normalTurns(turns)) {
            val nax = ay
            val nay = w - ax
            val nbx = by
            val nby = w - bx
            ax = nax; ay = nay; bx = nbx; by = nby
            val t = w; w = h; h = t
        }
        return CropRect(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
    }

    /** [rect] as whole source pixels, at least 1 × 1 and inside the source. Left, top, width, height. */
    fun pixelBounds(rect: CropRect, sourceWidth: Int, sourceHeight: Int): IntArray {
        val left = rect.left.roundToInt().coerceIn(0, sourceWidth - 1)
        val top = rect.top.roundToInt().coerceIn(0, sourceHeight - 1)
        val right = rect.right.roundToInt().coerceIn(left + 1, sourceWidth)
        val bottom = rect.bottom.roundToInt().coerceIn(top + 1, sourceHeight)
        return intArrayOf(left, top, right - left, bottom - top)
    }
}

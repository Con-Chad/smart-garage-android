package io.github.conchad.smartgarage.feature.textscan

import io.github.conchad.smartgarage.ocr.BoundingRect
import io.github.conchad.smartgarage.ocr.OcrPoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The "Scan text" tool's geometry and text assembly (D-A64): plain functions on plain values so they run in JVM
 * unit tests. Image coordinates are the OCR bitmap's pixels (`OcrImageResult.width` × `height`); view coordinates
 * are the overlay's pixels.
 */

/** A rectangle in view pixels. */
data class ViewRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    /** Straight-line distance from (x, y) to the nearest point of this rectangle; 0 inside it. */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = max(max(left - x, x - right), 0f)
        val dy = max(max(top - y, y - bottom), 0f)
        return hypot(dx, dy)
    }

    /** This rectangle grown about its centre so each side is at least [size] long. */
    fun atLeast(size: Float): ViewRect {
        val growX = max(0f, size - width) / 2f
        val growY = max(0f, size - height) / 2f
        return ViewRect(left - growX, top - growY, right + growX, bottom + growY)
    }
}

/**
 * How an image of the OCR's size sits in the overlay: scaled uniformly to fit inside it and centred, the rest
 * letterboxed (what `ContentScale.Fit` with `Alignment.Center` does).
 */
data class ImageFit(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun x(imageX: Float): Float = offsetX + imageX * scale
    fun y(imageY: Float): Float = offsetY + imageY * scale

    fun toView(rect: BoundingRect): ViewRect = ViewRect(x(rect.left), y(rect.top), x(rect.right), y(rect.bottom))

    companion object {
        /** Null when either size is empty (nothing to draw). */
        fun fit(imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float): ImageFit? {
            if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0f || viewHeight <= 0f) return null
            val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
            return ImageFit(scale, (viewWidth - imageWidth * scale) / 2f, (viewHeight - imageHeight * scale) / 2f)
        }
    }
}

/**
 * The box a tap at (x, y) means, or null. Every box answers within at least [minTarget] × [minTarget] around its
 * centre (a touch target for a one-word line); where those overlap, a box the tap is actually inside wins, then the
 * nearest box, then the smaller one (a word inside a bigger block).
 */
fun hitTest(boxes: List<ViewRect>, x: Float, y: Float, minTarget: Float): Int? =
    boxes.indices
        .filter { boxes[it].atLeast(minTarget).contains(x, y) }
        .minWithOrNull(compareBy({ boxes[it].distanceTo(x, y) }, { boxes[it].width * boxes[it].height }))

/**
 * The boxes as rows in reading order: top to bottom, and left to right within a row. A box joins the row above it
 * when its centre line lies within the row's (mean) band and the row's centre line within the box, so a total
 * printed at the far right of its label stays on the label's line even when the photo is slightly skewed, and one
 * tall box doesn't swallow the lines beside it. For that second test a box shorter than the median line (a lone
 * "1" in a quantity column) counts as a median line about its own centre, so it isn't dropped onto a row of its
 * own when skew puts it a little low (D-A67). Indices refer to [rects].
 */
fun readingOrder(rects: List<BoundingRect>): List<List<Int>> {
    val lineHeight = rects.map { it.height }.sorted().let { if (it.isEmpty()) 0f else it[it.size / 2] }
    val byCentre = rects.indices.sortedWith(compareBy({ centreY(rects[it]) }, { rects[it].left }))
    val rows = mutableListOf<MutableList<Int>>()
    var topSum = 0f
    var bottomSum = 0f
    for (i in byCentre) {
        val r = rects[i]
        val row = rows.lastOrNull()
        if (row != null) {
            val bandTop = topSum / row.size
            val bandBottom = bottomSum / row.size
            val bandCentre = (bandTop + bandBottom) / 2f
            val reach = max(r.height, lineHeight) / 2f
            if (centreY(r) in bandTop..bandBottom && bandCentre in centreY(r) - reach..centreY(r) + reach) {
                row += i
                topSum += r.top
                bottomSum += r.bottom
                continue
            }
        }
        rows += mutableListOf(i)
        topSum = r.top
        bottomSum = r.bottom
    }
    return rows.map { row -> row.sortedBy { rects[it].left } }
}

private fun centreY(r: BoundingRect): Float = (r.top + r.bottom) / 2f

/**
 * The boxes' bounds as if the photo were turned level (D-A67), for [readingOrder] and the receipt parser's row
 * grouping. On a receipt the amount sits at the far end of its label's row, so even 1-2 degrees of tilt moves it by
 * more than half a line and pairs "TOTAL" with the next row's amount. The tilt is the median slope of the line boxes'
 * top edges (a box at least twice as wide as tall: its top edge runs along the text; [corners] are clockwise from
 * the top-left, as the engine gives them); each box's corners are rotated back by it about the origin, then bounded.
 * Below a third of a degree, or with no line boxes, [bounds] come back as they are. Only for ordering: the screen
 * still draws the real boxes.
 */
fun levelledBounds(corners: List<List<OcrPoint>>, bounds: List<BoundingRect>): List<BoundingRect> {
    val angle = tilt(corners)
    if (abs(angle) < MIN_TILT) return bounds
    val c = cos(angle)
    val s = sin(angle)
    return bounds.indices.map { i ->
        val points = corners.getOrNull(i)?.takeIf { it.size == 4 }
            ?: bounds[i].let { listOf(OcrPoint(it.left, it.top), OcrPoint(it.right, it.top), OcrPoint(it.right, it.bottom), OcrPoint(it.left, it.bottom)) }
        val xs = points.map { it.x * c + it.y * s }
        val ys = points.map { it.y * c - it.x * s }
        BoundingRect(xs.min(), ys.min(), xs.max(), ys.max())
    }
}

/** The median top-edge slope of the line boxes, in radians (positive: lines run down to the right); 0 if none. */
internal fun tilt(corners: List<List<OcrPoint>>): Float {
    val slopes = corners.mapNotNull { q ->
        if (q.size != 4) return@mapNotNull null
        val dx = q[1].x - q[0].x
        val dy = q[1].y - q[0].y
        val side = hypot(q[2].x - q[1].x, q[2].y - q[1].y)
        if (dx <= 0f || hypot(dx, dy) < 2f * side) null else atan2(dy, dx)
    }.filter { abs(it) <= MAX_TILT }.sorted()
    return if (slopes.isEmpty()) 0f else slopes[slopes.size / 2]
}

private const val MIN_TILT = 0.006f // ~0.3 degrees
private const val MAX_TILT = 0.52f // ~30 degrees: steeper than that the corner order isn't trustworthy

/**
 * The text of [selected] boxes in reading order ([rows] from [readingOrder]): a row's boxes joined by a space, rows
 * by a line break. With nothing selected, all of it.
 */
fun assembleText(texts: List<String>, rows: List<List<Int>>, selected: Set<Int> = emptySet()): String =
    rows.mapNotNull { row ->
        row.filter { selected.isEmpty() || it in selected }
            .map { texts[it].trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
    }.joinToString("\n")

/** Which of [count] boxes are picked. */
data class TextSelection(val count: Int, val selected: Set<Int> = emptySet()) {
    val none: Boolean get() = selected.isEmpty()
    val all: Boolean get() = count > 0 && selected.size == count

    fun toggle(index: Int): TextSelection = when {
        index !in 0 until count -> this
        index in selected -> copy(selected = selected - index)
        else -> copy(selected = selected + index)
    }

    fun selectAll(): TextSelection = copy(selected = (0 until count).toSet())
    fun clear(): TextSelection = copy(selected = emptySet())

    companion object {
        /** A selection restored from saved state; indices that no longer exist are dropped. */
        fun restore(count: Int, saved: IntArray?): TextSelection =
            TextSelection(count, saved?.filter { it in 0 until count }?.toSet().orEmpty())
    }
}

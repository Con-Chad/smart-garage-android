package io.github.conchad.smartgarage.feature.documents.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A point; in a [ScanPage] it is normalized (0..1 of the source photo's width and height). */
data class Pt(val x: Float, val y: Float)

/**
 * The geometry of the document scanner (D-A59): corner order, the output page size, rotation, the PDF page box and
 * file naming. Pure Kotlin, unit-tested; the pixel work (`Matrix.setPolyToPoly`) is in `ScanImaging`.
 */
object ScanGeometry {
    /** The long edge a scan source photo is kept at; a page's output is at most [MAX_PAGE_EDGE]. */
    const val SOURCE_EDGE = 2400
    const val MAX_PAGE_EDGE = 2200

    /** The long edge of the in-app previews (crop screen, page thumbnails). */
    const val PREVIEW_EDGE = 1200

    /** More pages than this can't be added to one scan. */
    const val MAX_PAGES = 20

    /** A PDF page's short edge in points: an A4 page's width (210 mm). */
    const val PDF_SHORT_EDGE_PT = 595

    /** The corners a fresh page starts with when nothing was detected: a little inside the photo's edges. */
    fun insetCorners(inset: Float = 0.08f): List<Pt> =
        listOf(Pt(inset, inset), Pt(1 - inset, inset), Pt(1 - inset, 1 - inset), Pt(inset, 1 - inset))

    /** The whole photo. */
    val FULL: List<Pt> = insetCorners(0f)

    fun clamp(p: Pt): Pt = Pt(p.x.coerceIn(0f, 1f), p.y.coerceIn(0f, 1f))

    /**
     * Top-left, top-right, bottom-right, bottom-left, whatever order the user dragged the handles into: sorted by
     * angle around the centroid (clockwise on screen, y down), then started at the corner nearest the top-left.
     */
    fun orderCorners(points: List<Pt>): List<Pt> {
        require(points.size == 4) { "a page has four corners" }
        val cx = points.sumOf { it.x.toDouble() } / 4
        val cy = points.sumOf { it.y.toDouble() } / 4
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }

    /**
     * Ordered corners for an output turned [quarterTurns] × 90° clockwise: the corner that lands top-left is the
     * one that was bottom-left, and so on round. Any whole number of turns (negative too).
     */
    fun rotate(ordered: List<Pt>, quarterTurns: Int): List<Pt> {
        val k = Math.floorMod(quarterTurns, 4)
        return List(4) { ordered[Math.floorMod(it - k, 4)] }
    }

    /** Corners in pixels of a [width] × [height] photo. */
    fun toPixels(points: List<Pt>, width: Int, height: Int): List<Pt> = points.map { Pt(it.x * width, it.y * height) }

    private fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

    /**
     * The flattened page's size for ordered pixel corners: the longer of each pair of opposite edges, then scaled so
     * the long edge is at most [maxEdge]. Never below one pixel.
     */
    fun outputSize(orderedPx: List<Pt>, maxEdge: Int = MAX_PAGE_EDGE): Pair<Int, Int> {
        val (tl, tr, br, bl) = orderedPx
        val w = maxOf(dist(tl, tr), dist(bl, br)).toDouble()
        val h = maxOf(dist(tl, bl), dist(tr, br)).toDouble()
        val long = maxOf(w, h)
        val scale = if (long > maxEdge) maxEdge / long else 1.0
        return maxOf(1, (w * scale).roundToInt()) to maxOf(1, (h * scale).roundToInt())
    }

    /** `setPolyToPoly`'s two arrays: the ordered corners and the output rectangle's corners, as x, y pairs. */
    fun polyToPoly(orderedPx: List<Pt>, width: Int, height: Int): Pair<FloatArray, FloatArray> {
        val src = FloatArray(8).also { a -> orderedPx.forEachIndexed { i, p -> a[2 * i] = p.x; a[2 * i + 1] = p.y } }
        val w = width.toFloat()
        val h = height.toFloat()
        return src to floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
    }

    /** Shoelace area of a polygon (absolute). */
    fun area(points: List<Pt>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2
    }

    /** True when the ordered corners make a convex quadrilateral (all turns the same way, none degenerate). */
    fun isConvex(ordered: List<Pt>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = ordered[i]
            val b = ordered[(i + 1) % 4]
            val c = ordered[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Whether normalized corners describe a page worth flattening: convex and at least [minArea] of the photo. */
    fun isUsable(points: List<Pt>, minArea: Float = 0.02f): Boolean {
        val ordered = orderCorners(points)
        return isConvex(ordered) && area(ordered) >= minArea
    }

    /** The PDF page box in points for a page image of [widthPx] × [heightPx]: short edge A4-wide, aspect kept. */
    fun pdfPageSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        require(widthPx > 0 && heightPx > 0)
        return if (widthPx <= heightPx) {
            PDF_SHORT_EDGE_PT to maxOf(1, (PDF_SHORT_EDGE_PT.toDouble() * heightPx / widthPx).roundToInt())
        } else {
            maxOf(1, (PDF_SHORT_EDGE_PT.toDouble() * widthPx / heightPx).roundToInt()) to PDF_SHORT_EDGE_PT
        }
    }

    /** One page is sent as a JPEG, several as one PDF. */
    fun isPdf(pageCount: Int): Boolean = pageCount > 1

    /**
     * The uploaded file's name from the user's document name: characters a file system or header dislikes become
     * "-", runs collapse, length capped; "scan" when nothing is left. The extension follows [isPdf].
     */
    fun fileName(name: String, pageCount: Int): String {
        val base = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "-")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '-')
            .take(80)
            .trimEnd(' ', '.', '-')
            .ifEmpty { "scan" }
        return base + if (isPdf(pageCount)) ".pdf" else ".jpg"
    }
}

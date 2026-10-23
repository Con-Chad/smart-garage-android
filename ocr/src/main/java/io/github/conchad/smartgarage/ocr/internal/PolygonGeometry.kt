// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: the pure polygon helpers formerly private to TextDetector and the point helpers
// from ImageUtils are gathered here, operating on OcrPoint instead of android.graphics.PointF, so they can
// be unit-tested on the JVM. The algorithms and constants are unchanged; the unused ImageUtils helpers
// expandBox and isValidBox were not ported.
package io.github.conchad.smartgarage.ocr.internal

import io.github.conchad.smartgarage.ocr.OcrPoint
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal object PolygonGeometry {
    private const val EPSILON = 1e-6f

    /** 8-connected components of the `true` cells, each as its pixel coordinates. */
    fun extractConnectedComponents(binaryMap: Array<BooleanArray>): List<List<OcrPoint>> {
        val height = binaryMap.size
        val width = if (height > 0) binaryMap[0].size else 0
        val visited = Array(height) { BooleanArray(width) }
        val components = mutableListOf<List<OcrPoint>>()
        val stack = ArrayDeque<Pair<Int, Int>>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!binaryMap[y][x] || visited[y][x]) continue

                val points = mutableListOf<OcrPoint>()
                stack.clear()
                stack.add(Pair(x, y))
                visited[y][x] = true

                while (stack.isNotEmpty()) {
                    val (cx, cy) = stack.removeLast()
                    points.add(OcrPoint(cx.toFloat(), cy.toFloat()))

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (nx in 0 until width && ny in 0 until height &&
                                binaryMap[ny][nx] && !visited[ny][nx]
                            ) {
                                visited[ny][nx] = true
                                stack.add(Pair(nx, ny))
                            }
                        }
                    }
                }

                components.add(points)
            }
        }

        return components
    }

    /** Mean probability of the cells whose centres fall inside [polygon]. */
    fun calculateBoxScore(probMap: Array<FloatArray>, polygon: List<OcrPoint>): Float {
        if (polygon.isEmpty()) return 0f

        var minX = floor(polygon.minOf { it.x.toDouble() }).toInt()
        var maxX = ceil(polygon.maxOf { it.x.toDouble() }).toInt()
        var minY = floor(polygon.minOf { it.y.toDouble() }).toInt()
        var maxY = ceil(polygon.maxOf { it.y.toDouble() }).toInt()

        minX = min(max(minX, 0), probMap[0].size - 1)
        maxX = min(max(maxX, 0), probMap[0].size - 1)
        minY = min(max(minY, 0), probMap.size - 1)
        maxY = min(max(maxY, 0), probMap.size - 1)

        if (maxX < minX || maxY < minY) return 0f

        var sum = 0f
        var count = 0

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (isPointInsideQuad(x + 0.5f, y + 0.5f, polygon)) {
                    sum += probMap[y][x]
                    count++
                }
            }
        }

        return if (count > 0) sum / count else 0f
    }

    /** Rotating-calipers minimum-area bounding rectangle (four corners), or empty for degenerate input. */
    fun minimumAreaRectangle(points: List<OcrPoint>, pointsAreConvex: Boolean = false): List<OcrPoint> {
        val hull = if (pointsAreConvex) points else convexHull(points)
        if (hull.size < 3) return emptyList()

        var bestRect: List<OcrPoint> = emptyList()
        var minArea = Float.MAX_VALUE

        for (i in hull.indices) {
            val p1 = hull[i]
            val p2 = hull[(i + 1) % hull.size]
            val edgeVec = normalizeVector(p1, p2) ?: continue
            val normal = OcrPoint(-edgeVec.y, edgeVec.x)

            var minProj = Float.MAX_VALUE
            var maxProj = -Float.MAX_VALUE
            var minOrth = Float.MAX_VALUE
            var maxOrth = -Float.MAX_VALUE

            for (pt in hull) {
                val relX = pt.x - p1.x
                val relY = pt.y - p1.y
                val projection = relX * edgeVec.x + relY * edgeVec.y
                val orthProjection = relX * normal.x + relY * normal.y

                if (projection < minProj) minProj = projection
                if (projection > maxProj) maxProj = projection
                if (orthProjection < minOrth) minOrth = orthProjection
                if (orthProjection > maxOrth) maxOrth = orthProjection
            }

            val width = maxProj - minProj
            val height = maxOrth - minOrth
            val area = width * height

            if (area < minArea && width > 1e-3f && height > 1e-3f) {
                minArea = area

                val corner0 = OcrPoint(
                    p1.x + edgeVec.x * minProj + normal.x * minOrth,
                    p1.y + edgeVec.y * minProj + normal.y * minOrth
                )
                val corner1 = OcrPoint(
                    p1.x + edgeVec.x * maxProj + normal.x * minOrth,
                    p1.y + edgeVec.y * maxProj + normal.y * minOrth
                )
                val corner2 = OcrPoint(
                    p1.x + edgeVec.x * maxProj + normal.x * maxOrth,
                    p1.y + edgeVec.y * maxProj + normal.y * maxOrth
                )
                val corner3 = OcrPoint(
                    p1.x + edgeVec.x * minProj + normal.x * maxOrth,
                    p1.y + edgeVec.y * minProj + normal.y * maxOrth
                )

                bestRect = listOf(corner0, corner1, corner2, corner3)
            }
        }

        return if (bestRect.isEmpty()) axisAlignedBoundingBox(hull) else bestRect
    }

    private fun normalizeVector(from: OcrPoint, to: OcrPoint): OcrPoint? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = sqrt(dx * dx + dy * dy)
        if (length < 1e-6f) return null
        return OcrPoint(dx / length, dy / length)
    }

    private fun axisAlignedBoundingBox(points: List<OcrPoint>): List<OcrPoint> {
        if (points.isEmpty()) return emptyList()

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (point in points) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }

        return listOf(
            OcrPoint(minX, minY),
            OcrPoint(maxX, minY),
            OcrPoint(maxX, maxY),
            OcrPoint(minX, maxY)
        )
    }

    fun isPointInsideQuad(x: Float, y: Float, quad: List<OcrPoint>): Boolean {
        if (quad.size < 3) return false

        var hasPositive = false
        var hasNegative = false

        for (i in quad.indices) {
            val p1 = quad[i]
            val p2 = quad[(i + 1) % quad.size]
            val cross = (p2.x - p1.x) * (y - p1.y) - (p2.y - p1.y) * (x - p1.x)
            if (cross > 0) hasPositive = true else if (cross < 0) hasNegative = true
            if (hasPositive && hasNegative) return false
        }

        return true
    }

    /** Andrew's monotone chain convex hull (collinear points dropped). */
    fun convexHull(points: List<OcrPoint>): List<OcrPoint> {
        if (points.size < 3) return points

        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = mutableListOf<OcrPoint>()
        val upper = mutableListOf<OcrPoint>()

        for (point in sorted) {
            while (lower.size >= 2 && crossProduct(lower[lower.size - 2], lower[lower.size - 1], point) <= 0) {
                lower.removeAt(lower.lastIndex)
            }
            lower.add(point)
        }

        for (point in sorted.reversed()) {
            while (upper.size >= 2 && crossProduct(upper[upper.size - 2], upper[upper.size - 1], point) <= 0) {
                upper.removeAt(upper.lastIndex)
            }
            upper.add(point)
        }

        lower.removeAt(lower.lastIndex)
        upper.removeAt(upper.lastIndex)
        return lower + upper
    }

    private fun crossProduct(o: OcrPoint, a: OcrPoint, b: OcrPoint): Float {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    fun distance(p1: OcrPoint, p2: OcrPoint): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Offsets [box] outward by `area * unclipRatio / perimeter` (DBNet "unclip"). */
    fun unclipBox(box: List<OcrPoint>, unclipRatio: Float): List<OcrPoint> {
        if (box.size < 3) return emptyList()

        val area = polygonSignedArea(box)
        val perimeter = polygonPerimeter(box)
        if (perimeter <= EPSILON) return emptyList()

        val offset = abs(area) * unclipRatio / perimeter
        if (offset <= EPSILON) return box

        val expanded = offsetPolygon(box, offset)
        return if (expanded.size >= 3) expanded else emptyList()
    }

    fun getMinSide(box: List<OcrPoint>): Float {
        if (box.size < 2) return 0f
        var minSide = Float.MAX_VALUE
        for (i in box.indices) {
            val next = (i + 1) % box.size
            val length = distance(box[i], box[next])
            if (length < minSide) {
                minSide = length
            }
        }
        return if (minSide == Float.MAX_VALUE) 0f else minSide
    }

    fun polygonSignedArea(points: List<OcrPoint>): Float {
        var area = 0f
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].y - points[j].x * points[i].y
        }
        return area / 2f
    }

    private fun polygonPerimeter(points: List<OcrPoint>): Float {
        var perimeter = 0f
        for (i in points.indices) {
            val j = (i + 1) % points.size
            perimeter += distance(points[i], points[j])
        }
        return perimeter
    }

    private fun offsetPolygon(points: List<OcrPoint>, offset: Float): List<OcrPoint> {
        val count = points.size
        if (count < 3) return emptyList()

        val isCounterClockwise = polygonSignedArea(points) > 0f
        val result = ArrayList<OcrPoint>(count)

        for (i in 0 until count) {
            val prev = points[(i - 1 + count) % count]
            val curr = points[i]
            val next = points[(i + 1) % count]

            val edge1 = OcrPoint(curr.x - prev.x, curr.y - prev.y)
            val edge2 = OcrPoint(next.x - curr.x, next.y - curr.y)

            val dir1 = normalize(edge1) ?: continue
            val dir2 = normalize(edge2) ?: continue

            val normal1 = if (isCounterClockwise) OcrPoint(dir1.y, -dir1.x) else OcrPoint(-dir1.y, dir1.x)
            val normal2 = if (isCounterClockwise) OcrPoint(dir2.y, -dir2.x) else OcrPoint(-dir2.y, dir2.x)

            val offsetPoint1 = OcrPoint(curr.x + normal1.x * offset, curr.y + normal1.y * offset)
            val offsetPoint2 = OcrPoint(curr.x + normal2.x * offset, curr.y + normal2.y * offset)

            val intersection = intersectLines(offsetPoint1, dir1, offsetPoint2, dir2)
            result.add(intersection ?: OcrPoint(curr.x, curr.y))
        }

        return result
    }

    private fun normalize(vector: OcrPoint): OcrPoint? {
        val length = sqrt(vector.x * vector.x + vector.y * vector.y)
        if (length < EPSILON) return null
        return OcrPoint(vector.x / length, vector.y / length)
    }

    private fun intersectLines(
        point: OcrPoint,
        direction: OcrPoint,
        otherPoint: OcrPoint,
        otherDirection: OcrPoint
    ): OcrPoint? {
        val cross = direction.x * otherDirection.y - direction.y * otherDirection.x
        if (abs(cross) < EPSILON) {
            return null
        }

        val diffX = otherPoint.x - point.x
        val diffY = otherPoint.y - point.y
        val t = (diffX * otherDirection.y - diffY * otherDirection.x) / cross

        return OcrPoint(
            point.x + direction.x * t,
            point.y + direction.y * t
        )
    }

    // ---- Point helpers from upstream ImageUtils ----

    /** Orders a quad by angle about its centre, starting at the corner with the smallest x + y. */
    fun orderPointsClockwise(points: List<OcrPoint>): List<OcrPoint> {
        if (points.size != 4) {
            return points
        }

        // Calculate center point
        val centerX = points.sumOf { it.x.toDouble() }.toFloat() / 4
        val centerY = points.sumOf { it.y.toDouble() }.toFloat() / 4

        // Sort points by angle from center
        val sortedPoints = points.sortedBy { point ->
            atan2(
                (point.y - centerY).toDouble(),
                (point.x - centerX).toDouble()
            )
        }

        // Find top-left point (minimum sum of x and y)
        var topLeftIndex = 0
        var minSum = sortedPoints[0].x + sortedPoints[0].y
        for (i in 1 until 4) {
            val sum = sortedPoints[i].x + sortedPoints[i].y
            if (sum < minSum) {
                minSum = sum
                topLeftIndex = i
            }
        }

        // Reorder starting from top-left
        val orderedPoints = mutableListOf<OcrPoint>()
        for (i in 0 until 4) {
            orderedPoints.add(sortedPoints[(topLeftIndex + i) % 4])
        }

        return orderedPoints
    }

    fun clipBoxToImageBounds(
        points: List<OcrPoint>,
        imageWidth: Int,
        imageHeight: Int
    ): List<OcrPoint> {
        return points.map { point ->
            OcrPoint(
                point.x.coerceIn(0f, imageWidth - 1f),
                point.y.coerceIn(0f, imageHeight - 1f)
            )
        }
    }

    fun calculateBoxArea(points: List<OcrPoint>): Float {
        if (points.size != 4) {
            return 0f
        }

        // Shoelace formula for quadrilateral area
        var area = 0f
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].x * points[j].y
            area -= points[j].x * points[i].y
        }
        return abs(area) / 2f
    }
}

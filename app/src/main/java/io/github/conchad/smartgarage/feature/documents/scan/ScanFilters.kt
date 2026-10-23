package io.github.conchad.smartgarage.feature.documents.scan

/** The look of a scanned page (D-A59). */
enum class ScanFilter { Original, Greyscale, BlackWhite }

/**
 * The pixel maths of the scanner (D-A59) on plain arrays, so it is unit-tested without a device: luminance, Otsu's
 * threshold, the "B&W document" adaptive threshold, and the corner guess for a fresh photo.
 */
object ScanFilters {
    /** Rec. 601 luma of an ARGB pixel, 0..255 (integer weights 77/150/29 sum to 256). */
    fun luminance(argb: Int): Int = (77 * ((argb shr 16) and 0xFF) + 150 * ((argb shr 8) and 0xFF) + 29 * (argb and 0xFF)) shr 8

    fun luminance(pixels: IntArray): IntArray = IntArray(pixels.size) { luminance(pixels[it]) }

    /** Otsu's threshold over 0..255 values: pixels above it are the bright class. */
    fun otsu(lum: IntArray): Int {
        val hist = IntArray(256)
        for (v in lum) hist[v.coerceIn(0, 255)]++
        val total = lum.size.toDouble()
        var sumAll = 0.0
        for (i in 0 until 256) sumAll += i.toDouble() * hist[i]
        var sumB = 0.0
        var weightB = 0.0
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            weightB += hist[t]
            if (weightB == 0.0) continue
            val weightF = total - weightB
            if (weightF == 0.0) break
            sumB += t.toDouble() * hist[t]
            val meanB = sumB / weightB
            val meanF = (sumAll - sumB) / weightF
            val between = weightB * weightF * (meanB - meanF) * (meanB - meanF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    /**
     * Bradley's adaptive threshold: a pixel turns black when it is more than [bias] darker than the mean of the
     * [window]-wide square around it, else white. Ink stays crisp under uneven light, paper goes clean white.
     * Returns 0 or 255 per pixel. An integral image keeps it O(n); its sums fit an Int up to ~8 MP.
     */
    fun adaptiveThreshold(lum: IntArray, width: Int, height: Int, window: Int = defaultWindow(width, height), bias: Float = 0.12f): IntArray {
        require(lum.size == width * height)
        val stride = width + 1
        val integral = IntArray(stride * (height + 1))
        for (y in 0 until height) {
            var row = 0
            for (x in 0 until width) {
                row += lum[y * width + x]
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        val half = maxOf(1, window / 2)
        val out = IntArray(lum.size)
        for (y in 0 until height) {
            val y0 = maxOf(0, y - half)
            val y1 = minOf(height, y + half + 1)
            for (x in 0 until width) {
                val x0 = maxOf(0, x - half)
                val x1 = minOf(width, x + half + 1)
                val count = (x1 - x0) * (y1 - y0)
                val sum = integral[y1 * stride + x1] - integral[y0 * stride + x1] - integral[y1 * stride + x0] + integral[y0 * stride + x0]
                // lum * count < sum * (1 - bias), in integers scaled by 256.
                val dark = lum[y * width + x].toLong() * count * 256 < sum.toLong() * (256 - (bias * 256).toInt())
                out[y * width + x] = if (dark) 0 else 255
            }
        }
        return out
    }

    /** A page being scanned fills a good part of the photo; a smaller bright blob is a lamp or a reflection. */
    const val MIN_PAGE_AREA = 0.2f

    /** About an eighth of the short edge: wide enough to span a letter, narrow enough to follow a shadow. */
    fun defaultWindow(width: Int, height: Int): Int = maxOf(15, minOf(width, height) / 8)

    /**
     * A guess at a paper page's corners in a small greyscale photo (a page brighter than what it lies on): Otsu's
     * threshold, the largest bright 4-connected blob, then that blob's extreme points along the two diagonals.
     * Normalized to 0..1, ordered TL, TR, BR, BL; null when no plausible page (a convex outline over at least
     * [MIN_PAGE_AREA] of the photo, a blob under 99 % of it) shows.
     */
    fun detectCorners(lum: IntArray, width: Int, height: Int): List<Pt>? {
        val n = width * height
        if (n == 0 || lum.size != n) return null
        val t = otsu(lum)
        val bright = BooleanArray(n) { lum[it] > t }
        val label = IntArray(n) { -1 }
        val queue = IntArray(n)
        var bestLabel = -1
        var bestSize = 0
        var next = 0
        for (start in 0 until n) {
            if (!bright[start] || label[start] >= 0) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            label[start] = next
            while (head < tail) {
                val p = queue[head++]
                val x = p % width
                val y = p / width
                fun visit(q: Int) {
                    if (bright[q] && label[q] < 0) {
                        label[q] = next
                        queue[tail++] = q
                    }
                }
                if (x > 0) visit(p - 1)
                if (x < width - 1) visit(p + 1)
                if (y > 0) visit(p - width)
                if (y < height - 1) visit(p + width)
            }
            if (tail > bestSize) {
                bestSize = tail
                bestLabel = next
            }
            next++
        }
        if (bestLabel < 0 || bestSize < n / 10 || bestSize > n * 99 / 100) return null
        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE; var maxDiff = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE
        for (p in 0 until n) {
            if (label[p] != bestLabel) continue
            val x = p % width
            val y = p / width
            val s = x + y
            val d = x - y
            if (s < minSum) { minSum = s; tl = p }
            if (s > maxSum) { maxSum = s; br = p }
            if (d > maxDiff) { maxDiff = d; tr = p }
            if (d < minDiff) { minDiff = d; bl = p }
        }
        fun norm(p: Int) = Pt(((p % width) + 0.5f) / width, ((p / width) + 0.5f) / height)
        val corners = listOf(norm(tl), norm(tr), norm(br), norm(bl))
        if (corners.toSet().size < 4 || !ScanGeometry.isConvex(corners) || ScanGeometry.area(corners) < MIN_PAGE_AREA) return null
        return corners
    }
}

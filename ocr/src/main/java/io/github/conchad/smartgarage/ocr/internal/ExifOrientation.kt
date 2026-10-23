// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: the orientation -> Matrix mapping of MobileOcrPlugin.applyExifOrientation is a pure
// integer 2x2 transform so it runs (and is unit-tested) on the JVM. Fixes upstream swapping TRANSPOSE (5) and
// TRANSVERSE (7): rotate-then-preScale composes to the other diagonal reflection.
package io.github.conchad.smartgarage.ocr.internal

/**
 * The linear part of an EXIF orientation fix, mapping stored pixel (x, y) to (a*x + b*y, c*x + d*y) in y-down
 * screen coordinates; [android.graphics.Bitmap.createBitmap] with a matrix re-normalises the translation.
 */
internal data class OrientationTransform(val a: Int, val b: Int, val c: Int, val d: Int) {
    val isIdentity: Boolean get() = a == 1 && b == 0 && c == 0 && d == 1

    /** True when the upright image is [height] x [width] (any 90/270 degree component). */
    val swapsDimensions: Boolean get() = a == 0

    /**
     * Where the stored point ([x], [y]) of a [width] x [height] image lands in the upright image, with the
     * same translation normalisation Bitmap.createBitmap applies (bounding box moved to the origin).
     */
    fun mapPoint(x: Float, y: Float, width: Int, height: Int): Pair<Float, Float> {
        val corners = listOf(0f to 0f, width.toFloat() to 0f, 0f to height.toFloat(), width.toFloat() to height.toFloat())
        val minX = corners.minOf { (cx, cy) -> a * cx + b * cy }
        val minY = corners.minOf { (cx, cy) -> c * cx + d * cy }
        return (a * x + b * y - minX) to (c * x + d * y - minY)
    }

    /** Values for android.graphics.Matrix.setValues. */
    fun matrixValues(): FloatArray =
        floatArrayOf(a.toFloat(), b.toFloat(), 0f, c.toFloat(), d.toFloat(), 0f, 0f, 0f, 1f)
}

internal object ExifOrientation {
    // androidx.exifinterface.media.ExifInterface.ORIENTATION_* values (EXIF 2.3 tag 0x0112).
    const val NORMAL = 1
    const val FLIP_HORIZONTAL = 2
    const val ROTATE_180 = 3
    const val FLIP_VERTICAL = 4
    const val TRANSPOSE = 5
    const val ROTATE_90 = 6
    const val TRANSVERSE = 7
    const val ROTATE_270 = 8

    private val IDENTITY = OrientationTransform(1, 0, 0, 1)

    /** The transform that turns a stored image with EXIF [orientation] upright; unknown values are identity. */
    fun transformFor(orientation: Int): OrientationTransform = when (orientation) {
        FLIP_HORIZONTAL -> OrientationTransform(-1, 0, 0, 1)
        ROTATE_180 -> OrientationTransform(-1, 0, 0, -1)
        FLIP_VERTICAL -> OrientationTransform(1, 0, 0, -1)
        TRANSPOSE -> OrientationTransform(0, 1, 1, 0)
        ROTATE_90 -> OrientationTransform(0, -1, 1, 0)
        TRANSVERSE -> OrientationTransform(0, -1, -1, 0)
        ROTATE_270 -> OrientationTransform(0, 1, -1, 0)
        else -> IDENTITY
    }
}

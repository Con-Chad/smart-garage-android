// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: the pure point helpers (orderPointsClockwise, clipBoxToImageBounds,
// calculateBoxArea, distance) live in PolygonGeometry; this object keeps the Bitmap/buffer work.
package io.github.conchad.smartgarage.ocr.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import io.github.conchad.smartgarage.ocr.OcrPoint
import java.nio.FloatBuffer
import kotlin.math.max

internal object ImageUtils {

    /**
     * Safely converts a FloatBuffer to a FloatArray.
     * This handles both heap and direct buffers, unlike FloatBuffer.array()
     * which throws UnsupportedOperationException for direct buffers.
     */
    fun toFloatArray(buffer: FloatBuffer): FloatArray {
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        val array = FloatArray(duplicate.remaining())
        duplicate.get(array)
        return array
    }

    fun cropTextRegion(bitmap: Bitmap, points: List<OcrPoint>): Bitmap {
        if (points.size != 4) {
            throw IllegalArgumentException("Expected 4 points for text region")
        }
        if (bitmap.isRecycled) {
            throw IllegalStateException("Source bitmap was recycled before cropping")
        }

        // Calculate dimensions of the cropped region
        // Use maximum of opposing sides to avoid truncation (matching Python implementation)
        val width = max(
            PolygonGeometry.distance(points[0], points[1]), // top edge
            PolygonGeometry.distance(points[2], points[3]) // bottom edge
        ).toInt().coerceAtLeast(1)
        val height = max(
            PolygonGeometry.distance(points[0], points[3]), // left edge
            PolygonGeometry.distance(points[1], points[2]) // right edge
        ).toInt().coerceAtLeast(1)

        // Create destination points for perspective transform
        val dstPoints = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat()
        )

        // Create source points
        val srcPoints = floatArrayOf(
            points[0].x, points[0].y,
            points[1].x, points[1].y,
            points[2].x, points[2].y,
            points[3].x, points[3].y
        )

        // Calculate perspective transform matrix
        val matrix = Matrix().apply {
            setPolyToPoly(srcPoints, 0, dstPoints, 0, 4)
        }
        val croppedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(croppedBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }

        canvas.drawBitmap(bitmap, matrix, paint)

        // Check if the image needs rotation based on aspect ratio
        if (height.toFloat() / width >= 1.5f) {
            val rotationMatrix = Matrix().apply {
                postRotate(90f)
            }
            return Bitmap.createBitmap(
                croppedBitmap,
                0,
                0,
                croppedBitmap.width,
                croppedBitmap.height,
                rotationMatrix,
                true
            )
        }

        return croppedBitmap
    }
}

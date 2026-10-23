// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: android.graphics.PointF/RectF replaced by the plain OcrPoint/BoundingRect value
// types so geometry runs (and is unit-tested) on the JVM; DebugOptions takes a directory instead of a Context.
package io.github.conchad.smartgarage.ocr

import java.io.File

/** A point in source-image pixel coordinates. */
data class OcrPoint(val x: Float, val y: Float)

/** Axis-aligned bounds in source-image pixel coordinates. */
data class BoundingRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class CharacterBox(
    val text: String,
    val confidence: Float,
    val points: List<OcrPoint>
)

data class OcrResult(
    val boxes: List<TextBox>,
    val texts: List<String>,
    val scores: List<Float>,
    val characters: List<List<CharacterBox>>
)

/** A detected text region: four corners ordered clockwise from the top-left. */
data class TextBox(
    val points: List<OcrPoint>
) {
    fun boundingRect(): BoundingRect {
        if (points.isEmpty()) {
            return BoundingRect(0f, 0f, 0f, 0f)
        }

        var minX = points[0].x
        var maxX = points[0].x
        var minY = points[0].y
        var maxY = points[0].y

        for (point in points) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }

        return BoundingRect(minX, minY, maxX, maxY)
    }
}

data class DetectionCandidate(
    val box: TextBox,
    val score: Float
)

data class QuickCheckResult(
    val hasText: Boolean,
    val detectorHit: Boolean,
    val examinedDetections: Int,
    val candidateCount: Int,
    val evaluatedCandidates: Int,
    val maxDetectionScore: Float?,
    val bestRecognitionScore: Float?,
    val bestRecognitionText: String?,
    val matchedDetectionScore: Float?
)

/**
 * Debug aids. [saveCrops] writes PNG crops into [outputDirectory] (for example a subdirectory of the
 * app's cache dir); it is ignored while [outputDirectory] is null.
 */
data class DebugOptions(
    val saveCrops: Boolean = false,
    val logRecognition: Boolean = false,
    val outputDirectory: File? = null
)

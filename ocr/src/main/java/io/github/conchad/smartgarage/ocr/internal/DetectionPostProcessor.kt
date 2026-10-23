// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: TextDetector.postprocessDetection and sortBoxes moved here, taking the raw
// probability map as a FloatArray instead of an OnnxTensor so DBNet post-processing is testable on the JVM.
// Thresholds and control flow are unchanged.
package io.github.conchad.smartgarage.ocr.internal

import io.github.conchad.smartgarage.ocr.OcrPoint
import io.github.conchad.smartgarage.ocr.TextBox
import kotlin.math.abs

internal object DetectionPostProcessor {
    const val THRESH = 0.3f
    const val BOX_THRESH = 0.6f
    const val UNCLIP_RATIO = 1.5f
    const val MIN_SIZE = 3
    const val MAX_CANDIDATES = 1000

    /** Boxes whose top edges are within this many pixels are treated as one row when sorting. */
    const val ROW_TOLERANCE = 10f

    /**
     * Turns the detector's probability map (first channel, row-major `resizedHeight x resizedWidth`) into
     * text boxes in original-image coordinates. Each box and its score go to [handler] (largest components
     * first); returning `true` from [handler] stops the scan.
     */
    fun postprocess(
        probabilities: FloatArray,
        originalWidth: Int,
        originalHeight: Int,
        resizedWidth: Int,
        resizedHeight: Int,
        handler: (TextBox, Float) -> Boolean
    ) {
        val probMap = Array(resizedHeight) { FloatArray(resizedWidth) }

        // Extract probability map (first channel)
        for (y in 0 until resizedHeight) {
            for (x in 0 until resizedWidth) {
                probMap[y][x] = probabilities[y * resizedWidth + x]
            }
        }

        // Apply threshold to get binary map
        val binaryMap = Array(resizedHeight) { BooleanArray(resizedWidth) }
        for (y in 0 until resizedHeight) {
            for (x in 0 until resizedWidth) {
                binaryMap[y][x] = probMap[y][x] > THRESH
            }
        }

        val components = PolygonGeometry.extractConnectedComponents(binaryMap)
            .sortedByDescending { it.size }
            .take(MAX_CANDIDATES)
        val scaleX = originalWidth.toFloat() / resizedWidth
        val scaleY = originalHeight.toFloat() / resizedHeight

        componentLoop@ for (component in components) {
            if (component.size < 4) continue

            val hull = PolygonGeometry.convexHull(component)
            if (hull.size < 3) continue

            val rect = PolygonGeometry.minimumAreaRectangle(hull, pointsAreConvex = true)
            if (rect.isEmpty()) continue

            val score = PolygonGeometry.calculateBoxScore(probMap, rect)
            if (score < BOX_THRESH) continue

            val unclippedPolygon = PolygonGeometry.unclipBox(rect, UNCLIP_RATIO)
            if (unclippedPolygon.isEmpty()) continue

            val expandedRect = PolygonGeometry.minimumAreaRectangle(unclippedPolygon, pointsAreConvex = false)
            if (expandedRect.isEmpty()) continue

            val minSide = PolygonGeometry.getMinSide(expandedRect)
            if (minSide < MIN_SIZE) continue

            val clippedRect = PolygonGeometry.clipBoxToImageBounds(expandedRect, resizedWidth, resizedHeight)

            val scaledPoints = clippedRect.map { point ->
                OcrPoint(point.x * scaleX, point.y * scaleY)
            }

            val orderedPoints = PolygonGeometry.orderPointsClockwise(scaledPoints)
            val shouldBreak = handler(TextBox(orderedPoints), score)
            if (shouldBreak) {
                break@componentLoop
            }
        }
    }

    /** Reading order: rows top to bottom (tops within [ROW_TOLERANCE] share a row), left to right within a row. */
    fun sortBoxes(boxes: List<TextBox>): List<TextBox> {
        if (boxes.isEmpty()) return emptyList()

        val sortedByTop = boxes.sortedBy { box ->
            box.points.minOf { it.y }
        }

        val ordered = mutableListOf<TextBox>()
        var index = 0
        while (index < sortedByTop.size) {
            val current = sortedByTop[index]
            val referenceY = current.points.minOf { it.y }
            val group = mutableListOf<TextBox>()

            var j = index
            while (j < sortedByTop.size) {
                val candidate = sortedByTop[j]
                val candidateY = candidate.points.minOf { it.y }
                if (abs(candidateY - referenceY) <= ROW_TOLERANCE) {
                    group.add(candidate)
                    j++
                } else {
                    break
                }
            }

            group.sortBy { box -> box.points.minOf { it.x } }
            ordered.addAll(group)
            index = j
        }

        return ordered
    }
}

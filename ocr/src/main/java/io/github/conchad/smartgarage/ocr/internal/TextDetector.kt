// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: post-processing and geometry moved to DetectionPostProcessor/PolygonGeometry
// (pure Kotlin, JVM-testable); this class keeps preprocessing and inference.
package io.github.conchad.smartgarage.ocr.internal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import io.github.conchad.smartgarage.ocr.DetectionCandidate
import io.github.conchad.smartgarage.ocr.OnnxCancellationSignal
import io.github.conchad.smartgarage.ocr.TextBox
import io.github.conchad.smartgarage.ocr.runOnnx
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

internal data class DetectionStageSummary(
    val examinedDetections: Int,
    val maxDetectionScore: Float?,
    val candidates: List<DetectionCandidate>
)

internal class TextDetector(
    private val session: OrtSession,
    private val ortEnv: OrtEnvironment,
    private val cancellationSignal: OnnxCancellationSignal? = null
) {
    companion object {
        private const val LIMIT_SIDE_LEN = 960

        internal fun calculateResizeDimensions(width: Int, height: Int): Pair<Int, Int> {
            val maxSide = max(width, height)
            val ratio = if (maxSide > LIMIT_SIDE_LEN) {
                LIMIT_SIDE_LEN.toFloat() / maxSide
            } else {
                1.0f
            }

            var resizedWidth = max(1, (width * ratio).roundToInt())
            var resizedHeight = max(1, (height * ratio).roundToInt())

            // Make dimensions multiple of 32 (minimum 32)
            resizedWidth = max(((resizedWidth + 31) / 32) * 32, 32)
            resizedHeight = max(((resizedHeight + 31) / 32) * 32, 32)

            return Pair(resizedWidth, resizedHeight)
        }
    }

    fun detect(bitmap: Bitmap): List<TextBox> {
        val boxes = mutableListOf<TextBox>()
        runDetection(bitmap) { box, _ ->
            boxes.add(box)
            false
        }

        if (boxes.isEmpty()) {
            return emptyList()
        }

        return DetectionPostProcessor.sortBoxes(boxes)
    }

    fun collectHighConfidenceDetections(
        bitmap: Bitmap,
        minimumDetectionConfidence: Float,
        maxCandidates: Int
    ): DetectionStageSummary {
        var examined = 0
        var maxScore = Float.NEGATIVE_INFINITY
        val candidates = mutableListOf<DetectionCandidate>()

        runDetection(bitmap) { box, score ->
            examined++
            if (score > maxScore) {
                maxScore = score
            }
            val meetsThreshold = score >= minimumDetectionConfidence
            if (meetsThreshold) {
                candidates.add(DetectionCandidate(box, score))
                candidates.size >= maxCandidates
            } else {
                false
            }
        }

        val bestScore = if (examined == 0) null else maxScore
        return DetectionStageSummary(
            examinedDetections = examined,
            maxDetectionScore = bestScore,
            candidates = candidates
        )
    }

    private fun runDetection(
        bitmap: Bitmap,
        handler: (TextBox, Float) -> Boolean
    ) {
        cancellationSignal?.ensureActive()
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        val (inputTensor, resizedWidth, resizedHeight) = preprocessImage(bitmap)

        try {
            val inputs = mapOf("x" to inputTensor)
            runOnnx(session, inputs, cancellationSignal).use { outputs ->
                DetectionPostProcessor.postprocess(
                    probabilities = ImageUtils.toFloatArray((outputs[0] as OnnxTensor).floatBuffer),
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                    resizedWidth = resizedWidth,
                    resizedHeight = resizedHeight,
                    handler = handler
                )
            }
            cancellationSignal?.ensureActive()
        } finally {
            inputTensor.close()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): Triple<OnnxTensor, Int, Int> {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // Calculate resize dimensions
        val (resizedWidth, resizedHeight) = calculateResizeDimensions(originalWidth, originalHeight)

        // Resize bitmap; Android may return the original if sizes match
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)

        // Convert to float array with normalization
        val inputArray = FloatArray(1 * 3 * resizedHeight * resizedWidth)
        val pixels = IntArray(resizedWidth * resizedHeight)
        resizedBitmap.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, resizedHeight)

        // Normalization parameters from OnnxOCR
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val scale = 1.0f / 255.0f

        var pixelIndex = 0
        for (y in 0 until resizedHeight) {
            for (x in 0 until resizedWidth) {
                val pixel = pixels[y * resizedWidth + x]
                val b = (pixel and 0xFF) * scale
                val g = ((pixel shr 8) and 0xFF) * scale
                val r = ((pixel shr 16) and 0xFF) * scale

                // CHW format, BGR order to match PaddleOCR training data
                inputArray[pixelIndex] = (b - mean[0]) / std[0]
                inputArray[pixelIndex + resizedHeight * resizedWidth] = (g - mean[1]) / std[1]
                inputArray[pixelIndex + 2 * resizedHeight * resizedWidth] = (r - mean[2]) / std[2]
                pixelIndex++
            }
        }

        if (resizedBitmap !== bitmap && !resizedBitmap.isRecycled) {
            resizedBitmap.recycle()
        }

        val shape = longArrayOf(1, 3, resizedHeight.toLong(), resizedWidth.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputArray), shape)

        return Triple(inputTensor, resizedWidth, resizedHeight)
    }
}

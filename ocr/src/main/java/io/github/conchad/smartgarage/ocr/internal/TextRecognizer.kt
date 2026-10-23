// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: CTC decoding moved to CtcDecoder (pure Kotlin, JVM-testable).
package io.github.conchad.smartgarage.ocr.internal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import io.github.conchad.smartgarage.ocr.OnnxCancellationSignal
import io.github.conchad.smartgarage.ocr.runOnnx
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.min

internal class TextRecognizer(
    private val session: OrtSession,
    private val ortEnv: OrtEnvironment,
    private val characterDict: List<String>,
    private val cancellationSignal: OnnxCancellationSignal? = null
) {
    companion object {
        private const val IMG_HEIGHT = 48
        private const val IMG_WIDTH = 320
        private const val BATCH_SIZE = 6
    }

    fun recognize(images: List<Bitmap>): List<RecognitionResult> {
        if (images.isEmpty()) {
            return emptyList()
        }

        val widthList = images.map { it.width.toFloat() / it.height }
        val sortedIndices = widthList.indices.sortedBy { widthList[it] }
        val orderedResults = MutableList(images.size) {
            RecognitionResult("", 0f, emptyList())
        }

        for (start in sortedIndices.indices step BATCH_SIZE) {
            cancellationSignal?.ensureActive()
            val end = min(start + BATCH_SIZE, sortedIndices.size)
            val batchIndices = sortedIndices.subList(start, end)
            val batchBitmaps = batchIndices.map { images[it] }
            val batchResults = processBatch(batchBitmaps)

            batchIndices.forEachIndexed { idx, originalIndex ->
                orderedResults[originalIndex] = batchResults[idx]
            }
        }

        return orderedResults
    }

    private fun processBatch(batchImages: List<Bitmap>): List<RecognitionResult> {
        if (batchImages.isEmpty()) return emptyList()

        // Calculate max width-height ratio for the batch (baseline ratio aligns with Python implementation)
        var maxWhRatio = IMG_WIDTH.toFloat() / IMG_HEIGHT
        for (image in batchImages) {
            val ratio = image.width.toFloat() / image.height
            if (ratio > maxWhRatio) {
                maxWhRatio = ratio
            }
        }

        val targetWidth = ceil(IMG_HEIGHT * maxWhRatio).toInt().coerceAtLeast(1)

        // Prepare batch input
        val batchSize = batchImages.size
        val inputArray = FloatArray(batchSize * 3 * IMG_HEIGHT * targetWidth)

        val contentWidths = IntArray(batchSize)
        for ((index, image) in batchImages.withIndex()) {
            val resizedWidth = preprocessImage(image, inputArray, index, targetWidth)
            contentWidths[index] = resizedWidth
        }

        // Create tensor
        val shape = longArrayOf(batchSize.toLong(), 3, IMG_HEIGHT.toLong(), targetWidth.toLong())
        val inputTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(inputArray), shape)

        // Run inference
        val inputs = mapOf(session.inputNames.first() to inputTensor)
        return try {
            runOnnx(session, inputs, cancellationSignal).use { outputs ->
                val output = outputs[0] as OnnxTensor
                val outputShape = output.info.shape
                CtcDecoder.decodeBatch(
                    output = ImageUtils.toFloatArray(output.floatBuffer),
                    batchSize = batchSize,
                    seqLen = outputShape[1].toInt(),
                    vocabSize = outputShape[2].toInt(),
                    contentWidths = contentWidths,
                    targetWidth = targetWidth,
                    characterDict = characterDict
                )
            }
        } finally {
            inputTensor.close()
        }
    }

    private fun preprocessImage(
        bitmap: Bitmap,
        outputArray: FloatArray,
        batchIndex: Int,
        targetWidth: Int
    ): Int {
        // Calculate resize dimensions maintaining aspect ratio
        val aspectRatio = bitmap.width.toFloat() / bitmap.height
        val resizedWidth = min(
            ceil(IMG_HEIGHT * aspectRatio).toInt().coerceAtLeast(1),
            targetWidth
        )

        // Resize bitmap; Android may return the original if sizes match
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, resizedWidth, IMG_HEIGHT, true)

        // Get pixels
        val pixels = IntArray(resizedWidth * IMG_HEIGHT)
        resizedBitmap.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, IMG_HEIGHT)
        if (resizedBitmap !== bitmap && !resizedBitmap.isRecycled) {
            resizedBitmap.recycle()
        }

        // Normalize and convert to CHW format
        val baseOffset = batchIndex * 3 * IMG_HEIGHT * targetWidth

        val channelStride = IMG_HEIGHT * targetWidth

        for (y in 0 until IMG_HEIGHT) {
            val rowOffset = y * targetWidth
            val sourceRowOffset = y * resizedWidth

            for (x in 0 until targetWidth) {
                val pixelIndex = rowOffset + x

                if (x < resizedWidth) {
                    val pixel = pixels[sourceRowOffset + x]
                    val b = (pixel and 0xFF) / 255.0f
                    val g = ((pixel shr 8) and 0xFF) / 255.0f
                    val r = ((pixel shr 16) and 0xFF) / 255.0f

                    outputArray[baseOffset + pixelIndex] = (b - 0.5f) / 0.5f
                    outputArray[baseOffset + channelStride + pixelIndex] = (g - 0.5f) / 0.5f
                    outputArray[baseOffset + 2 * channelStride + pixelIndex] = (r - 0.5f) / 0.5f
                } else {
                    outputArray[baseOffset + pixelIndex] = 0f
                    outputArray[baseOffset + channelStride + pixelIndex] = 0f
                    outputArray[baseOffset + 2 * channelStride + pixelIndex] = 0f
                }
            }
        }

        return resizedWidth
    }
}

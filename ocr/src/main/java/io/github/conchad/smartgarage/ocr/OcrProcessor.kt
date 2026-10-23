// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: models come from an OcrModels set of ModelSources (file or asset bytes) instead of
// downloaded ModelFiles; no Context (debug crops go to DebugOptions.outputDirectory); result types moved to
// OcrTypes.kt; close() also releases the session options.
package io.github.conchad.smartgarage.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.util.Log
import io.github.conchad.smartgarage.ocr.internal.CharacterBoxGeometry
import io.github.conchad.smartgarage.ocr.internal.CtcDecoder
import io.github.conchad.smartgarage.ocr.internal.ImageUtils
import io.github.conchad.smartgarage.ocr.internal.PolygonGeometry
import io.github.conchad.smartgarage.ocr.internal.RecognitionResult
import io.github.conchad.smartgarage.ocr.internal.TextClassifier
import io.github.conchad.smartgarage.ocr.internal.TextDetector
import io.github.conchad.smartgarage.ocr.internal.TextRecognizer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Full PP-OCRv5 pipeline: detection, optional 180-degree angle classification, recognition.
 * Construction loads the ONNX sessions; every call is blocking CPU work, so run it on
 * Dispatchers.Default (never the main thread). Call [close] when done.
 */
class OcrProcessor(
    private val models: OcrModels,
    private val useAngleClassification: Boolean = true,
    private val debugOptions: DebugOptions = DebugOptions()
) {
    companion object {
        private const val MIN_RECOGNITION_SCORE = 0.8f
        private const val FALLBACK_MIN_RECOGNITION_SCORE = 0.5f
        private const val ANGLE_ASPECT_RATIO_THRESHOLD = 0.5f
        private const val LOW_CONFIDENCE_THRESHOLD = 0.65f
        private const val DEBUG_TAG = "OnnxOcrDebug"
        private const val QUICK_CHECK_MAX_CANDIDATES = 3
    }

    private val ortEnv = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    private lateinit var detectionSession: OrtSession
    private lateinit var recognitionSession: OrtSession
    private var classificationSession: OrtSession? = null

    private lateinit var characterDict: List<String>

    init {
        loadSessions()
        loadCharacterDict()
    }

    private fun loadSessions() {
        detectionSession = models.detection.createSession(ortEnv, sessionOptions)
        recognitionSession = models.recognition.createSession(ortEnv, sessionOptions)

        if (useAngleClassification) {
            val classification = requireNotNull(models.classification) {
                "Angle classification requested but no classification model was supplied"
            }
            classificationSession = classification.createSession(ortEnv, sessionOptions)
        }
    }

    private fun loadCharacterDict() {
        characterDict = CtcDecoder.characterDictionary(models.dictionary.readLines())
    }

    fun processImage(
        bitmap: Bitmap,
        includeAllConfidenceScores: Boolean = false,
        cancellationSignal: OnnxCancellationSignal? = null
    ): OcrResult {
        val checkCancellation = { cancellationSignal?.ensureActive() }
        // Step 1: Text Detection
        val detectionResult = detectText(bitmap, cancellationSignal)
        checkCancellation()

        if (detectionResult.isEmpty()) {
            return OcrResult(emptyList(), emptyList(), emptyList(), emptyList())
        }

        val croppedImages = mutableListOf<Bitmap>()
        try {
            // Step 2: Crop text regions
            detectionResult.forEachIndexed { index, box ->
                checkCancellation()
                val cropped = cropTextRegion(bitmap, box)
                saveDebugBitmap(cropped, "crop", index, "raw")
                croppedImages.add(cropped)
            }

            val classificationMask = BooleanArray(croppedImages.size)
            val rotationStates = BooleanArray(croppedImages.size)

            if (useAngleClassification) {
                checkCancellation()
                val aspectCandidates = croppedImages.mapIndexedNotNull { index, image ->
                    val aspectRatio = image.width.toFloat() / image.height
                    if (aspectRatio < ANGLE_ASPECT_RATIO_THRESHOLD) index else null
                }
                classifyAndRotateIndices(
                    croppedImages,
                    aspectCandidates,
                    classificationMask,
                    rotationStates,
                    "angle_aspect",
                    cancellationSignal
                )
            }

            // Step 3: Text recognition
            checkCancellation()
            val recognitionResults = recognizeText(
                croppedImages,
                cancellationSignal
            ).toMutableList()
            checkCancellation()

            if (useAngleClassification && recognitionResults.isNotEmpty()) {
                val lowConfidenceIndices = recognitionResults.mapIndexedNotNull { index, result ->
                    if (!classificationMask[index] && result.confidence < LOW_CONFIDENCE_THRESHOLD) index else null
                }

                if (lowConfidenceIndices.isNotEmpty()) {
                    checkCancellation()
                    classifyAndRotateIndices(
                        croppedImages,
                        lowConfidenceIndices,
                        classificationMask,
                        rotationStates,
                        "angle_confidence",
                        cancellationSignal
                    )
                    val refreshed = recognizeText(
                        lowConfidenceIndices.map { croppedImages[it] },
                        cancellationSignal
                    )
                    checkCancellation()
                    lowConfidenceIndices.forEachIndexed { refreshedIndex, originalIndex ->
                        val current = recognitionResults[originalIndex]
                        val updated = refreshed[refreshedIndex]
                        recognitionResults[originalIndex] =
                            if (updated.confidence > current.confidence) updated else current
                    }
                }
            }

            if (debugOptions.logRecognition) {
                logDebug("Detected ${recognitionResults.size} regions")
                recognitionResults.forEachIndexed { index, result ->
                    logDebug("[$index] score=${"%.3f".format(result.confidence)} text=${result.text}")
                }
            }

            val characterBoxesPerDetection = recognitionResults.mapIndexed { index, result ->
                checkCancellation()
                CharacterBoxGeometry.build(
                    detectionResult[index],
                    result.characterSpans,
                    rotationStates[index]
                )
            }

            // Step 4: Filter by confidence score
            val minThreshold = if (includeAllConfidenceScores) FALLBACK_MIN_RECOGNITION_SCORE else MIN_RECOGNITION_SCORE
            val filteredResults = mutableListOf<TextBox>()
            val filteredTexts = mutableListOf<String>()
            val filteredScores = mutableListOf<Float>()
            val filteredCharacters = mutableListOf<List<CharacterBox>>()

            for (i in recognitionResults.indices) {
                val recognition = recognitionResults[i]
                if (recognition.confidence >= minThreshold) {
                    filteredResults.add(detectionResult[i])
                    filteredTexts.add(recognition.text)
                    filteredScores.add(recognition.confidence)
                    filteredCharacters.add(characterBoxesPerDetection[i])
                }
            }
            if (filteredResults.isEmpty()) {
                val bestRecognition = recognitionResults.maxOfOrNull { it.confidence }
                val thresholdString = String.format(Locale.US, "%.2f", minThreshold)
                val bestRecognitionString = bestRecognition?.let { String.format(Locale.US, "%.3f", it) } ?: "none"
                Log.i(
                    DEBUG_TAG,
                    "Recognition produced no results. detections=${detectionResult.size}, " +
                        "includeAllConfidenceScores=$includeAllConfidenceScores, threshold=$thresholdString, " +
                        "bestRecognitionScore=$bestRecognitionString"
                )
            }

            return OcrResult(filteredResults, filteredTexts, filteredScores, filteredCharacters)
        } finally {
            croppedImages.forEach { crop ->
                if (!crop.isRecycled) {
                    crop.recycle()
                }
            }
        }
    }

    private fun detectText(
        bitmap: Bitmap,
        cancellationSignal: OnnxCancellationSignal?
    ): List<TextBox> {
        val processor = TextDetector(detectionSession, ortEnv, cancellationSignal)
        return processor.detect(bitmap)
    }

    private fun recognizeCandidate(
        bitmap: Bitmap,
        box: TextBox,
        cancellationSignal: OnnxCancellationSignal?
    ): RecognitionResult? {
        val crop = cropTextRegion(bitmap, box)
        val crops = mutableListOf(crop)
        val classificationMask = BooleanArray(1)
        val rotationStates = BooleanArray(1)
        try {

            if (useAngleClassification) {
                val aspectRatio = crop.width.toFloat() / crop.height
                val aspectCandidates = if (aspectRatio < ANGLE_ASPECT_RATIO_THRESHOLD) listOf(0) else emptyList()
                classifyAndRotateIndices(
                    crops,
                    aspectCandidates,
                    classificationMask,
                    rotationStates,
                    "angle_aspect_quick",
                    cancellationSignal
                )
            }

            var recognitionResults = recognizeText(crops, cancellationSignal)
            if (useAngleClassification && recognitionResults.isNotEmpty()) {
                val needsRetry = !classificationMask[0] && recognitionResults[0].confidence < LOW_CONFIDENCE_THRESHOLD
                if (needsRetry) {
                    classifyAndRotateIndices(
                        crops,
                        listOf(0),
                        classificationMask,
                        rotationStates,
                        "angle_confidence_quick",
                        cancellationSignal
                    )
                    val refreshed = recognizeText(crops, cancellationSignal)
                    if (refreshed.isNotEmpty() && refreshed[0].confidence > recognitionResults[0].confidence) {
                        recognitionResults = refreshed
                    }
                }
            }

            return recognitionResults.firstOrNull()
        } finally {
            crops.forEach { image ->
                if (!image.isRecycled) {
                    image.recycle()
                }
            }
        }
    }

    fun hasHighConfidenceText(
        bitmap: Bitmap,
        minimumDetectionConfidence: Float = 0.9f,
        recognitionThreshold: Float = MIN_RECOGNITION_SCORE,
        cancellationSignal: OnnxCancellationSignal? = null
    ): QuickCheckResult {
        val checkCancellation = { cancellationSignal?.ensureActive() }
        val processor = TextDetector(
            detectionSession,
            ortEnv,
            cancellationSignal
        )
        val detectionSummary = processor.collectHighConfidenceDetections(
            bitmap = bitmap,
            minimumDetectionConfidence = minimumDetectionConfidence,
            maxCandidates = QUICK_CHECK_MAX_CANDIDATES
        )
        checkCancellation()

        if (detectionSummary.candidates.isEmpty()) {
            return QuickCheckResult(
                hasText = false,
                detectorHit = false,
                examinedDetections = detectionSummary.examinedDetections,
                candidateCount = 0,
                evaluatedCandidates = 0,
                maxDetectionScore = detectionSummary.maxDetectionScore,
                bestRecognitionScore = null,
                bestRecognitionText = null,
                matchedDetectionScore = null
            )
        }

        var evaluated = 0
        var matched = false
        var matchedDetectionScore: Float? = null
        var bestRecognition: RecognitionResult? = null
        var bestRecognitionScore = Float.NEGATIVE_INFINITY

        for (candidate in detectionSummary.candidates) {
            checkCancellation()
            evaluated++
            val recognition = recognizeCandidate(
                bitmap,
                candidate.box,
                cancellationSignal
            )
            if (recognition != null) {
                if (recognition.confidence > bestRecognitionScore) {
                    bestRecognitionScore = recognition.confidence
                    bestRecognition = recognition
                }
                val meetsThreshold = recognition.confidence >= recognitionThreshold && recognition.text.isNotBlank()
                if (meetsThreshold) {
                    matched = true
                    matchedDetectionScore = candidate.score
                    break
                }
            }
        }

        val bestScore = if (bestRecognitionScore == Float.NEGATIVE_INFINITY) null else bestRecognitionScore
        val bestText = bestRecognition?.text
        return QuickCheckResult(
            hasText = matched,
            detectorHit = true,
            examinedDetections = detectionSummary.examinedDetections,
            candidateCount = detectionSummary.candidates.size,
            evaluatedCandidates = evaluated,
            maxDetectionScore = detectionSummary.maxDetectionScore,
            bestRecognitionScore = bestScore,
            bestRecognitionText = bestText,
            matchedDetectionScore = matchedDetectionScore
        )
    }

    private fun cropTextRegion(bitmap: Bitmap, box: TextBox): Bitmap {
        val orderedPoints = PolygonGeometry.orderPointsClockwise(box.points)
        return ImageUtils.cropTextRegion(bitmap, orderedPoints)
    }

    private fun classifyAndRotateIndices(
        images: MutableList<Bitmap>,
        indices: List<Int>,
        classificationMask: BooleanArray,
        rotationStates: BooleanArray,
        stageLabel: String,
        cancellationSignal: OnnxCancellationSignal? = null
    ) {
        if (!useAngleClassification || indices.isEmpty()) {
            return
        }

        val session = classificationSession
            ?: throw IllegalStateException("Angle classification requested but model not loaded")

        val classifier = TextClassifier(session, ortEnv, cancellationSignal)
        val subset = indices.map { images[it] }
        val outputs = classifier.classifyAndRotate(subset)

        indices.forEachIndexed { idx, imageIndex ->
            classificationMask[imageIndex] = true
            val output = outputs[idx]
            val old = images[imageIndex]
            if (output.rotated) {
                rotationStates[imageIndex] = !rotationStates[imageIndex]
            }
            images[imageIndex] = output.bitmap
            if (output.rotated && output.bitmap !== old && !old.isRecycled) {
                old.recycle()
            }
            saveDebugBitmap(output.bitmap, "crop", imageIndex, stageLabel)
        }
    }

    private fun recognizeText(
        images: List<Bitmap>,
        cancellationSignal: OnnxCancellationSignal? = null
    ): List<RecognitionResult> {
        val recognizer = TextRecognizer(
            recognitionSession,
            ortEnv,
            characterDict,
            cancellationSignal
        )
        return recognizer.recognize(images)
    }

    private fun saveDebugBitmap(bitmap: Bitmap, prefix: String, index: Int, stage: String) {
        val directory = debugOptions.outputDirectory
        if (!debugOptions.saveCrops || directory == null) {
            return
        }

        runCatching {
            if (!directory.exists()) {
                directory.mkdirs()
            }

            val fileName = String.format(Locale.US, "%s_%03d_%s.png", prefix, index, stage)
            val outputFile = File(directory, fileName)
            FileOutputStream(outputFile).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }.onFailure { error ->
            Log.w(DEBUG_TAG, "Failed to save debug bitmap: ${error.message}")
        }
    }

    private fun logDebug(message: String) {
        if (debugOptions.logRecognition) {
            Log.d(DEBUG_TAG, message)
        }
    }

    fun close() {
        detectionSession.close()
        recognitionSession.close()
        classificationSession?.close()
        sessionOptions.close()
    }
}

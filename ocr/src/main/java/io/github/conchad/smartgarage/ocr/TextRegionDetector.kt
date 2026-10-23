// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: takes a ModelSource (file or in-memory asset bytes) instead of downloaded files.
package io.github.conchad.smartgarage.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import io.github.conchad.smartgarage.ocr.internal.TextDetector

/** Detection-only pass: finds text regions without recognising them. Blocking; call off the main thread. */
class TextRegionDetector(detectionModel: ModelSource) {
    companion object {
        private const val MINIMUM_DETECTION_CONFIDENCE = 0.5f
        private const val MAX_REGIONS = 1000
    }

    private val ortEnv = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }
    private val detectionSession = detectionModel.createSession(ortEnv, sessionOptions)

    fun detect(
        bitmap: Bitmap,
        cancellationSignal: OnnxCancellationSignal? = null
    ): List<DetectionCandidate> {
        return TextDetector(detectionSession, ortEnv, cancellationSignal)
            .collectHighConfidenceDetections(
                bitmap = bitmap,
                minimumDetectionConfidence = MINIMUM_DETECTION_CONFIDENCE,
                maxCandidates = MAX_REGIONS
            )
            .candidates
    }

    fun close() {
        detectionSession.close()
        sessionOptions.close()
    }
}

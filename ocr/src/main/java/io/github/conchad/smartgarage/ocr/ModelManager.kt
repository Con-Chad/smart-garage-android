// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: reduced to an offline locator/verifier. All download code (HttpURLConnection,
// BASE_URL, timeouts, temp files, version-marker refresh) is removed: this app never fetches models, it
// ships them. The asset names, SHA-256 digests and sizes are verbatim from upstream.
package io.github.conchad.smartgarage.ocr

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** One required model file with its expected size and SHA-256 (lower-case hex). */
data class ModelAsset(
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long
)

data class ModelFiles(
    val version: String,
    val baseDir: File,
    val detectionModel: File,
    val recognitionModel: File,
    val classificationModel: File,
    val dictionaryFile: File
) {
    fun toOcrModels(): OcrModels = OcrModels(
        detection = ModelSource.FromFile(detectionModel),
        recognition = ModelSource.FromFile(recognitionModel),
        classification = ModelSource.FromFile(classificationModel),
        dictionary = ModelSource.FromFile(dictionaryFile)
    )
}

data class DetectionModelFiles(
    val version: String,
    val baseDir: File,
    val detectionModel: File
)

data class ModelAvailability(
    val version: String,
    val detectorReady: Boolean,
    val recognizerReady: Boolean
)

/**
 * Finds and integrity-checks the PP-OCRv5 model files in [modelDirectory]. Never touches the network.
 * Hashing reads every file in full, so call it off the main thread.
 *
 * Stub for CP4: CP5 decides how the models reach the device (bundled APK assets fed to [OcrModels] as
 * [ModelSource.FromBytes], or copied into [modelDirectory]) and verifies them with [verify].
 */
class ModelManager(private val modelDirectory: File) {

    companion object {
        const val MODEL_VERSION = "pp-ocrv5-202410"
        private const val BUFFER_SIZE = 8 * 1024

        val DETECTION_MODEL = ModelAsset(
            "det.onnx", "d7fe3ea74652890722c0f4d02458b7261d9f5ae6c92904d05707c9eb155c7924", 4_748_769
        )
        val RECOGNITION_MODEL = ModelAsset(
            "rec.onnx", "bf66820f48fa99f779974c4df78e5274a9d8e0458c4137e8c5357e40e2c3faf2", 16_517_247
        )
        val CLASSIFICATION_MODEL = ModelAsset(
            "cls.onnx", "f4bb53707100c5f3d59ba834eb05bb400369f20aed35d4b26807b1bfadd2a70e", 582_663
        )
        val DICTIONARY = ModelAsset(
            "ppocrv5_dict.txt", "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b", 74_012
        )

        val REQUIRED_ASSETS: List<ModelAsset> =
            listOf(DETECTION_MODEL, RECOGNITION_MODEL, CLASSIFICATION_MODEL, DICTIONARY)

        /** True when [bytes] have exactly the expected size and SHA-256 of [asset]. */
        fun verify(asset: ModelAsset, bytes: ByteArray): Boolean =
            bytes.size.toLong() == asset.sizeBytes &&
                computeSha256(bytes.inputStream()).equals(asset.sha256, ignoreCase = true)

        /** True when [file] exists with exactly the expected size and SHA-256 of [asset]. */
        fun verify(asset: ModelAsset, file: File): Boolean =
            file.isFile && file.length() == asset.sizeBytes &&
                file.inputStream().use { computeSha256(it) }.equals(asset.sha256, ignoreCase = true)

        private fun computeSha256(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private fun fileFor(asset: ModelAsset) = File(modelDirectory, asset.fileName)

    /** All four files, or null if any is missing or fails verification. */
    fun locateModels(): ModelFiles? {
        if (!REQUIRED_ASSETS.all { verify(it, fileFor(it)) }) return null
        return ModelFiles(
            version = MODEL_VERSION,
            baseDir = modelDirectory,
            detectionModel = fileFor(DETECTION_MODEL),
            recognitionModel = fileFor(RECOGNITION_MODEL),
            classificationModel = fileFor(CLASSIFICATION_MODEL),
            dictionaryFile = fileFor(DICTIONARY)
        )
    }

    /** The detection model alone, or null if it is missing or fails verification. */
    fun locateDetectionModel(): DetectionModelFiles? {
        val target = fileFor(DETECTION_MODEL)
        if (!verify(DETECTION_MODEL, target)) return null
        return DetectionModelFiles(
            version = MODEL_VERSION,
            baseDir = modelDirectory,
            detectionModel = target
        )
    }

    fun getAvailability(): ModelAvailability {
        val availability = REQUIRED_ASSETS.associateWith { verify(it, fileFor(it)) }
        return ModelAvailability(
            version = MODEL_VERSION,
            detectorReady = availability.getValue(DETECTION_MODEL),
            recognizerReady = REQUIRED_ASSETS.drop(1).all { availability.getValue(it) }
        )
    }
}

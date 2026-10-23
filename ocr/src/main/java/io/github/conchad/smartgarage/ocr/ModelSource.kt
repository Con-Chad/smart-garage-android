package io.github.conchad.smartgarage.ocr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Where a model (or the recognition dictionary) is read from. The pipeline never fetches anything:
 * callers hand it either a verified file on disk or the bytes of a bundled asset.
 */
sealed interface ModelSource {
    class FromFile(val file: File) : ModelSource

    /** For example the bytes of an APK asset read with `context.assets.open(name).readBytes()`. */
    class FromBytes(val bytes: ByteArray) : ModelSource
}

/**
 * The PP-OCRv5 model set the pipeline needs. [classification] is required only when angle
 * classification is enabled.
 */
class OcrModels(
    val detection: ModelSource,
    val recognition: ModelSource,
    val classification: ModelSource?,
    val dictionary: ModelSource
)

internal fun ModelSource.createSession(env: OrtEnvironment, options: OrtSession.SessionOptions): OrtSession =
    when (this) {
        is ModelSource.FromFile -> env.createSession(file.absolutePath, options)
        is ModelSource.FromBytes -> env.createSession(bytes, options)
    }

internal fun ModelSource.readLines(): List<String> =
    when (this) {
        is ModelSource.FromFile -> file.inputStream().use { it.bufferedReader().readLines() }
        is ModelSource.FromBytes -> bytes.inputStream().bufferedReader().use { it.readLines() }
    }

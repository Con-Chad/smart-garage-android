package io.github.conchad.smartgarage.ocr

import android.graphics.Bitmap
import android.net.Uri

/**
 * Fully on-device text recognition. Implementations never touch the network: the models ship in the APK.
 *
 * Every call is safe from the main thread (work runs on a background dispatcher) and calls are serialised.
 * Failures come back as [Result.failure] carrying an [OcrError]; cancelling the calling coroutine cancels the
 * in-flight inference.
 */
interface OcrEngine {
    /** Loads and verifies the models. Optional: [recognize] prepares lazily on first use. */
    suspend fun prepare(): Result<Unit>

    /**
     * Recognises text in [bitmap] (any config; the caller keeps ownership and must not recycle it meanwhile).
     * Coordinates are in [bitmap] pixel space. A blank image yields an empty list.
     */
    suspend fun recognize(bitmap: Bitmap): Result<List<OcrTextBlock>>

    /**
     * Decodes [uri] (`file://` or `content://`), downsamples it to at most 4096 px per side and 12 MP, applies
     * its EXIF orientation and recognises text. Block coordinates are in [OcrImageResult.bitmap] pixel space.
     */
    suspend fun recognize(uri: Uri): Result<OcrImageResult>

    /** Releases the ONNX sessions. The engine stays usable: the next call prepares again. */
    fun close()
}

/** One line of recognised text. */
data class OcrTextBlock(
    val text: String,
    val confidence: Float,
    /** Four corners, clockwise from the top-left. */
    val points: List<OcrPoint>,
    val boundingRect: BoundingRect
)

/**
 * Result of [OcrEngine.recognize] for a [Uri].
 *
 * @property bitmap the decoded, upright (EXIF-applied) image the [blocks] refer to; owned by the caller, who
 *   may display it and should recycle it (or drop the reference) when done.
 * @property width width of [bitmap].
 * @property height height of [bitmap].
 * @property originalWidth full-resolution width of the upright source image before downsampling.
 * @property originalHeight full-resolution height of the upright source image before downsampling.
 */
class OcrImageResult(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val originalWidth: Int,
    val originalHeight: Int,
    val blocks: List<OcrTextBlock>
)

/** Why an [OcrEngine] call failed. */
sealed class OcrError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** A bundled model is missing or does not match its expected size / SHA-256. */
    class CorruptModel(val assetName: String, detail: String) : OcrError("Corrupt OCR model $assetName: $detail")

    /** The image or the models did not fit in memory. */
    class OutOfMemory(cause: Throwable? = null) : OcrError("Out of memory during OCR", cause)

    /** The image could not be opened or decoded. */
    class UnreadableImage(detail: String, cause: Throwable? = null) : OcrError("Unreadable image: $detail", cause)

    /** The request was cancelled before it finished. */
    class Cancelled : OcrError("OCR request was cancelled")

    /** ONNX Runtime or the pipeline failed. */
    class Inference(cause: Throwable) : OcrError("OCR inference failed: ${cause.message}", cause)
}

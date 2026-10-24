package io.github.conchad.smartgarage.feature.receipts.ocr

import android.net.Uri
import io.github.conchad.smartgarage.feature.textscan.levelledBounds
import io.github.conchad.smartgarage.feature.textscan.readingOrder
import io.github.conchad.smartgarage.ocr.OcrEngine
import io.github.conchad.smartgarage.ocr.OcrError
import io.github.conchad.smartgarage.ocr.OcrTextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads a receipt photo on the phone (D-A63): the bundled [OcrEngine] finds the text lines, [ReceiptTextParser]
 * makes fields of them. No network. An interface so the forms' fallback logic is unit-tested without ONNX.
 */
interface ReceiptTextReader {
    suspend fun read(photo: File, locale: Locale = Locale.getDefault()): DeviceRead
}

/** What reading on the phone gave. */
sealed interface DeviceRead {
    /** Text was recognised (possibly none: then [lines] is empty and [receipt] carries `NO_TEXT`). */
    data class Text(val receipt: ParsedReceipt, val lines: List<String>) : DeviceRead {
        val noText: Boolean get() = lines.isEmpty() || ParseWarning.NO_TEXT in receipt.warnings
    }

    data class Failed(val reason: DeviceReadFailure) : DeviceRead
}

enum class DeviceReadFailure {
    /** The photo couldn't be opened or decoded. */
    Unreadable,
    OutOfMemory,
    /** A model failed its checksum or ONNX Runtime failed. */
    EngineFailed,
    /** The screen went away meanwhile; nothing to tell the user. */
    Cancelled,
}

class OcrReceiptTextReader @Inject constructor(
    private val engine: OcrEngine,
    private val parser: ReceiptTextParser,
) : ReceiptTextReader {

    override suspend fun read(photo: File, locale: Locale): DeviceRead {
        val image = engine.recognize(Uri.fromFile(photo)).getOrElse { return DeviceRead.Failed(failureOf(it)) }
        return try {
            val lines = image.blocks.toOcrLines()
            val receipt = withContext(Dispatchers.Default) { parser.parse(lines, locale) }
            DeviceRead.Text(receipt, image.blocks.toTextRows())
        } finally {
            // Only the text is kept; the decoded image (up to 12 MP) goes straight away.
            image.bitmap.recycle()
        }
    }
}

/**
 * The engine's blocks as parser lines in reading order, with each box's centre and height so the parser can pair a
 * label with the amount printed at the far end of the same row. Positions are taken with the photo turned level
 * ([levelledBounds]) and rows come from Scan text's [readingOrder], so on a tilted photo an amount still follows its
 * own label, not the next row's (D-A67).
 */
fun List<OcrTextBlock>.toOcrLines(): List<OcrLine> {
    val blocks = filter { it.text.isNotBlank() }
    val level = levelledBounds(blocks.map { it.points }, blocks.map { it.boundingRect })
    return readingOrder(level).flatten().map { i ->
        val r = level[i]
        OcrLine(
            text = blocks[i].text.trim(),
            confidence = blocks[i].confidence,
            centerX = (r.left + r.right) / 2f,
            centerY = (r.top + r.bottom) / 2f,
            height = r.height,
        )
    }
}

/**
 * The text for the "Text read from the photo" sheet: one line per printed row, its boxes left to right, as Scan
 * text copies it (D-A67).
 */
fun List<OcrTextBlock>.toTextRows(): List<String> {
    val blocks = filter { it.text.isNotBlank() }
    val level = levelledBounds(blocks.map { it.points }, blocks.map { it.boundingRect })
    return readingOrder(level).map { row -> row.joinToString(" ") { blocks[it].text.trim() } }
}

internal fun failureOf(error: Throwable): DeviceReadFailure = when (error) {
    is CancellationException -> throw error
    is OcrError.Cancelled -> DeviceReadFailure.Cancelled
    is OcrError.UnreadableImage -> DeviceReadFailure.Unreadable
    is OcrError.OutOfMemory, is OutOfMemoryError -> DeviceReadFailure.OutOfMemory
    else -> DeviceReadFailure.EngineFailed
}

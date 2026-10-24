package io.github.conchad.smartgarage.feature.textscan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.feature.receipts.ReceiptCapture
import io.github.conchad.smartgarage.feature.receipts.ocr.DeviceReadFailure
import io.github.conchad.smartgarage.feature.receipts.ocr.failureOf
import io.github.conchad.smartgarage.ocr.BoundingRect
import io.github.conchad.smartgarage.ocr.OcrEngine
import io.github.conchad.smartgarage.ocr.OcrImageResult
import io.github.conchad.smartgarage.ocr.OcrPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.roundToInt

/** One recognised line: its text, its corners and bounds in OCR image pixels. */
class ScannedBlock(val text: String, val points: List<OcrPoint>, val bounds: BoundingRect)

/**
 * A read photo (D-A64). [bitmap] is a display copy of the OCR bitmap (long edge at most [PREVIEW_MAX_EDGE]); [width] ×
 * [height] is the OCR image's size, the space [blocks] are in. [rows] is [blocks] in reading order.
 */
class ScanResult(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val blocks: List<ScannedBlock>,
    val rows: List<List<Int>>,
) {
    val image: ImageBitmap = bitmap.asImageBitmap()
    val texts: List<String> = blocks.map { it.text }
}

sealed interface TextScanPhase {
    /** Nothing read yet: pick a source. */
    data object Idle : TextScanPhase
    data object Reading : TextScanPhase
    data class Done(val result: ScanResult) : TextScanPhase
    /** The photo was read and held no text. */
    data object Empty : TextScanPhase
    data class Failed(val reason: DeviceReadFailure) : TextScanPhase
}

enum class TextScanMessage(@StringRes val res: Int) {
    NoCamera(R.string.camera_unavailable),
    NoGallery(R.string.receipt_scan_no_gallery),
    Unusable(R.string.text_scan_unreadable),
}

data class TextScanState(
    val phase: TextScanPhase = TextScanPhase.Idle,
    val selection: TextSelection = TextSelection(0),
    /** The editable text: the selection's (or all) text, until the user types. */
    val text: String = "",
    val message: TextScanMessage? = null,
) {
    val reading: Boolean get() = phase == TextScanPhase.Reading
}

/**
 * The standalone "Scan text" tool (D-A64): a photo from the camera or the gallery, read by the on-device
 * [OcrEngine] (no network), shown with its text boxes to pick from.
 *
 * Owns the display bitmap: it lives as long as this ViewModel (so a rotation keeps it) and is recycled in
 * [onCleared], when the screen is gone for good. A newer scan only drops the older bitmap (the outgoing frame of a
 * cross-fade may still draw it); the GC frees it. The photo file stays in `cache/captures/` meanwhile so a process
 * death can read it again, and is deleted with the ViewModel or when a newer photo replaces it.
 */
@HiltViewModel
class TextScanViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val engine: OcrEngine,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val capture = ReceiptCapture(context, savedStateHandle, CAPTURE_PATH)
    private val form = MutableStateFlow(TextScanState())
    val state: StateFlow<TextScanState> = form.asStateFlow()

    private var job: Job? = null
    private var shown: Bitmap? = null

    private var photo: File?
        get() = savedStateHandle.get<String>(PHOTO_PATH)?.let(::File)
        set(value) = savedStateHandle.set(PHOTO_PATH, value?.absolutePath)

    init {
        // Back after a process death: read the same photo again and put the selection and edits back.
        photo?.takeIf { it.isFile }?.let { file -> read(restore = true) { file } }
    }

    // ── Sources ──────────────────────────────────────────────────────────────────────────────────────────────

    /** A fresh target for `TakePicture`. */
    fun startCamera(): Uri = capture.start()

    /** The camera came back: read the full-size photo (the engine downsamples to what OCR needs, ≤ 12 MP). */
    fun captured(success: Boolean) {
        val file = capture.taken(success) ?: return
        read { file }
    }

    /** A gallery photo: copied into the cache first, so it outlives the picker's grant and a process death. */
    fun picked(uri: Uri) = read { capture.fromGallery(uri) }

    fun cameraUnavailable() = form.update { it.copy(message = TextScanMessage.NoCamera) }
    fun galleryUnavailable() = form.update { it.copy(message = TextScanMessage.NoGallery) }
    fun clearMessage() = form.update { it.copy(message = null) }

    private fun read(restore: Boolean = false, source: suspend () -> File?) {
        job?.cancel()
        val before = form.value.phase.takeIf { it != TextScanPhase.Reading } ?: TextScanPhase.Idle
        form.update { it.copy(phase = TextScanPhase.Reading, message = null) }
        job = viewModelScope.launch {
            val file = source()
            if (file == null) {
                form.update { it.copy(phase = before, message = TextScanMessage.Unusable) }
                return@launch
            }
            photo?.takeIf { it != file }?.delete()
            photo = file
            if (!restore) {
                savedStateHandle.remove<IntArray>(SELECTION)
                savedStateHandle.remove<String>(EDITED_TEXT)
            }
            val image = engine.recognize(Uri.fromFile(file)).getOrElse { error ->
                ensureActive()
                val reason = failureOf(error)
                form.update {
                    it.copy(phase = if (reason == DeviceReadFailure.Cancelled) before else TextScanPhase.Failed(reason))
                }
                return@launch
            }
            ensureActive()
            val result = withContext(Dispatchers.Default) { prepare(image) }
            ensureActive()
            if (result == null) {
                form.update { it.copy(phase = TextScanPhase.Empty, selection = TextSelection(0), text = "") }
                return@launch
            }
            // The older bitmap is only dropped: it may still be on screen while the new result fades in.
            shown = result.bitmap
            val selection = TextSelection.restore(result.blocks.size, savedStateHandle[SELECTION])
            val text = savedStateHandle.get<String>(EDITED_TEXT) ?: assembleText(result.texts, result.rows, selection.selected)
            form.update { it.copy(phase = TextScanPhase.Done(result), selection = selection, text = text) }
        }
    }

    /** Blocks, reading order and a display copy; null (bitmap recycled) when the photo holds no text. */
    private fun prepare(image: OcrImageResult): ScanResult? {
        val blocks = image.blocks.filter { it.text.isNotBlank() }
            .map { ScannedBlock(it.text.trim(), it.points, it.boundingRect) }
        if (blocks.isEmpty()) {
            image.bitmap.recycle()
            return null
        }
        val display = displayCopy(image.bitmap)
        return ScanResult(
            bitmap = display,
            width = image.width,
            height = image.height,
            blocks = blocks,
            rows = readingOrder(blocks.map { it.bounds }),
        )
    }

    // ── Selection and text ───────────────────────────────────────────────────────────────────────────────────

    fun toggle(index: Int) = select { it.toggle(index) }
    fun selectAll() = select { it.selectAll() }
    fun clearSelection() = select { it.clear() }

    /** A new selection rewrites the text from the boxes (any edit made before is replaced). */
    private fun select(change: (TextSelection) -> TextSelection) {
        val result = (form.value.phase as? TextScanPhase.Done)?.result ?: return
        form.update { s ->
            val selection = change(s.selection)
            s.copy(selection = selection, text = assembleText(result.texts, result.rows, selection.selected))
        }
        savedStateHandle[SELECTION] = form.value.selection.selected.toIntArray()
        savedStateHandle.remove<String>(EDITED_TEXT)
    }

    fun setText(text: String) {
        form.update { it.copy(text = text) }
        savedStateHandle[EDITED_TEXT] = text
    }

    override fun onCleared() {
        job?.cancel()
        // The screen is gone (not a rotation): nothing draws this bitmap any more.
        shown?.recycle()
        shown = null
        photo?.delete()
        photo = null
    }

    companion object {
        private const val CAPTURE_PATH = "textscan_capture_path"
        private const val PHOTO_PATH = "textscan_photo_path"
        private const val SELECTION = "textscan_selection"
        private const val EDITED_TEXT = "textscan_edited_text"

        /** Enough for a phone-width overlay at full sharpness; the OCR image itself may be 4096 px. */
        const val PREVIEW_MAX_EDGE = 2048

        /** The OCR bitmap itself when it is small enough, else a scaled copy (and the original recycled). */
        private fun displayCopy(bitmap: Bitmap): Bitmap {
            val edge = max(bitmap.width, bitmap.height)
            if (edge <= PREVIEW_MAX_EDGE) return bitmap
            val factor = PREVIEW_MAX_EDGE.toFloat() / edge
            val copy = bitmap.scale(max(1, (bitmap.width * factor).roundToInt()), max(1, (bitmap.height * factor).roundToInt()))
            if (copy !== bitmap) bitmap.recycle()
            return copy
        }
    }
}

package io.github.conchad.smartgarage.feature.documents.scan

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.github.conchad.smartgarage.ui.files.CameraCapture
import io.github.conchad.smartgarage.ui.files.PickedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The document scanner's state holder (D-A59), owned by the document form's view model: the [ScanSession] (null
 * while the form shows), mirrored into the [SavedStateHandle], and the camera target, as `ReceiptCapture` keeps its
 * own. Photos come from the system camera through `TakePicture` (no CAMERA permission, no camera library).
 */
class ScanController(
    private val context: Context,
    private val handle: SavedStateHandle,
    private val scope: CoroutineScope,
    /** Called with the exported file and the document name once the user taps "Use scan". */
    private val onResult: (PickedFile, String) -> Unit,
) {
    private val flow = MutableStateFlow(ScanSession.decode(handle[SESSION]))
    val session: StateFlow<ScanSession?> = flow.asStateFlow()

    private var capturePath: String?
        get() = handle[CAPTURE]
        set(value) = handle.set(CAPTURE, value)

    init {
        ScanImaging.sweep(context)
    }

    private fun set(value: ScanSession?) {
        flow.value = value
        handle[SESSION] = value?.encode()
    }

    private fun update(change: (ScanSession) -> ScanSession) {
        flow.value?.let { set(change(it)) }
    }

    /** Opens an empty scan named [name]; the screen then launches the camera. */
    fun begin(name: String) {
        if (flow.value == null) set(ScanSession(name = name.take(MAX_NAME)))
    }

    /** A fresh `TakePicture` target; [retake] is the page the photo replaces, null for a new page. */
    fun startCapture(retake: Int? = null): Uri {
        capturePath?.let { File(it).delete() }
        update { it.copy(retakeIndex = retake, failed = false) }
        val target = CameraCapture.newTarget(context)
        capturePath = target.file.absolutePath
        return target.uri
    }

    /** The camera came back. Nothing taken on an empty scan closes the scanner again. */
    fun taken(success: Boolean) {
        val path = capturePath
        capturePath = null
        val current = flow.value ?: return
        if (!success || path == null) {
            path?.let { File(it).delete() }
            if (current.pages.isEmpty()) set(null) else set(current.copy(retakeIndex = null))
            return
        }
        set(current.copy(busy = true))
        scope.launch {
            val page = ScanImaging.prepare(context, File(path))
            val now = flow.value ?: return@launch
            if (page == null) {
                if (now.pages.isEmpty()) set(null) else set(now.copy(busy = false, retakeIndex = null, failed = true))
                return@launch
            }
            now.retakeIndex?.let { i -> now.pages.getOrNull(i)?.let { File(it.path).delete() } }
            set(now.withPhoto(page))
        }
    }

    fun cameraUnavailable() {
        capturePath?.let { File(it).delete() }
        capturePath = null
        val current = flow.value ?: return
        if (current.pages.isEmpty()) set(null) else set(current.copy(retakeIndex = null))
    }

    fun editCorners(index: Int) = update { it.copy(cropIndex = index.takeIf { i -> i in it.pages.indices }) }
    fun setCorners(index: Int, corners: List<Pt>) = update { it.withCorners(index, corners) }
    fun doneCropping() = update { it.copy(cropIndex = null) }
    fun rotate(index: Int) = update { it.rotated(index) }
    fun move(index: Int, delta: Int) = update { it.moved(index, delta) }
    fun setFilter(filter: ScanFilter) = update { it.copy(filter = filter) }
    fun setName(name: String) = update { it.copy(name = name.take(MAX_NAME), failed = false) }

    fun remove(index: Int) {
        val current = flow.value ?: return
        current.pages.getOrNull(index)?.let { File(it.path).delete() }
        set(current.removed(index))
    }

    /** Re-runs the corner guess on a page's photo; the whole photo when nothing is found. */
    fun detect(index: Int) {
        val page = flow.value?.pages?.getOrNull(index) ?: return
        scope.launch {
            val corners = withContext(Dispatchers.Default) { ScanImaging.detect(File(page.path)) }
            setCorners(index, corners ?: ScanGeometry.FULL)
        }
    }

    /** Closes the scanner and deletes its photos. */
    fun discard() {
        flow.value?.let { ScanImaging.discard(it.pages) }
        capturePath?.let { File(it).delete() }
        capturePath = null
        set(null)
    }

    /** Builds the file; on success hands it to the form and closes the scanner. */
    fun finish() {
        val current = flow.value ?: return
        if (!current.canFinish) return
        set(current.copy(exporting = true, failed = false))
        scope.launch {
            val file = ScanImaging.export(context, current.pages, current.filter, current.name)
            if (file == null) {
                update { it.copy(exporting = false, failed = true) }
                return@launch
            }
            ScanImaging.discard(current.pages)
            set(null)
            onResult(file, current.name.trim())
        }
    }

    companion object {
        /** The document title's limit on the server. */
        const val MAX_NAME = 120
        private const val SESSION = "scanSession"
        private const val CAPTURE = "scanCapturePath"
    }
}

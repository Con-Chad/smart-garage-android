package io.github.conchad.smartgarage.feature.receipts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.github.conchad.smartgarage.ui.files.CameraCapture
import io.github.conchad.smartgarage.ui.files.PickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The photo side of a receipt scan, shared by the fill-up, service and expense forms (D-A27, D-A53, D-A56):
 * `TakePicture` writes into `cache/captures/` (as on the document form, D-A23); the target's path survives the
 * process being killed behind the camera app through the [SavedStateHandle]. A photo from the Photo Picker
 * ([fromGallery], D-A56) is copied into the same folder and from there goes through the same downscale and upload.
 */
class ReceiptCapture(private val context: Context, private val handle: SavedStateHandle, private val key: String) {

    private var path: String?
        get() = handle[key]
        set(value) = handle.set(key, value)

    /** A fresh target for `TakePicture`; an earlier unfinished target is discarded. */
    fun start(): Uri {
        path?.let { File(it).delete() }
        CameraCapture.sweep(context)
        val target = CameraCapture.newTarget(context)
        path = target.file.absolutePath
        return target.uri
    }

    /** The camera came back: the photo file to scan when [success], else null (and the empty target is deleted). */
    fun taken(success: Boolean): File? {
        val file = path?.let(::File) ?: return null
        path = null
        if (!success) {
            file.delete()
            return null
        }
        return file
    }

    /**
     * A photo picked from the gallery (D-A56): its bytes copied into a fresh `cache/captures/` file, ready for
     * [finish]. Null when it can't be read or is larger than [MAX_SOURCE_BYTES] (nothing is left behind then).
     */
    suspend fun fromGallery(uri: Uri): File? = withContext(Dispatchers.IO) {
        CameraCapture.sweep(context)
        val target = CameraCapture.newTarget(context).file
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> copyCapped(input, output, MAX_SOURCE_BYTES) }
            } ?: false
        }.getOrDefault(false)
        if (copied) target else null.also { target.delete() }
    }

    /**
     * Downscale (long edge ≤ 1600 px, JPEG 80, EXIF dropped) and describe [file]; null when there is nothing usable
     * or the result is still above the server's receipt limit ([MAX_UPLOAD_BYTES]); the file is deleted then.
     */
    suspend fun finish(file: File): PickedFile? {
        val photo = CameraCapture.finish(context, file, "receipt-${System.currentTimeMillis()}.jpg") ?: return null
        if (!fitsUpload(photo.sizeBytes)) {
            file.delete()
            return null
        }
        return photo
    }

    /** The cache file behind a photo this helper produced, or null for anything else. */
    fun fileOf(photo: PickedFile): File? =
        photo.uri.path?.takeIf { it.contains("/captures/") }?.let { File(context.cacheDir, "captures/${it.substringAfterLast('/')}") }

    companion object {
        /**
         * The receipt-scan upload limit: `RECEIPT-SCANNER.md` allows ≤ 7 MB and ≤ `MAX_IMAGE_SIZE_MB` (default 10);
         * taken as 7 × 10⁶ bytes so either reading of "MB" is met. A 1600 px JPEG at quality 80 is a few hundred KB.
         */
        const val MAX_UPLOAD_BYTES: Long = 7_000_000

        /** A gallery photo bigger than this isn't copied at all (a 200 MP JPEG is well below it). */
        const val MAX_SOURCE_BYTES: Long = 64L * 1024 * 1024

        fun fitsUpload(sizeBytes: Long): Boolean = sizeBytes in 1..MAX_UPLOAD_BYTES

        /** Copies [input] to [output]; false (stopping early) once more than [limit] bytes came in. */
        internal fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Boolean {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return total > 0
                total += n
                if (total > limit) return false
                output.write(buffer, 0, n)
            }
        }
    }
}

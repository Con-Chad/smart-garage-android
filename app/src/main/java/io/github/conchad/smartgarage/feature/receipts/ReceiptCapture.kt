package io.github.conchad.smartgarage.feature.receipts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import io.github.conchad.smartgarage.ui.files.CameraCapture
import io.github.conchad.smartgarage.ui.files.PickedFile
import java.io.File

/**
 * The camera side of a receipt scan, shared by the fill-up and service forms (D-A27, D-A53): `TakePicture` writes
 * into `cache/captures/` (as on the document form, D-A23); the target's path survives the process being killed
 * behind the camera app through the [SavedStateHandle].
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

    /** Downscale and describe [file]; null when the camera left nothing usable. */
    suspend fun finish(file: File): PickedFile? = CameraCapture.finish(context, file, "receipt-${System.currentTimeMillis()}.jpg")

    /** The cache file behind a photo this helper produced, or null for anything else. */
    fun fileOf(photo: PickedFile): File? =
        photo.uri.path?.takeIf { it.contains("/captures/") }?.let { File(context.cacheDir, "captures/${it.substringAfterLast('/')}") }
}

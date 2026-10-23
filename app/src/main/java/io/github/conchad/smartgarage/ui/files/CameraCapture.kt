package io.github.conchad.smartgarage.ui.files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * A photo taken in the app for a receipt or document (D-A23). `TakePicture` writes the camera's full-size JPEG into
 * `cache/captures/` through the app's FileProvider (no CAMERA permission is needed for that contract); the file is
 * then re-encoded in place with its long edge at most [ImageMath.MAX_EDGE_PX] and JPEG quality
 * [ImageMath.JPEG_QUALITY], upright per its EXIF orientation and with the EXIF (location included) dropped. From
 * there it is a [PickedFile] like any other and goes through the same rules and the same upload path.
 */
object CameraCapture {
    private const val DIR = "captures"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    class Target(val uri: Uri, val file: File)

    /** A fresh file for the camera to write, exposed through the FileProvider's `captures` path. */
    fun newTarget(context: Context): Target {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        return Target(uriFor(context, file), file)
    }

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    /**
     * After the camera returned: downscale, then read the result back as a [PickedFile] (name, type sniffed from the
     * bytes, size). Null when the camera left nothing usable; the file is deleted then.
     */
    suspend fun finish(context: Context, file: File, name: String): PickedFile? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L || !downscale(file)) {
            file.delete()
            return@withContext null
        }
        context.contentResolver.inspect(uriFor(context, file))?.copy(name = name)
    }

    /** Deletes captures older than a day: a form that was abandoned mid-way leaves no photos behind. */
    fun sweep(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        File(context.cacheDir, DIR).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /**
     * Re-encodes [file] in place: coarse shrink in the decoder (`inSampleSize`), a final scale to the target size,
     * the EXIF rotation applied to the pixels, then JPEG. The platform `ExifInterface` is enough to read one tag.
     */
    internal fun downscale(file: File, maxEdge: Int = ImageMath.MAX_EDGE_PX, quality: Int = ImageMath.JPEG_QUALITY): Boolean {
        val path = file.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        val options = BitmapFactory.Options().apply { inSampleSize = ImageMath.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge) }
        val decoded = BitmapFactory.decodeFile(path, options) ?: return false
        val (width, height) = ImageMath.targetSize(decoded.width, decoded.height, maxEdge)
        val scaled = if (width != decoded.width || height != decoded.height) decoded.scale(width, height) else decoded
        val matrix = orientationMatrix(exifOrientation(path))
        val upright = if (matrix == null) scaled else Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, matrix, true)
        val temp = File(file.parentFile, "${file.name}.tmp")
        val written = runCatching {
            FileOutputStream(temp).use { out -> upright.compress(Bitmap.CompressFormat.JPEG, quality, out) }
        }.getOrDefault(false)
        if (upright !== scaled) upright.recycle()
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        if (!written) {
            temp.delete()
            return false
        }
        return temp.renameTo(file) || (file.delete() && temp.renameTo(file))
    }

    /** The platform `ExifInterface` reads one tag from a local file fine; the AndroidX one would be a new dependency. */
    @SuppressLint("ExifInterface")
    private fun exifOrientation(path: String): Int = runCatching {
        android.media.ExifInterface(path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, ORIENTATION_NORMAL)
    }.getOrDefault(ORIENTATION_NORMAL)

    // The EXIF orientation values (the same constants in both ExifInterface classes).
    private const val ORIENTATION_NORMAL = 1
    private const val ORIENTATION_FLIP_HORIZONTAL = 2
    private const val ORIENTATION_ROTATE_180 = 3
    private const val ORIENTATION_FLIP_VERTICAL = 4
    private const val ORIENTATION_TRANSPOSE = 5
    private const val ORIENTATION_ROTATE_90 = 6
    private const val ORIENTATION_TRANSVERSE = 7
    private const val ORIENTATION_ROTATE_270 = 8

    private fun orientationMatrix(orientation: Int): Matrix? = when (orientation) {
        ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
        ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
        ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
        ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
        ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
        ORIENTATION_TRANSPOSE -> Matrix().apply { postRotate(90f); postScale(-1f, 1f) }
        ORIENTATION_TRANSVERSE -> Matrix().apply { postRotate(270f); postScale(-1f, 1f) }
        else -> null
    }
}

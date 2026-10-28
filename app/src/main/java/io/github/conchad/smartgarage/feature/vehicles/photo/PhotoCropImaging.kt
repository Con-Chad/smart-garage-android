package io.github.conchad.smartgarage.feature.vehicles.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import io.github.conchad.smartgarage.ui.files.CameraCapture
import io.github.conchad.smartgarage.ui.files.ImageMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The bitmap side of the vehicle photo crop (D-A82), platform APIs only (no uCrop / CanHub): the picked photo decoded
 * upright (EXIF) with its long edge at most [PhotoCropGeometry.MAX_EDGE], and the crop rendered to a JPEG in
 * `cache/captures/` so it goes through the same checks and upload as any picked photo.
 */
object PhotoCropImaging {
    /** The picked photo, upright, long edge ≤ [PhotoCropGeometry.MAX_EDGE]; null when it can't be read. */
    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = ImageMath.sampleSize(bounds.outWidth, bounds.outHeight, PhotoCropGeometry.MAX_EDGE)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
            val orientation = resolver.openInputStream(uri)?.use { CameraCapture.exifOrientation(it) }
            val (width, height) = ImageMath.targetSize(decoded.width, decoded.height, PhotoCropGeometry.MAX_EDGE)
            val matrix = (orientation?.let { CameraCapture.orientationMatrix(it) } ?: Matrix()).apply {
                if (width != decoded.width || height != decoded.height) preScale(width / decoded.width.toFloat(), height / decoded.height.toFloat())
            }
            if (matrix.isIdentity) {
                decoded
            } else {
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
            }
        }.getOrNull()
    }

    /**
     * The crop of [source] (the upright photo from [load]) turned by [CropState.turns], as a JPEG in
     * `cache/captures/`; null when it can't be written. The caller deletes the file once it is queued.
     */
    suspend fun render(context: Context, source: Bitmap, state: CropState, aspect: Float): File? = withContext(Dispatchers.Default) {
        val (turnedWidth, turnedHeight) = PhotoCropGeometry.turnedSize(source.width, source.height, state.turns)
        val rect = PhotoCropGeometry.cropRect(state, turnedWidth.toFloat(), turnedHeight.toFloat(), aspect)
        val (x, y, w, h) = PhotoCropGeometry.pixelBounds(PhotoCropGeometry.toSource(rect, source.width, source.height, state.turns), source.width, source.height)
        val turn = PhotoCropGeometry.normalTurns(state.turns)
        val matrix = Matrix().apply { if (turn != 0) postRotate(90f * turn) }
        val file = CameraCapture.newTarget(context).file
        val cropped = runCatching { Bitmap.createBitmap(source, x, y, w, h, matrix, true) }.getOrNull() ?: return@withContext null
        val written = runCatching {
            file.outputStream().use { cropped.compress(Bitmap.CompressFormat.JPEG, PhotoCropGeometry.JPEG_QUALITY, it) }
        }.getOrDefault(false)
        if (cropped !== source) cropped.recycle()
        if (written && file.length() > 0) file else file.delete().let { null }
    }
}

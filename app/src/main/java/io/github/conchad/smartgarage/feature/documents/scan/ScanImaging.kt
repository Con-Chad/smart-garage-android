package io.github.conchad.smartgarage.feature.documents.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import io.github.conchad.smartgarage.ui.files.CameraCapture
import io.github.conchad.smartgarage.ui.files.FileRules
import io.github.conchad.smartgarage.ui.files.ImageMath
import io.github.conchad.smartgarage.ui.files.PickedFile
import io.github.conchad.smartgarage.ui.files.inspect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * The bitmap side of the document scanner (D-A59), all platform APIs: the photo kept upright at
 * [ScanGeometry.SOURCE_EDGE], the page flattened with `Matrix.setPolyToPoly`, the filters (a `ColorMatrix` for
 * greyscale, [ScanFilters.adaptiveThreshold] for B&W) and the export, one page as JPEG, several as a [JpegPdf].
 */
object ScanImaging {
    private const val DIR = "scans"
    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    private const val SOURCE_QUALITY = 92
    private const val DETECT_EDGE = 256

    /** JPEG quality and page long edge for each export attempt; a file over the limit is retried smaller. */
    private val ATTEMPTS = listOf(85 to ScanGeometry.MAX_PAGE_EDGE, 75 to 1700, 65 to 1300)

    fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    /** Deletes scan photos older than a day, as `CameraCapture.sweep` does for captures. */
    fun sweep(context: Context) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        File(context.cacheDir, DIR).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /**
     * The camera's photo made upright (EXIF), EXIF dropped, long edge ≤ [ScanGeometry.SOURCE_EDGE], moved into
     * `cache/scans/`; with its guessed corners. Null (and the photo deleted) when it can't be read.
     */
    suspend fun prepare(context: Context, photo: File): ScanPage? = withContext(Dispatchers.IO) {
        if (!photo.exists() || photo.length() == 0L || !CameraCapture.downscale(photo, ScanGeometry.SOURCE_EDGE, SOURCE_QUALITY)) {
            photo.delete()
            return@withContext null
        }
        val target = File(dir(context), "${UUID.randomUUID()}.jpg")
        if (!photo.renameTo(target)) {
            runCatching { photo.copyTo(target, overwrite = true) }
            photo.delete()
        }
        if (!target.exists()) return@withContext null
        ScanPage(target.absolutePath, detect(target) ?: ScanGeometry.insetCorners())
    }

    /** [ScanFilters.detectCorners] on a ~256 px greyscale copy. */
    fun detect(file: File): List<Pt>? {
        val bitmap = decode(file, DETECT_EDGE) ?: return null
        val small = fitWithin(bitmap, DETECT_EDGE)
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        val corners = ScanFilters.detectCorners(ScanFilters.luminance(pixels), small.width, small.height)
        if (small !== bitmap) small.recycle()
        bitmap.recycle()
        return corners
    }

    /** The photo decoded with its long edge near [maxEdge] (power-of-two sampling, then at most a 2:1 scale). */
    fun decode(file: File, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    private fun fitWithin(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val (w, h) = ImageMath.targetSize(bitmap.width, bitmap.height, maxEdge)
        return if (w == bitmap.width && h == bitmap.height) bitmap else bitmap.scale(w, h)
    }

    /** The photo for the crop screen. */
    suspend fun preview(path: String): Bitmap? = withContext(Dispatchers.IO) {
        decode(File(path), ScanGeometry.PREVIEW_EDGE)?.let { fitWithin(it, ScanGeometry.PREVIEW_EDGE) }
    }

    /** The flattened, filtered page with its long edge at most [maxEdge]; null when the photo is gone. */
    suspend fun render(page: ScanPage, filter: ScanFilter, maxEdge: Int): Bitmap? = withContext(Dispatchers.Default) {
        // Decode a little above the output size so the warp only ever shrinks.
        val source = decode(File(page.path), minOf(ScanGeometry.SOURCE_EDGE, maxEdge + maxEdge / 4)) ?: return@withContext null
        try {
            flatten(source, page, filter, maxEdge)
        } finally {
            source.recycle()
        }
    }

    private fun flatten(source: Bitmap, page: ScanPage, filter: ScanFilter, maxEdge: Int): Bitmap {
        val corners = page.corners.takeIf { ScanGeometry.isUsable(it) } ?: ScanGeometry.FULL
        val ordered = ScanGeometry.rotate(ScanGeometry.orderCorners(ScanGeometry.toPixels(corners, source.width, source.height)), page.turns)
        val (width, height) = ScanGeometry.outputSize(ordered, maxEdge)
        val (src, dst) = ScanGeometry.polyToPoly(ordered, width, height)
        val matrix = Matrix()
        val out = createBitmap(width, height)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (filter != ScanFilter.Original) paint.colorFilter = ColorMatrixColorFilter(greyscaleMatrix())
        if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) canvas.drawBitmap(source, matrix, paint)
        if (filter == ScanFilter.BlackWhite) {
            val pixels = IntArray(width * height)
            out.getPixels(pixels, 0, width, 0, 0, width, height)
            val bw = ScanFilters.adaptiveThreshold(ScanFilters.luminance(pixels), width, height)
            for (i in pixels.indices) pixels[i] = if (bw[i] == 0) Color.BLACK else Color.WHITE
            out.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return out
    }

    /** Greyscale with a gentle contrast lift (×1.2 around mid-grey), so paper reads lighter and ink darker. */
    private fun greyscaleMatrix(): ColorMatrix {
        val grey = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.2f
        val shift = 128f * (1 - contrast)
        grey.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, shift,
                    0f, contrast, 0f, 0f, shift,
                    0f, 0f, contrast, 0f, shift,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        return grey
    }

    /**
     * The scan as one file in `cache/captures/` (so the form's upload and clean-up treat it like a photo taken
     * there), named after [name]: a JPEG for one page, else a PDF. Retried smaller while above the server's
     * document limit; null when it still doesn't fit or a page can't be rendered.
     */
    suspend fun export(context: Context, pages: List<ScanPage>, filter: ScanFilter, name: String): PickedFile? = withContext(Dispatchers.Default) {
        if (pages.isEmpty()) return@withContext null
        val pdf = ScanGeometry.isPdf(pages.size)
        val target = CameraCapture.newTarget(context).file.let { File(it.parentFile, it.nameWithoutExtension + if (pdf) ".pdf" else ".jpg") }
        for ((quality, edge) in ATTEMPTS) {
            val written = runCatching {
                target.outputStream().use { out ->
                    if (!pdf) {
                        out.write(encodePage(pages.single(), filter, edge, quality)?.first ?: return@runCatching false)
                    } else {
                        val pdfPages = pages.map { page ->
                            val (bytes, size) = encodePage(page, filter, edge, quality) ?: return@runCatching false
                            val (w, h) = ScanGeometry.pdfPageSize(size.first, size.second)
                            JpegPdf.Page(bytes, w, h)
                        }
                        JpegPdf.write(pdfPages, name.trim(), out)
                    }
                }
                true
            }.getOrDefault(false)
            if (!written) break
            if (target.length() in 1..FileRules.MAX_DOCUMENT_BYTES) {
                val described = withContext(Dispatchers.IO) { context.contentResolver.inspect(CameraCapture.uriFor(context, target)) }
                return@withContext described?.copy(name = ScanGeometry.fileName(name, pages.size))
            }
        }
        target.delete()
        null
    }

    /** One page as JPEG bytes and its pixel size. */
    private suspend fun encodePage(page: ScanPage, filter: ScanFilter, edge: Int, quality: Int): Pair<ByteArray, Pair<Int, Int>>? {
        val bitmap = render(page, filter, edge) ?: return null
        return try {
            val bytes = ByteArrayOutputStream().use { buffer ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
                buffer.toByteArray()
            }
            bytes to (bitmap.width to bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** Deletes the scan's source photos. */
    fun discard(pages: List<ScanPage>) {
        pages.forEach { File(it.path).delete() }
    }
}

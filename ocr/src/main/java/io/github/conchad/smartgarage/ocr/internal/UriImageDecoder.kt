// Ported from ente/mobile_ocr (https://github.com/ente/mobile_ocr) @ 0c68f88
// Copyright (c) 2025 Laurens Priem. Licensed under the MIT License (see OPEN_SOURCE_NOTICES.md).
//
// Changes from upstream: MobileOcrPlugin.decodeImage reads a Uri through a ContentResolver (file:// and
// content://) instead of a file path; the EXIF matrix comes from the pure ExifOrientation mapping; failures
// raise OcrError.UnreadableImage; the decoded size is reported from the actual bitmap.
package io.github.conchad.smartgarage.ocr.internal

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import io.github.conchad.smartgarage.ocr.OcrError
import java.io.IOException

internal class DecodedImage(
    val bitmap: Bitmap,
    /** Full-resolution upright size before downsampling. */
    val originalWidth: Int,
    val originalHeight: Int
)

internal object UriImageDecoder {
    const val MAX_DIMENSION = 4096
    const val MAX_PIXELS = 12_000_000L

    fun decode(resolver: ContentResolver, uri: Uri): DecodedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw OcrError.UnreadableImage("cannot read image bounds")
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIMENSION, MAX_PIXELS)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val source = open(resolver, uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw OcrError.UnreadableImage("cannot decode image")

        val orientation = runCatching {
            open(resolver, uri).use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val transform = ExifOrientation.transformFor(orientation)

        val upright = try {
            applyOrientation(source, transform)
        } catch (error: Throwable) {
            source.recycle()
            throw error
        }
        if (upright !== source) source.recycle()

        return DecodedImage(
            bitmap = upright,
            originalWidth = if (transform.swapsDimensions) bounds.outHeight else bounds.outWidth,
            originalHeight = if (transform.swapsDimensions) bounds.outWidth else bounds.outHeight
        )
    }

    private fun open(resolver: ContentResolver, uri: Uri) =
        try {
            resolver.openInputStream(uri) ?: throw OcrError.UnreadableImage("no stream")
        } catch (error: IOException) {
            throw OcrError.UnreadableImage("cannot open image", error)
        } catch (error: SecurityException) {
            throw OcrError.UnreadableImage("no permission to read image", error)
        } catch (error: IllegalArgumentException) {
            throw OcrError.UnreadableImage("unsupported uri", error)
        }

    private fun applyOrientation(source: Bitmap, transform: OrientationTransform): Bitmap {
        if (transform.isIdentity) return source
        val matrix = Matrix().apply { setValues(transform.matrixValues()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}

package io.github.conchad.smartgarage.ui.glass

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import java.util.Random

/**
 * A full-resolution dither over the aurora (D-A87). The aurora is rasterised at 1/4 of the screen in an 8-bit layer
 * and scaled up, so each 8-bit step of its slow, dark gradients (the top of the page) becomes a 4×-magnified contour
 * that reads as banding. A repeating tile of signed noise — each pixel a white or black wash of 0–3/255 — breaks those
 * contours at screen resolution: about ±2 levels of grain, below what the eye sees as texture, and well under a level
 * of mean shift. Plain SrcOver over one static rect: no blend-mode readback, no recomposition, drawn once per frame
 * the parent is.
 */
fun Modifier.auroraDither(): Modifier = drawWithCache {
    val brush = ShaderBrush(ImageShader(ditherTile, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind { drawRect(brush) }
}

/** Signed triangular noise in −[DITHER_AMPLITUDE]..[DITHER_AMPLITUDE] per pixel (non-premultiplied ARGB). */
internal fun ditherPixels(size: Int, seed: Long): IntArray {
    val random = Random(seed)
    return IntArray(size * size) {
        val n = random.nextInt(DITHER_AMPLITUDE + 1) - random.nextInt(DITHER_AMPLITUDE + 1)
        when {
            n > 0 -> (n shl 24) or 0xFFFFFF
            n < 0 -> (-n) shl 24
            else -> 0
        }
    }
}

private val ditherTile by lazy {
    Bitmap.createBitmap(ditherPixels(DITHER_TILE, 0x5EEDL), DITHER_TILE, DITHER_TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

private const val DITHER_TILE = 128

/** Peak alpha (of 255) of a noise pixel's white or black wash. */
internal const val DITHER_AMPLITUDE = 3

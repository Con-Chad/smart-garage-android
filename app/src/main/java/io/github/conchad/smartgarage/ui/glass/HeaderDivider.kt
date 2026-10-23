package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The header divider (D-A60): a 1 dp line along the bottom edge of the shared top bar, in the complement of the
 * aurora's colour, so the header's boundary reads clearly whatever the theme colour.
 */
object HeaderDivider {
    /** Degrees the divider's hue sits from the aurora tint's; 180 is the true complement. Tweak here. */
    const val HUE_OFFSET = 180f
    /** Saturation clamp: a neutral (steel) tint still gets a definite accent, a vivid one doesn't shout. */
    const val SAT_MIN = 0.65f
    const val SAT_MAX = 0.85f
    /** Lightness on the dark header (light enough to stand off #08090D) and on the light theme's pale header. */
    const val LIGHTNESS_DARK = 0.64f
    const val LIGHTNESS_LIGHT = 0.42f
    /** Opacity of the line; opaque, so the 1 dp edge stays clear over bright auroras. */
    const val ALPHA = 1f
}

/** The divider colour for [tint]: hue rotated by [HeaderDivider.HUE_OFFSET], saturation and lightness tuned for [dark]. */
fun complementaryAccent(tint: Color, dark: Boolean): Color {
    val r = tint.red
    val g = tint.green
    val b = tint.blue
    val hi = maxOf(r, g, b)
    val lo = minOf(r, g, b)
    val chroma = hi - lo
    val l = (hi + lo) / 2f
    val sat = if (chroma == 0f) 0f else chroma / (1f - abs(2f * l - 1f))
    val hue = when {
        chroma == 0f -> 0f
        hi == r -> 60f * (((g - b) / chroma) % 6f)
        hi == g -> 60f * ((b - r) / chroma + 2f)
        else -> 60f * ((r - g) / chroma + 4f)
    }
    return Color.hsl(
        hue = ((hue + HeaderDivider.HUE_OFFSET) % 360f + 360f) % 360f,
        saturation = sat.coerceIn(HeaderDivider.SAT_MIN, HeaderDivider.SAT_MAX),
        lightness = if (dark) HeaderDivider.LIGHTNESS_DARK else HeaderDivider.LIGHTNESS_LIGHT,
        alpha = HeaderDivider.ALPHA,
    )
}

/**
 * Draws the header divider over the bottom edge of this (the top bar) while [visible]: content has scrolled under the
 * bar, or a menu (the Home fan) is open over the page; at the top of a page there's nothing to divide, so it's hidden.
 * Fades in and out over [Motion.SHORT_MS] and cross-fades when the aurora colour changes; both are read in the draw
 * phase only, so the fades repaint without recomposing. Callers pass a threshold flag (a derived state), not the raw
 * scroll offset, so scrolling itself causes no work. The line is a whole number of pixels on a whole-pixel row, so it
 * stays crisp, and it's drawn on top of the bar's content in its own layout bounds, which a scrolling list under the
 * pinned bar doesn't move.
 */
@Composable
fun Modifier.headerDivider(visible: Boolean): Modifier {
    val tint = LocalAuroraTint.current.effective ?: MaterialTheme.colorScheme.primary
    val target = complementaryAccent(tint, glass.dark)
    val reduced = rememberReducedMotion()
    val color = animateColorAsState(target, tween<Color>(Motion.MEDIUM_MS).orSnap(reduced), label = "headerDivider")
    val alpha = animateFloatAsState(if (visible) 1f else 0f, tween<Float>(Motion.SHORT_MS).orSnap(reduced), label = "headerDividerAlpha")
    return this.drawWithContent {
        drawContent()
        val a = alpha.value
        if (a > 0f) {
            val stroke = max(1f, 1.dp.toPx().roundToInt().toFloat())
            val top = (size.height - stroke).roundToInt().toFloat()
            drawRect(color.value, topLeft = Offset(0f, top), size = Size(size.width, stroke), alpha = a)
        }
    }
}

package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The top edge of a page's scrolling body (D-A76, replacing the header divider of D-A60 and the bar's scrolled
 * tint): content scrolled up under the top bar fades out row by row, fully transparent at the mask's top edge and
 * fully opaque [length] below it ([GlassTokens.contentFade]) on a smoothstep ramp, so a card partway into the band
 * is crisp at its bottom and faint at its top, and the band has no visible start or end line.
 *
 * [lift] ([GlassTokens.contentFadeLift]) extends the scroll container that far up under the header, so the
 * transparent edge sits inside the bar, just under its title, rather than at the bar's bottom right above the
 * first card. The caller adds the same [lift] as top content padding (the `LazyColumn`'s `contentPadding`, or a
 * `padding(top = lift)` after `verticalScroll`) so the layout at rest is unchanged. Pass 0 dp where fixed controls
 * (filter chips, tabs) sit between the bar and the list: the container would otherwise draw over them.
 *
 * Apply once to the scroll container itself (before `verticalScroll`, or on the `LazyColumn`), never per item. The
 * mask is static in the container's bounds: an offscreen layer and one `DstOut` rect whose brush is rebuilt only
 * when the size changes. Its strength follows the scroll offset (0 at rest, so the first item is never faded there;
 * full by the time content reaches the container's top edge, i.e. after [lift], at least [MIN_RAMP], so the edge
 * never shows as a cut), read in the layer and draw phases only, so scrolling never recomposes. At rest the layer
 * is dropped (no offscreen pass).
 */
@Composable
fun Modifier.topFadeEdge(state: ScrollState, lift: Dp = 0.dp, length: Dp = glass.contentFade): Modifier =
    topFadeEdge(length, lift) { (state.value / ramp(lift)).coerceIn(0f, 1f) }

/** [topFadeEdge] for a `LazyColumn`: full strength once the first item has scrolled off. */
@Composable
fun Modifier.topFadeEdge(state: LazyListState, lift: Dp = 0.dp, length: Dp = glass.contentFade): Modifier =
    topFadeEdge(length, lift) {
        if (state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / ramp(lift)).coerceIn(0f, 1f)
    }

/**
 * The mask behind [topFadeEdge], for a caller that drives its own [strength] (0..1, read in the layer and draw
 * phases only). At 0 the content draws as it would without it, with no offscreen layer.
 */
fun Modifier.topFadeEdge(length: Dp, lift: Dp = 0.dp, strength: Density.() -> Float): Modifier = this
    .liftUnderHeader(lift)
    .graphicsLayer { compositingStrategy = if (strength() > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
    .drawWithCache {
        val l = length.toPx()
        // Erases (DstOut) by 1 − smoothstep(t): fully at the top edge, not at all at [length], easing in and out.
        val stops = Array(FADE_STOPS + 1) { i ->
            val t = i.toFloat() / FADE_STOPS
            t to Color.Black.copy(alpha = 1f - t * t * (3f - 2f * t))
        }
        val mask = Brush.verticalGradient(*stops, startY = 0f, endY = l)
        val band = Size(size.width, l)
        onDrawWithContent {
            drawContent()
            val a = strength()
            if (a > 0f) drawRect(mask, size = band, alpha = a, blendMode = BlendMode.DstOut)
        }
    }

private const val FADE_STOPS = 8

/** The least scroll over which the fade comes in, where nothing is lifted under the bar. */
private val MIN_RAMP = 8.dp

private fun Density.ramp(lift: Dp): Float = maxOf(lift, MIN_RAMP).toPx()

/** Measures the content [lift] taller and places it [lift] higher, reporting the original size to the parent. */
private fun Modifier.liftUnderHeader(lift: Dp): Modifier = if (lift == 0.dp) this else layout { measurable, constraints ->
    if (!constraints.hasBoundedHeight) {
        val p = measurable.measure(constraints)
        return@layout layout(p.width, p.height) { p.place(0, 0) }
    }
    val l = lift.roundToPx()
    val p = measurable.measure(constraints.copy(minHeight = constraints.minHeight + l, maxHeight = constraints.maxHeight + l))
    layout(p.width, (p.height - l).coerceAtLeast(0)) { p.place(0, -l) }
}

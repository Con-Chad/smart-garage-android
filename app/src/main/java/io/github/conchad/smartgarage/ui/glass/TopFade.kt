package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
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
 * mask is an offscreen layer and one `DstOut` rect whose brush is rebuilt only when the size changes. It follows
 * the scroll offset continuously (D-A84): at rest there is no mask (and no offscreen pass), so the first item is
 * never faded; as the content scrolls, the band slides down from above the container's top edge by as much as the
 * content has moved (its opaque end at `lift + scrolled`), and settles in place once that reaches [length]. A card
 * nudged up by a few pixels therefore loses only a sliver of its top, never the whole band at once. The offset is
 * read in the layer and draw phases only, so scrolling never recomposes.
 */
@Composable
fun Modifier.topFadeEdge(state: ScrollState, lift: Dp = 0.dp, length: Dp = glass.contentFade): Modifier =
    topFadeEdge(length, lift) { state.value.toFloat() }

/**
 * [topFadeEdge] for a `LazyColumn`. The scrolled distance is item 0's offset while it is first, then the remembered
 * spacing between the leading items plus the first visible one's offset ([lazyScrolledPx]), so a short first item
 * (a group header) does not snap the band in when it scrolls off.
 */
@Composable
fun Modifier.topFadeEdge(state: LazyListState, lift: Dp = 0.dp, length: Dp = glass.contentFade): Modifier {
    val steps = remember(state) { IntArray(LAZY_STEPS) { -1 } }
    return topFadeEdge(length, lift) {
        val items = state.layoutInfo.visibleItemsInfo
        for (i in 0 until items.size - 1) {
            val index = items[i].index
            if (index in 0 until LAZY_STEPS && items[i + 1].index == index + 1) steps[index] = items[i + 1].offset - items[i].offset
        }
        lazyScrolledPx(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, steps)
    }
}

/**
 * The mask behind [topFadeEdge], for a caller that drives its own [scrolled] distance in px (read in the layer and
 * draw phases only). At 0 the content draws as it would without it, with no offscreen layer.
 */
fun Modifier.topFadeEdge(length: Dp, lift: Dp = 0.dp, scrolled: Density.() -> Float): Modifier = this
    .liftUnderHeader(lift)
    .graphicsLayer { compositingStrategy = if (scrolled() > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
    .drawWithCache {
        val l = length.toPx()
        val liftPx = lift.toPx()
        // Erases (DstOut) by 1 − smoothstep(t): fully at the top edge, not at all at [length], easing in and out.
        val stops = Array(FADE_STOPS + 1) { i ->
            val t = i.toFloat() / FADE_STOPS
            t to Color.Black.copy(alpha = 1f - t * t * (3f - 2f * t))
        }
        val mask = Brush.verticalGradient(*stops, startY = 0f, endY = l)
        val band = Size(size.width, l)
        onDrawWithContent {
            drawContent()
            val s = scrolled()
            if (s > 0f) {
                translate(top = fadeBandTop(s, liftPx, l)) { drawRect(mask, size = band, blendMode = BlendMode.DstOut) }
            }
        }
    }

/**
 * Where the fade band's top edge sits (px, ≤ 0) after [scrolledPx] of scroll: its opaque end tracks `lift + scrolled`
 * until that reaches [lengthPx], then it stays put at 0. Only meaningful for [scrolledPx] > 0 (at rest, no band).
 */
internal fun fadeBandTop(scrolledPx: Float, liftPx: Float, lengthPx: Float): Float =
    (liftPx + scrolledPx.coerceAtLeast(0f)).coerceAtMost(lengthPx) - lengthPx

/**
 * How far a lazy list has scrolled (px): the distance from item 0's start to the first visible item's start, from
 * the remembered [steps] (`steps[i]` = item i+1's offset − item i's, −1 if never seen together), plus the first
 * visible item's own offset. [Float.POSITIVE_INFINITY] (band fully in) if the list jumped past items never laid out
 * together, or past the first [LAZY_STEPS].
 */
internal fun lazyScrolledPx(firstIndex: Int, firstOffset: Int, steps: IntArray): Float {
    if (firstIndex >= steps.size) return Float.POSITIVE_INFINITY
    var total = firstOffset.toFloat()
    for (i in 0 until firstIndex) {
        if (steps[i] < 0) return Float.POSITIVE_INFINITY
        total += steps[i]
    }
    return total
}

/** Leading items whose spacing is remembered; past this many the band is always fully in. */
private const val LAZY_STEPS = 8

/**
 * Linear segments approximating the smoothstep ramp. D-A87: 24 (was 8) so the slope changes between segments are
 * too small to read as faint lines across a card in the band.
 */
private const val FADE_STOPS = 24

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

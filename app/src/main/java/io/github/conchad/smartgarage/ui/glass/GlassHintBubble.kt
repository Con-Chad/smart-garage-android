package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * A small frosted-glass explanation that pops up above [content] while [visible] (D-A69, the HSN and TSN fields of
 * the Add vehicle form): raised glass ([GlassTokens.frost] with the bevelled rim, gloss and lift shadow of D-A52)
 * with a tail pointing at the middle of [content], an info icon and [title] over [text].
 *
 * It floats in a non-focusable popup, so the field keeps focus and the keyboard; it never pushes the form about.
 * Centred over [content], kept [EDGE_MARGIN] inside the window, and flipped below it when there is no room above
 * (a field scrolled up under the top bar). It grows out of its tail on [Motion.gentleSpring] and fades; under
 * reduced motion it just appears. A tap on the bubble puts it away until [visible] next turns on; it also goes when
 * [visible] turns off (the caller ties it to focus). TalkBack announces it politely as it appears.
 *
 * A popup is its own window, so a drag that starts on it would never reach the form's scroll (D-A72). The anchor
 * therefore carries a [NestedScrollDispatcher] in the form's layout tree, and vertical drags (and their fling) on the
 * bubble are handed to it: the scrolling parent takes them as a child's leftover scroll, exactly as if the finger had
 * landed on the field. A tap still puts the bubble away (the drag only starts past touch slop).
 */
@Composable
fun GlassHintBubble(
    visible: Boolean,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var dismissed by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) dismissed = false }
    val state = remember { MutableTransitionState(false) }
    state.targetState = visible && !dismissed
    val scrollDispatcher = remember { NestedScrollDispatcher() }
    Box(modifier.nestedScroll(NoNestedScroll, scrollDispatcher)) {
        content()
        if (state.currentState || state.targetState) {
            val density = LocalDensity.current
            val tailX = remember { mutableFloatStateOf(Float.NaN) }
            val below = remember { mutableStateOf(false) }
            val provider = remember(density) {
                BubblePosition(
                    gap = with(density) { BUBBLE_GAP.roundToPx() },
                    margin = with(density) { EDGE_MARGIN.roundToPx() },
                    tailX = tailX,
                    below = below,
                )
            }
            Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
                HintBubbleBody(state, title, text, tailX.floatValue, below.value, scrollDispatcher, onDismiss = { dismissed = true })
            }
        }
    }
}

@Composable
private fun HintBubbleBody(
    state: MutableTransitionState<Boolean>,
    title: String,
    text: String,
    tailX: Float,
    below: Boolean,
    scrollDispatcher: NestedScrollDispatcher,
    onDismiss: () -> Unit,
) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    val shape = remember(tailX, below) { BubbleShape(tokens.radiusMedium, TAIL_HEIGHT, tailX, below) }
    // Grows out of its tail: the pivot is the tail's tip.
    var width by remember { mutableFloatStateOf(0f) }
    val origin = TransformOrigin(if (width > 0f && !tailX.isNaN()) (tailX / width).coerceIn(0f, 1f) else 0.5f, if (below) 0f else 1f)
    // Vertical drags on the bubble scroll the form under it (D-A72): handed to the anchor's nested-scroll parents.
    val dragState = rememberDraggableState { delta ->
        val available = Offset(0f, delta)
        val pre = scrollDispatcher.dispatchPreScroll(available, NestedScrollSource.UserInput)
        scrollDispatcher.dispatchPostScroll(Offset.Zero, available - pre, NestedScrollSource.UserInput)
    }
    AnimatedVisibility(
        visibleState = state,
        enter = (fadeIn(tween(Motion.SHORT_MS)) + scaleIn(Motion.gentleSpring(), initialScale = BUBBLE_FROM, transformOrigin = origin)).orNone(reduced),
        exit = (fadeOut(tween(Motion.QUICK_MS)) + scaleOut(tween(Motion.QUICK_MS), targetScale = BUBBLE_FROM, transformOrigin = origin)).orNone(reduced),
    ) {
        Column(
            Modifier
                // The popup's size: the bubble plus its shadow room, which the tail position is measured against.
                .onSizeChanged { width = it.width.toFloat() }
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val available = Velocity(0f, velocity)
                        val pre = scrollDispatcher.dispatchPreFling(available)
                        scrollDispatcher.dispatchPostFling(Velocity.Zero, available - pre)
                    },
                )
                .padding(BUBBLE_SHADOW_ROOM)
                .widthIn(max = BUBBLE_MAX_WIDTH)
                .glassShadow(shape, elevation = 8.dp, color = tokens.shadow)
                .clip(shape)
                // Over the form's fields and labels: the page colour under the frost, so nothing reads through the
                // explanation (the glass menus' rule, D-A31).
                .drawBehind { drawRect(tokens.pageBackground.copy(alpha = BUBBLE_UNDERLAY_ALPHA)) }
                .bevelFill(tokens, tokens.frost)
                .bevelRim(tokens, shape, width = 1.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = 10.dp + if (below) TAIL_HEIGHT else 0.dp,
                    bottom = 10.dp + if (below) 0.dp else TAIL_HEIGHT,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(18.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = tokens.textPrimary,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Puts the bubble [gap] above the anchor (below when it would leave the window's top), centred on it and kept
 * [margin] inside the window; reports where the anchor's middle falls in the bubble so the tail points there.
 */
private class BubblePosition(
    private val gap: Int,
    private val margin: Int,
    private val tailX: MutableFloatState,
    private val below: MutableState<Boolean>,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val centre = anchorBounds.center.x
        val maxX = (windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
        val x = (centre - popupContentSize.width / 2).coerceIn(margin, maxX)
        val above = anchorBounds.top - gap - popupContentSize.height
        val flip = above < margin
        val y = if (flip) anchorBounds.bottom + gap else above
        tailX.floatValue = (centre - x).toFloat()
        below.value = flip
        return IntOffset(x, y)
    }
}

/**
 * The bubble's outline: a rounded rectangle with a [tail] tall triangle on its bottom edge ([below] false) or top
 * edge, its tip at [tailX] (clamped clear of the corners). [tailX] is measured in the popup, which pads the bubble
 * by [BUBBLE_SHADOW_ROOM], hence the shift.
 */
private class BubbleShape(private val radius: Dp, private val tail: Dp, private val tailX: Float, private val below: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val r = radius.toPx()
        val t = tail.toPx()
        val halfBase = t
        val top = if (below) t else 0f
        val bottom = if (below) size.height else size.height - t
        val body = Path().apply { addRoundRect(RoundRect(0f, top, size.width, bottom, CornerRadius(r))) }
        val wanted = if (tailX.isNaN()) size.width / 2f else tailX - BUBBLE_SHADOW_ROOM.toPx()
        val x = wanted.coerceIn(r + halfBase, (size.width - r - halfBase).coerceAtLeast(r + halfBase))
        val tip = Path().apply {
            if (below) {
                moveTo(x - halfBase, top + 1f)
                lineTo(x, 0f)
                lineTo(x + halfBase, top + 1f)
            } else {
                moveTo(x - halfBase, bottom - 1f)
                lineTo(x, size.height)
                lineTo(x + halfBase, bottom - 1f)
            }
            close()
        }
        Outline.Generic(Path.combine(PathOperation.Union, body, tip))
    }
}

/** The anchor only dispatches (the bubble's drags); it consumes nothing itself. */
private val NoNestedScroll = object : NestedScrollConnection {}

/** How far the tail's tip stays from the field it points at. */
private val BUBBLE_GAP = 2.dp

/** The bubble keeps this far inside the window's edges. */
private val EDGE_MARGIN = 12.dp

/** The tail's height; its base is twice that. */
private val TAIL_HEIGHT = 8.dp

/** Wide enough for two short sentences at font scale 1, narrow enough to read as a bubble, not a sheet. */
private val BUBBLE_MAX_WIDTH = 300.dp

/** Room around the bubble inside the popup for its lift shadow, which a popup window would otherwise clip. */
private val BUBBLE_SHADOW_ROOM = 8.dp

/** How much of the page colour lies under the bubble's frost. */
private const val BUBBLE_UNDERLAY_ALPHA = 0.94f

/** The bubble grows from this scale (and shrinks back to it). */
private const val BUBBLE_FROM = 0.8f

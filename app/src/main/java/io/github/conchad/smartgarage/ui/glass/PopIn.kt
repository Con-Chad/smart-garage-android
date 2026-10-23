package io.github.conchad.smartgarage.ui.glass

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * The bubble pop-in. One [PopInState] per page, from [rememberPopIn]; each card takes [Modifier.popIn].
 *
 * - Plain (D-A32, the Vehicles tab): every card grows from [POP_IN_FROM] of its size at its own centre on one spring,
 *   with a quick fade, all at once.
 * - Staggered (D-A37, the vehicle page): the cards pop one after another, top to bottom, each starting
 *   [POP_IN_STAGGER_MS] after the one above started. Each grows from [POP_IN_STAGGER_FROM] to [POP_IN_OVERSHOOT] over
 *   [POP_IN_EXPAND_MS] and settles back to 1 over [POP_IN_SETTLE_MS] — a crisp bounce, not a wobble.
 */
@Stable
class PopInState internal constructor(internal val reduced: Boolean, internal val staggerMs: Long) {
    /** When the page's cards first composed (uptime ms). */
    private val start = SystemClock.uptimeMillis()

    /** The start of the last claimed slot, relative to [start]; -stagger before any card has claimed one. */
    private var lastSlot = -staggerMs

    /**
     * True for the first [POP_IN_WINDOW_MS] after the page appeared. A card that composes later (a lazy item
     * scrolled into view) appears at once. A plain var, not a state: each card reads it once at its first
     * composition and nothing observes the flip.
     */
    internal var armed = true

    /**
     * The delay before a card composed now starts: one stagger after the card above it, and never in the past. The
     * cards on screen compose top to bottom in the page's first pass, so they claim 0, 150, 300 ms…; one composed
     * later (scrolled in) queues behind them rather than waiting for a slot of its own far down the list.
     */
    internal fun claimDelay(): Long {
        val now = SystemClock.uptimeMillis() - start
        val slot = maxOf(lastSlot + staggerMs, now)
        lastSlot = slot
        return slot - now
    }
}

/**
 * The page's pop-in. Call it where the cards are composed (inside the loaded branch), so the window starts with them.
 * [staggered] pops the cards one after another (the vehicle page, D-A37); otherwise together (D-A32).
 */
@Composable
fun rememberPopIn(staggered: Boolean = false): PopInState {
    val context = LocalContext.current
    val state = remember { PopInState(isReducedMotion(context), if (staggered) POP_IN_STAGGER_MS else 0L) }
    LaunchedEffect(state) {
        delay(POP_IN_WINDOW_MS)
        state.armed = false
    }
    return state
}

/**
 * Pops this card in when it is composed inside the page's window. Read in `graphicsLayer {}` only, so a frame
 * recomposes nothing. Under reduced motion, or after the window, the card appears at once and takes no layer.
 */
@Composable
fun Modifier.popIn(popIn: PopInState): Modifier {
    val delayMs = remember(popIn) { if (popIn.armed && !popIn.reduced) (if (popIn.staggerMs > 0) popIn.claimDelay() else 0L) else -1L }
    if (delayMs < 0) return this
    return if (popIn.staggerMs > 0) staggeredPop(delayMs) else springPop()
}

/** D-A32: scale [POP_IN_FROM] → 1 on a soft spring, alpha reaching 1 by 40 % of the way. */
@Composable
private fun Modifier.springPop(): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, spring(dampingRatio = POP_IN_DAMPING, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer {
        val p = progress.value
        val s = POP_IN_FROM + (1f - POP_IN_FROM) * p
        scaleX = s
        scaleY = s
        alpha = (p * POP_IN_ALPHA_RATE).coerceIn(0f, 1f)
    }
}

/**
 * D-A37: invisible until its slot, then scale [POP_IN_STAGGER_FROM] → [POP_IN_OVERSHOOT] in [POP_IN_EXPAND_MS]
 * (decelerating), back to 1 in [POP_IN_SETTLE_MS]; opaque by [POP_IN_STAGGER_FADE_MS].
 */
@Composable
private fun Modifier.staggeredPop(delayMs: Long): Modifier {
    val scale = remember { Animatable(POP_IN_STAGGER_FROM) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(scale) {
        if (delayMs > 0) delay(delayMs)
        scale.animateTo(
            1f,
            keyframes {
                durationMillis = POP_IN_EXPAND_MS + POP_IN_SETTLE_MS
                POP_IN_STAGGER_FROM at 0 using PopExpandEasing
                POP_IN_OVERSHOOT at POP_IN_EXPAND_MS using FastOutSlowInEasing
            },
        )
    }
    LaunchedEffect(alpha) {
        if (delayMs > 0) delay(delayMs)
        alpha.animateTo(1f, tween(POP_IN_STAGGER_FADE_MS, easing = LinearOutSlowInEasing))
    }
    return graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
        this.alpha = alpha.value
    }
}

/** The expand: leaves quickly and eases into the overshoot, so the bounce reads as momentum. */
private val PopExpandEasing = CubicBezierEasing(0.2f, 0.6f, 0.35f, 1f)

/** How long after the page appears a newly composed card still pops in. */
private const val POP_IN_WINDOW_MS = 600L

/** The starting scale of a card (plain). */
private const val POP_IN_FROM = 0.6f

/** The spring's damping; under 1 so the bubble overshoots a little before it settles. */
private const val POP_IN_DAMPING = 0.6f

/** Alpha runs this many times faster than the scale: fully opaque at 40 % of the way. */
private const val POP_IN_ALPHA_RATE = 2.5f

/** Staggered: the gap between one card's start and the next's. */
private const val POP_IN_STAGGER_MS = 150L

/** Staggered: the starting scale. */
private const val POP_IN_STAGGER_FROM = 0.8f

/** Staggered: the peak of the bounce. */
private const val POP_IN_OVERSHOOT = 1.03f

/** Staggered: the expand to the peak. */
private const val POP_IN_EXPAND_MS = 200

/** Staggered: the settle from the peak back to 1. */
private const val POP_IN_SETTLE_MS = 90

/** Staggered: the fade in, quicker than the expand so the card is solid well before its peak. */
private const val POP_IN_STAGGER_FADE_MS = 120

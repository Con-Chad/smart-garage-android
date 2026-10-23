package io.github.conchad.smartgarage.ui.glass

import android.os.SystemClock
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * The bubble pop-in. One [PopInState] per page, from [rememberPopIn]; each card takes [Modifier.popIn].
 *
 * - Plain (D-A32; no caller since D-A51 took it off the Vehicles tab): every card grows from [POP_IN_FROM] of its size at its own centre on one spring,
 *   with a quick fade, all at once.
 * - Staggered (D-A37, the vehicle page): the cards pop one after another, top to bottom, each starting
 *   [Motion.POP_STAGGER_MS] after the one above started. Each grows from [POP_IN_STAGGER_FROM] to [POP_IN_OVERSHOOT]
 *   over [Motion.POP_EXPAND_MS] and settles back to 1 over [Motion.POP_SETTLE_MS] — a crisp bounce, not a wobble.
 *
 * Timings come from [Motion] (D-A40); the D-A37 numbers there are user-tuned.
 *
 * D-A55: the fades modulate each draw's alpha ([CompositingStrategy.ModulateAlpha]) instead of drawing the card into
 * an offscreen buffer. That buffer is the size of the card's bounds, so it cut the soft glass shadow, which lies
 * outside them, into a hard-edged rectangle with square corners for as long as the card was fading.
 */
@Stable
class PopInState internal constructor(
    internal val reduced: Boolean,
    internal val staggerMs: Long,
    startDelayMs: Long = 0L,
    /** The page's pop-out scale while leaving ([rememberPopOut]); null when the page doesn't pop out. */
    internal val popOut: State<Float>? = null,
) {
    /** When the page's cards first composed (uptime ms). */
    private val start = SystemClock.uptimeMillis()

    /** The start of the last claimed slot, relative to [start]; one stagger before the first slot until a card claims it. */
    private var lastSlot = startDelayMs - staggerMs

    /**
     * True for the first [Motion.POP_WINDOW_MS] after the page appeared. A card that composes later (a lazy item
     * scrolled into view) appears at once. A plain var, not a state: each card reads it once at its first
     * composition and nothing observes the flip.
     */
    internal var armed = true

    /**
     * The delay before a card composed now starts: one stagger after the card above it, and never in the past. The
     * cards on screen compose top to bottom in the page's first pass, so they claim 0, 100, 200 ms…; one composed
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
 * [staggered] pops the cards one after another (the vehicle page, D-A37); otherwise together (D-A32). The first
 * staggered card starts [startDelayMs] after the page appears (D-A43: the vehicle page waits for its hero). [popOut]
 * is the page's [rememberPopOut], which every card then follows when the page is left. With [enabled] false nothing
 * pops in (D-A46: a page returned to by Back, or the vehicle page opened other than from a Home card); the pop-out
 * still applies.
 */
@Composable
fun rememberPopIn(staggered: Boolean = false, startDelayMs: Long = 0L, popOut: State<Float>? = null, enabled: Boolean = true): PopInState {
    val reduced = rememberReducedMotion()
    val state = remember {
        PopInState(reduced, if (staggered) Motion.POP_STAGGER_MS else 0L, if (staggered) startDelayMs else 0L, popOut).also { it.armed = enabled }
    }
    LaunchedEffect(state) {
        delay(Motion.POP_WINDOW_MS + startDelayMs)
        state.armed = false
    }
    return state
}

/**
 * Pops this card in when it is composed inside the page's window, and out with the page when it has a pop-out.
 * Read in `graphicsLayer {}` only, so a frame recomposes nothing. Under reduced motion, or after the window, the
 * card appears at once and takes no entry layer.
 */
@Composable
fun Modifier.popIn(popIn: PopInState): Modifier {
    val delayMs = remember(popIn) { if (popIn.armed && !popIn.reduced) (if (popIn.staggerMs > 0) popIn.claimDelay() else 0L) else -1L }
    val entered = when {
        delayMs < 0 -> this
        popIn.staggerMs > 0 -> staggeredPop(delayMs)
        else -> springPop()
    }
    val out = popIn.popOut ?: return entered
    return entered.popOutLayer(out)
}

/**
 * The reverse pop (D-A43) for a page that is being left: one value per page, 1 at rest, rising to
 * [POP_IN_OVERSHOOT] in [Motion.POP_OUT_BULGE_MS] and falling to 0 in [Motion.POP_OUT_SHRINK_MS], every card
 * together, while the hero glides back. Runs only when [leaving] says the page is being popped (Back), not when
 * another screen is pushed over it; null under reduced motion or outside the shell, so nothing pops out.
 */
@Composable
fun rememberPopOut(leaving: () -> Boolean): State<Float>? {
    val nav = LocalNavAnimatedScope.current ?: return null
    if (rememberReducedMotion()) return null
    return nav.transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.PostExit) {
                keyframes {
                    durationMillis = Motion.POP_OUT_BULGE_MS + Motion.POP_OUT_SHRINK_MS
                    1f at 0 using Motion.PopExpandEasing
                    POP_IN_OVERSHOOT at Motion.POP_OUT_BULGE_MS using FastOutLinearInEasing
                }
            } else {
                snap()
            }
        },
        label = "popOut",
    ) { if (it == EnterExitState.PostExit && leaving()) 0f else 1f }
}

/** Fades what doesn't pop (the top bar) as the page's cards shrink away: gone as they are. */
fun Modifier.popOutFade(popOut: State<Float>?): Modifier = if (popOut == null) this else graphicsLayer {
    alpha = popOut.value.coerceIn(0f, 1f)
}

/** Scales the card by the page's pop-out; it turns transparent over the last of the shrink so no sliver lingers. */
private fun Modifier.popOutLayer(out: State<Float>): Modifier = graphicsLayer {
    val s = out.value
    if (s == 1f) return@graphicsLayer
    scaleX = s
    scaleY = s
    alpha = (s / POP_OUT_FADE_BELOW).coerceIn(0f, 1f)
    compositingStrategy = CompositingStrategy.ModulateAlpha
}

/** D-A32: scale [POP_IN_FROM] → 1 on [Motion.popSpring], alpha reaching 1 by 40 % of the way. */
@Composable
private fun Modifier.springPop(): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(progress) {
        progress.animateTo(1f, Motion.popSpring())
    }
    return graphicsLayer {
        val p = progress.value
        val s = POP_IN_FROM + (1f - POP_IN_FROM) * p
        scaleX = s
        scaleY = s
        alpha = (p * POP_IN_ALPHA_RATE).coerceIn(0f, 1f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/**
 * D-A37: invisible until its slot, then scale [POP_IN_STAGGER_FROM] → [POP_IN_OVERSHOOT] in [Motion.POP_EXPAND_MS]
 * (decelerating), back to 1 in [Motion.POP_SETTLE_MS]; opaque by [Motion.POP_FADE_MS].
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
                durationMillis = Motion.POP_EXPAND_MS + Motion.POP_SETTLE_MS
                POP_IN_STAGGER_FROM at 0 using Motion.PopExpandEasing
                POP_IN_OVERSHOOT at Motion.POP_EXPAND_MS using FastOutSlowInEasing
            },
        )
    }
    LaunchedEffect(alpha) {
        if (delayMs > 0) delay(delayMs)
        alpha.animateTo(1f, tween(Motion.POP_FADE_MS, easing = LinearOutSlowInEasing))
    }
    return graphicsLayer {
        val s = scale.value
        scaleX = s
        scaleY = s
        this.alpha = alpha.value
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** The starting scale of a card (plain). */
private const val POP_IN_FROM = 0.6f

/** Alpha runs this many times faster than the scale: fully opaque at 40 % of the way. */
private const val POP_IN_ALPHA_RATE = 2.5f

/** Staggered: the starting scale. */
private const val POP_IN_STAGGER_FROM = 0.8f

/** Staggered: the peak of the bounce; also the pop-out's bulge. */
private const val POP_IN_OVERSHOOT = 1.03f

/** Popping out, a card fades as its scale falls below this. */
private const val POP_OUT_FADE_BELOW = 0.35f

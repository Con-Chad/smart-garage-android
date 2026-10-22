package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * The bubble pop-in (D-A32): every card of a page grows from [POP_IN_FROM] of its size at its own centre on one
 * spring, with a quick fade, all at once — no stagger, no slide. One [PopInState] per page, from [rememberPopIn];
 * each card takes [Modifier.popIn]. The page's nav transition is `None`, so this is the only motion on the way in.
 */
@Stable
class PopInState internal constructor(internal val reduced: Boolean) {
    /**
     * True for the first [POP_IN_WINDOW_MS] after the page appeared. A card that composes later (a lazy item
     * scrolled into view) appears at once. A plain var, not a state: each card reads it once at its first
     * composition and nothing observes the flip.
     */
    internal var armed = true
}

/** The page's pop-in. Call it where the cards are composed (inside the loaded branch), so the window starts with them. */
@Composable
fun rememberPopIn(): PopInState {
    val context = LocalContext.current
    val state = remember { PopInState(isReducedMotion(context)) }
    LaunchedEffect(state) {
        delay(POP_IN_WINDOW_MS)
        state.armed = false
    }
    return state
}

/**
 * Pops this card in when it is composed inside the page's window: scale [POP_IN_FROM] → 1 on `spring(dampingRatio
 * 0.6, StiffnessMediumLow)` (a touch of overshoot, like a bubble settling), alpha reaching 1 by 40 % of the way.
 * Read in `graphicsLayer {}` only, so a frame recomposes nothing. Under reduced motion, or after the window, the
 * card appears at once and takes no layer.
 */
@Composable
fun Modifier.popIn(popIn: PopInState): Modifier {
    val animate = remember(popIn) { popIn.armed && !popIn.reduced }
    if (!animate) return this
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

/** How long after the page appears a newly composed card still pops in. */
private const val POP_IN_WINDOW_MS = 600L

/** The starting scale of a card. */
private const val POP_IN_FROM = 0.6f

/** The spring's damping; under 1 so the bubble overshoots a little before it settles. */
private const val POP_IN_DAMPING = 0.6f

/** Alpha runs this many times faster than the scale: fully opaque at 40 % of the way. */
private const val POP_IN_ALPHA_RATE = 2.5f

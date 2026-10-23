package io.github.conchad.smartgarage.ui.glass

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * The app's one motion system (D-A40): every duration, easing and spring the glass UI animates with. Screens and
 * components take their timing from here rather than from literals, so the hero, the drawer, the pop-ins, the fan,
 * the bottom nav, menus, drop-downs and press feedback move as one family. [rememberReducedMotion] is the one
 * reduced-motion check; [orSnap] / [orNone] turn a spec off under it.
 *
 * The D-A37 values (vehicle-page stagger, hero glide) are user-tuned: do not change them.
 */
object Motion {
    // Durations (ms).

    /** Quick fades: a leaving hero card's content, a bubbling page leaving, a tab label hiding. */
    const val QUICK_MS = 120

    /** Small enter fades. */
    const val SHORT_MS = 200

    /** Small things that travel: the disconnected popup's slide, the refresh icon's scale. */
    const val MEDIUM_MS = 260

    /** Colour and settle moves: a tab's icon tint, the tilt frame easing back. */
    const val STANDARD_MS = 280

    /** The vehicle hero's glide and the drawer's slide (D-A34, D-A37). */
    const val HERO_MS = 320

    /**
     * Opening the vehicle page, the hero card starts moving up this long before the drawer's slide ends (D-A41,
     * user-set), making room for the cards popping in under it.
     */
    const val HERO_OVERLAP_MS = 80

    /**
     * Opening the vehicle page, the hero card starts this long after the drawer beneath it: the drawer's duration
     * less [HERO_OVERLAP_MS], so 240 ms (D-A41, user-set; 400 ms in D-A40 was far too long). 560 ms in all.
     */
    const val HERO_DELAY_MS = HERO_MS - HERO_OVERLAP_MS

    /** A slower spin: the refresh icon while a sync runs. */
    const val SPIN_MS = 900

    /** One pass of the loading skeleton's sweep (D-A41): the sync spin's pace, so every "still loading" moves alike. */
    const val SHIMMER_MS = SPIN_MS

    /** NavHost's cross-fade between ordinary routes. */
    const val NAV_FADE_MS = 700

    /** Tint cross-fades that follow the aurora (the aurora itself, the fan's colours). */
    const val TINT_MS = 800

    // The vehicle page's staggered pop-in (D-A37; stagger 150 -> 100 ms in D-A40; user-tuned: keep these numbers).

    /** Between one card's start and the next card's. */
    const val POP_STAGGER_MS = 100L

    /** How long after a page appears a newly composed card still pops in (a lazy item scrolled in queues). */
    const val POP_WINDOW_MS = 600L

    /** The expand from 0.8 to the 1.03 overshoot. */
    const val POP_EXPAND_MS = 200

    /** The settle from the overshoot back to 1. */
    const val POP_SETTLE_MS = 90

    /** The fade in, quicker than the expand so the card is solid well before its peak. */
    const val POP_FADE_MS = 120

    // Easings.

    /**
     * The hero glide and the drawer (D-A37): a stronger ease-in than FastOutSlowIn's 0.4, so the start visibly
     * gathers speed, then the same soft landing. The reverse (Back) runs the same curve.
     */
    val EmphasizedEasing = CubicBezierEasing(0.6f, 0f, 0.2f, 1f)

    /** The staggered pop's expand (D-A37): leaves quickly and eases into the overshoot, so the bounce reads as momentum. */
    val PopExpandEasing = CubicBezierEasing(0.2f, 0.6f, 0.35f, 1f)

    /** A steep ease-out for things settling back to rest (the tilt frame). */
    val SettleEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    // Springs.

    /** The bottom nav's blob and the active tab's lift: a lively bounce. */
    fun <T> bouncySpring() = spring<T>(dampingRatio = 0.55f, stiffness = 300f)

    /** Pop-ins: the Vehicles tab's bubble (D-A32), the fan core's turn. */
    fun <T> popSpring() = spring<T>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)

    /** The fan's petals flying out and back. */
    fun <T> petalSpring() = spring<T>(dampingRatio = 0.62f, stiffness = 380f)

    /** Things that open: a glass menu appearing, a drop-down growing. A hint of overshoot. */
    fun <T> gentleSpring() = spring<T>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)

    /** Chevrons turning: no overshoot. */
    fun <T> standardSpring() = spring<T>(stiffness = Spring.StiffnessMediumLow)

    /** Press feedback going down and coming back: quick and critically damped. */
    fun <T> pressSpring() = spring<T>(stiffness = 600f)

    // Press feedback.

    /** A pill, button or bead shrinks to this while held. */
    const val PRESS_SCALE = 0.96f

    /** A tappable panel lifts to this while held (the D-A24 snippet's press lift). */
    const val PRESS_LIFT = 1.02f

    /** The white wash over a held glass surface, at full press. */
    const val PRESS_GLOW_ALPHA = 0.10f
}

/** [this], or a snap when [reduced]. */
fun <T> FiniteAnimationSpec<T>.orSnap(reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else this

/** [this], or no enter animation when [reduced]. */
fun EnterTransition.orNone(reduced: Boolean): EnterTransition = if (reduced) EnterTransition.None else this

/** [this], or no exit animation when [reduced]. */
fun ExitTransition.orNone(reduced: Boolean): ExitTransition = if (reduced) ExitTransition.None else this

/**
 * True when the user turned animations off (animator duration scale 0 in Developer options / "Remove animations"
 * in Accessibility): the app's one reduced-motion check (D-A40). Ambient effects then render one static frame, the
 * snippets' `prefers-reduced-motion` guard; transitions snap. Read synchronously on first composition (so a
 * decision taken once, like the pop-in or the hero, is right on frame 1) and again whenever the screen resumes, so
 * a change in system settings applies on return.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val resumed = rememberIsResumed()
    return remember(context, resumed) { isReducedMotion(context) }
}

/** The setting itself, behind [rememberReducedMotion]. */
private fun isReducedMotion(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** Whether the hosting lifecycle is RESUMED; ambient animations stop composing when it isn't (the `visibilitychange` pause). */
@Composable
fun rememberIsResumed(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    return state.isAtLeast(Lifecycle.State.RESUMED)
}

/** Ambient motion runs only while on screen and allowed; one flag for every effect on a screen. */
@Composable
fun rememberAmbientMotion(): Boolean {
    val resumed = rememberIsResumed()
    val reduced = rememberReducedMotion()
    return resumed && !reduced
}

/** A [State] that never changes, for the static branch of an animation. */
@Composable
fun <T> rememberStatic(value: T): State<T> = remember(value) { mutableStateOf(value) }

/**
 * Milliseconds since this composable entered the tree, advanced from the frame clock but at most once per
 * [minIntervalMillis]. Ambient effects derive their phase from it inside a draw lambda, so they repaint at that
 * cadence instead of once per vsync: a 120 Hz panel would otherwise redraw the aurora 120 times a second for
 * ribbons that move about 25 px/s. Between ticks the coroutine sleeps rather than waking on every frame; the
 * tick itself lands on a vsync so the write lines up with a frame. The value is real elapsed time, so uneven tick
 * spacing never changes the speed of what is drawn from it.
 */
@Composable
fun rememberAmbientClock(minIntervalMillis: Long): State<Long> {
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(minIntervalMillis) {
        val start = withFrameNanos { it }
        while (true) {
            val sleep = minIntervalMillis - FRAME_SLACK_MS
            if (sleep > 0) delay(sleep)
            val now = withFrameNanos { it }
            elapsed.longValue = (now - start) / 1_000_000L
        }
    }
    return elapsed
}

/** Woken this much before the interval ends so the next vsync, not the one after, carries the tick. */
private const val FRAME_SLACK_MS = 4L

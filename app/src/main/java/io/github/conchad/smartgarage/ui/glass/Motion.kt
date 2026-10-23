package io.github.conchad.smartgarage.ui.glass

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import kotlin.math.roundToInt

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
     * Opening the vehicle page, the drawer leads and the hero card starts rising this long after it (D-A43): just
     * enough that the drawer is visibly under way first. Was 240 ms (D-A41), which read as the hero waiting. 380 ms
     * in all.
     */
    const val HERO_DELAY_MS = 60

    /** Opening the vehicle page, when the hero and the Home card's collapsing drawer come to rest (D-A45). */
    const val HERO_END_MS = HERO_DELAY_MS + HERO_MS

    /**
     * The screen a vehicle page opens over (Home's greeting, headers and other cards, its fan FAB) and the bottom
     * tray fade out over the whole hero glide, on its curve, ending as the card lands; Back fades them in over the
     * same span (D-A45).
     */
    const val UNDER_HERO_FADE_MS = HERO_END_MS

    /** A slower spin: the refresh icon while a sync runs. */
    const val SPIN_MS = 900

    /** One pass of the loading skeleton's sweep (D-A41): the sync spin's pace, so every "still loading" moves alike. */
    const val SHIMMER_MS = SPIN_MS

    /**
     * Tab to tab (D-A51, replaces D-A46's 700 ms cross-fade): a fade-through. The page being left fades out in
     * [NAV_TAB_OUT_MS], dropping fastest at the start; the tab arriving waits [NAV_TAB_IN_DELAY_MS] (the old page is
     * then nearly gone) and fades in over [NAV_TAB_IN_MS] while growing from [NAV_TAB_SCALE_FROM]. 240 ms in all, and
     * two busy glass pages are never on screen together at any strength.
     */
    const val NAV_TAB_OUT_MS = 80

    /** See [NAV_TAB_OUT_MS]. */
    const val NAV_TAB_IN_DELAY_MS = 60

    /** See [NAV_TAB_OUT_MS]. */
    const val NAV_TAB_IN_MS = 180

    /** The arriving tab's starting scale: a hint of settling into place, not a zoom. */
    const val NAV_TAB_SCALE_FROM = 0.98f

    /**
     * A screen whose cache answers within this long of it appearing shows its content at once rather than
     * cross-fading from the loading state (D-A51): longer than the tab fade-through, so a tab opened for the first
     * time arrives already filled.
     */
    const val LOADING_GRACE_MS = 250L

    /**
     * A plain pushed screen (a form, Settings, a section, the vehicle page opened from a list) slides in from the
     * right by [NAV_SLIDE_FRACTION] of the width while fading in, decelerating, over this long (D-A46).
     */
    const val NAV_PUSH_MS = 300

    /** Back from a plain screen: it slides out to the right while fading, accelerating, over this long (D-A46). */
    const val NAV_POP_MS = 260

    /**
     * The page underneath fades out this fast as a screen is pushed over it, quicker than the slide, so the two never
     * sit fully on top of each other for long; it fades back in over [NAV_POP_MS] as that screen leaves (D-A46).
     */
    const val NAV_UNDER_FADE_MS = 200

    /** How far a pushed screen travels, as a fraction of the width: enough to read as a slide, short enough to stay calm. */
    const val NAV_SLIDE_FRACTION = 0.3f

    /**
     * Adaptive ink (D-A46): the time constant of the low-pass the text colour follows toward the aurora under it.
     * 63 % of a change in 140 ms, settled in about half a second; smooth while scrolling, never a snap.
     */
    const val INK_SMOOTH_MS = 140f

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

    /**
     * Opening the vehicle page from Home (D-A43, user-set): the first card pops [POP_AFTER_HERO_MS] after the hero
     * starts rising, at its final spot and drawn above the hero ([drawerSlide]); the stagger follows from here. D-A45:
     * 200 ms (260 ms in) lands just as the Home card's drawer finishes collapsing, so nothing overlaps it.
     */
    const val POP_AFTER_HERO_MS = 200L
    const val POP_WITH_HERO_MS = HERO_DELAY_MS.toLong() + POP_AFTER_HERO_MS

    /** Leaving the vehicle page (D-A43): each card bulges to the overshoot in this long… */
    const val POP_OUT_BULGE_MS = 70

    /** …then shrinks to nothing in this long, all cards together while the hero glides back. */
    const val POP_OUT_SHRINK_MS = 150

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

    /** A pushed screen arriving (D-A46): Material's emphasized decelerate. */
    val SlideInEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** A screen leaving by Back (D-A46): Material's emphasized accelerate. */
    val SlideOutEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** A steep ease-out for things settling back to rest (the tilt frame). */
    val SettleEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    // Springs.

    /** The bottom nav's blob and the active tab's lift: a lively bounce. */
    fun <T> bouncySpring() = spring<T>(dampingRatio = 0.55f, stiffness = 300f)

    /** Pop-ins: the plain bubble ([rememberPopIn], unused since D-A51), the fan core's turn. */
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

/** The fade of what the vehicle hero leaves behind or comes back to: [Motion.UNDER_HERO_FADE_MS] on the hero's curve. */
fun <T> underHeroFadeSpec(): FiniteAnimationSpec<T> = tween(Motion.UNDER_HERO_FADE_MS, easing = Motion.EmphasizedEasing)

/** A tab arriving from another (D-A51): after a beat, fades in while settling from a hair smaller. */
fun navTabEnter(): EnterTransition =
    fadeIn(tween(Motion.NAV_TAB_IN_MS, delayMillis = Motion.NAV_TAB_IN_DELAY_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(Motion.NAV_TAB_IN_MS, delayMillis = Motion.NAV_TAB_IN_DELAY_MS, easing = LinearOutSlowInEasing), initialScale = Motion.NAV_TAB_SCALE_FROM)

/** The tab being left (D-A51): gone fast, before the next one shows. */
fun navTabExit(): ExitTransition = fadeOut(tween(Motion.NAV_TAB_OUT_MS, easing = LinearOutSlowInEasing))

/** A plain screen arriving (D-A46): in from the right while fading in. */
fun navPushEnter(): EnterTransition =
    slideInHorizontally(tween(Motion.NAV_PUSH_MS, easing = Motion.SlideInEasing)) { (it * Motion.NAV_SLIDE_FRACTION).roundToInt() } +
        fadeIn(tween(Motion.NAV_PUSH_MS, easing = LinearOutSlowInEasing))

/** The page a plain screen is pushed over (D-A46): it fades out underneath. */
fun navPushExit(): ExitTransition = fadeOut(tween(Motion.NAV_UNDER_FADE_MS))

/** The page a plain screen leaves by Back to (D-A46): it fades back in, revealed as that screen slides away. */
fun navPopEnter(): EnterTransition = fadeIn(tween(Motion.NAV_POP_MS))

/** A plain screen leaving by Back (D-A46): out to the right while fading. */
fun navPopExit(): ExitTransition =
    slideOutHorizontally(tween(Motion.NAV_POP_MS, easing = Motion.SlideOutEasing)) { (it * Motion.NAV_SLIDE_FRACTION).roundToInt() } +
        fadeOut(tween(Motion.NAV_POP_MS, easing = FastOutLinearInEasing))

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

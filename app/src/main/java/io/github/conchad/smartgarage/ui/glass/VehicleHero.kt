package io.github.conchad.smartgarage.ui.glass

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/*
 * The vehicle hero (D-A34): the car card one taps on Home glides to where the vehicle page's photo card sits, and
 * the page's cards beneath it slide up behind it as one drawer, on the same tween. The card is a `sharedBounds`
 * pair keyed on the vehicle id inside the shell's SharedTransitionLayout; the drawer offset rides the nav entry's
 * own enter/exit transition, so both start on the same frame and end together.
 */

/** The shell's SharedTransitionLayout scope; null outside the shell (previews, tests): every hero modifier is then a no-op. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The nav entry's AnimatedContentScope, provided by MainShell for Home, Vehicles and the vehicle page. */
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Where a tapped Home card's bottom edge was (root px), handed to the vehicle page that opens from it. */
@Stable
class VehicleHeroOrigins {
    private val bottoms = HashMap<String, Float>()

    /** The card for [vehicleId] is being opened from a screen where its bottom edge is at [bottomPx]. */
    fun launch(vehicleId: String, bottomPx: Float) {
        bottoms[vehicleId] = bottomPx
    }

    private var lastTaken: String? = null
    private var lastTakenAt = 0L

    /** The origin of a launch, once; a page opened any other way (a list, a push, Back from a form) finds none. */
    fun take(vehicleId: String): Float? = bottoms.remove(vehicleId)?.also {
        lastTaken = vehicleId
        lastTakenAt = SystemClock.uptimeMillis()
    }

    /**
     * True while the page for [vehicleId] is being opened from a Home card (D-A46: the shell picks the hero path's
     * transitions from it). Doesn't consume the launch; a launch the page took a moment ago still counts, whichever
     * of the two the frame composes first.
     */
    fun isLaunching(vehicleId: String): Boolean =
        bottoms.containsKey(vehicleId) || (lastTaken == vehicleId && SystemClock.uptimeMillis() - lastTakenAt < LAUNCH_GRACE_MS)
}

/** How long after the page took its launch the shell still counts it as the hero path. */
private const val LAUNCH_GRACE_MS = 500L

val LocalVehicleHeroOrigins = staticCompositionLocalOf<VehicleHeroOrigins?> { null }

/**
 * The glide (D-A37): a short ramp-up — it gathers speed from rest — then rises and decelerates into place, no
 * overshoot ([Motion.EmphasizedEasing] over [Motion.HERO_MS], D-A40). Shared by the card's bounds and the drawer, so
 * they stay in step, opening and on Back.
 */
private fun <T> heroSpec(delayMs: Int = 0) = tween<T>(Motion.HERO_MS, delayMillis = delayMs, easing = Motion.EmphasizedEasing)

/**
 * The card's bounds (D-A40): opening the vehicle page, the drawer slides up first and the card follows
 * [Motion.HERO_DELAY_MS] later (D-A43: 60 ms, so it rises almost at once); on Back the card glides at once while the
 * page's cards pop out in place.
 * [page] is the vehicle page's card (entering on open), else Home's (leaving on open); whichever side's transform
 * Compose uses, the delay applies only to the opening.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private fun heroBounds(nav: AnimatedVisibilityScope, page: Boolean) = BoundsTransform { _, _ ->
    val entering = nav.transition.targetState == EnterExitState.Visible
    heroSpec(if (entering == page) Motion.HERO_DELAY_MS else 0)
}

/**
 * Makes this card the hero for [vehicleId]: between Home and the vehicle page it glides from one position and
 * height to the other in [Motion.HERO_MS], drawn at full alpha and scale throughout (the arriving card shows at once).
 * No-op under reduced motion or outside the shell.
 *
 * D-A43: the card is remeasured to the gliding bounds, so its own plate, border and outside-only shadow are drawn at
 * those bounds in every frame, exactly as at rest; its content keeps its natural height inside ([HeroContent]),
 * top-aligned and clipped by the plate, so the photo never stretches. Going Back, the vehicle page's card leaves at
 * once rather than fading over the Home card, so there is never a doubled fill or shadow.
 *
 * D-A45: opening, the Home card is drawn above the page's card ([HOME_HERO_Z]) with no plate of its own
 * ([heroPlateOff]): the page card under it supplies the one fill, border and shadow, and the Home card's photo and
 * footer ride on top at full alpha, so the footer (the drawer under the photo) is clipped away as the bounds
 * collapse instead of fading out before they move (the "blink": a [Motion.QUICK_MS] fade from frame 0). The Home
 * card fades only over the last [Motion.QUICK_MS] of the glide, when little but the photo is left, onto the page's
 * identical photo.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.vehicleHero(vehicleId: String, page: Boolean = false): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val nav = LocalNavAnimatedScope.current ?: return this
    val reduced = rememberReducedMotion()
    if (reduced) return this
    val bounds = remember(nav, page) { heroBounds(nav, page) }
    return with(shared) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(key = "vehicle-card-$vehicleId"),
            animatedVisibilityScope = nav,
            enter = EnterTransition.None,
            exit = if (page) {
                fadeOut(snap())
            } else {
                fadeOut(tween(Motion.QUICK_MS, delayMillis = Motion.HERO_END_MS - Motion.QUICK_MS))
            },
            boundsTransform = bounds,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            zIndexInOverlay = if (page) 0f else HOME_HERO_Z,
        )
    }
}

/**
 * A hero card's content (D-A43): measured at its natural height whatever the gliding bounds are, top-aligned, so
 * the card's plate clips what doesn't fit instead of the content being squeezed.
 */
@Composable
fun HeroContent(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true), content = content)
}

/**
 * True while this screen's hero card is leaving in a shared glide (Home, opening a vehicle): it then draws no plate,
 * border or shadow, only its content over the arriving card's, so the card looks the same in every frame (D-A43,
 * D-A45).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun heroPlateOff(): Boolean {
    val shared = LocalSharedTransitionScope.current ?: return false
    val nav = LocalNavAnimatedScope.current ?: return false
    return shared.isTransitionActive && nav.transition.targetState == EnterExitState.PostExit
}

/** Records this card's bottom edge so a tap can hand it to [VehicleHeroOrigins.launch]. */
class HeroAnchor {
    internal var bottomPx: Float = Float.NaN
}

@Composable
fun rememberHeroAnchor(): HeroAnchor = remember { HeroAnchor() }

fun Modifier.heroAnchor(anchor: HeroAnchor): Modifier = onGloballyPositioned {
    anchor.bottomPx = it.positionInRoot().y + it.size.height
}

/** Hands [anchor]'s position to the page about to open for [vehicleId]. */
fun VehicleHeroOrigins?.launch(vehicleId: String, anchor: HeroAnchor) {
    if (this != null && !anchor.bottomPx.isNaN()) launch(vehicleId, anchor.bottomPx)
}

/**
 * How the vehicle page was opened (D-A43, D-A46): from a Home card (the hero path) its cards pop in at their final
 * spots above the gliding hero, each after the one above; opened any other way (a list, a push, Back from a form)
 * the page slides in as a whole and nothing pops. Leaving by Back to Home the cards pop out in place
 * ([rememberPopOut]). One per page, from [rememberDrawerSlide].
 */
@Stable
class DrawerSlide internal constructor(
    internal val reduced: Boolean,
    private val originBottom: Float?,
) {
    /** Opened from a Home card, so the hero glides in and the cards pop in above it (D-A43, D-A46). */
    val fromHero: Boolean get() = !reduced && originBottom != null
}

/** The page's drawer slide. Call it where the cards are composed, with the vehicle the page shows. */
@Composable
fun rememberDrawerSlide(vehicleId: String): DrawerSlide {
    val reduced = rememberReducedMotion()
    val origins = LocalVehicleHeroOrigins.current
    val origin = remember(vehicleId) { origins?.take(vehicleId) }
    return remember(origin, reduced) { DrawerSlide(reduced, origin) }
}

/**
 * Opened from a Home card (D-A43, user-set) the card doesn't travel: it pops in at its final spot from the moment
 * the hero starts rising, and is drawn above the gliding hero for the length of the transition, so the hero rises
 * into place above it and never hides it. Opened any other way the card is simply part of the page (D-A46).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.drawerSlide(slide: DrawerSlide): Modifier {
    if (!slide.fromHero) return this
    val shared = LocalSharedTransitionScope.current ?: return this
    val nav = LocalNavAnimatedScope.current ?: return this
    // Only while arriving: going Back the hero glides down over the cards popping out.
    return with(shared) {
        renderInSharedTransitionScopeOverlay(
            renderInOverlay = { isTransitionActive && nav.transition.targetState == EnterExitState.Visible },
            zIndexInOverlay = ABOVE_HERO_Z,
        )
    }
}

/** The page's hero is on overlay layer 0; the page's cards draw over both heroes while they glide (D-A43). */
private const val ABOVE_HERO_Z = 1f

/** Opening, the leaving Home card draws over the page's card, under the page's cards (D-A45). */
private const val HOME_HERO_Z = 0.5f

package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

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

    /** The origin of a launch, once; a page opened any other way (a list, a push) finds none. */
    fun take(vehicleId: String): Float? = bottoms.remove(vehicleId)
}

val LocalVehicleHeroOrigins = staticCompositionLocalOf<VehicleHeroOrigins?> { null }

/**
 * The glide (D-A37): a short ramp-up — it gathers speed from rest — then rises and decelerates into place, no
 * overshoot ([Motion.EmphasizedEasing] over [Motion.HERO_MS], D-A40). Shared by the card's bounds and the drawer, so
 * they stay in step, opening and on Back.
 */
private fun <T> heroSpec(delayMs: Int = 0) = tween<T>(Motion.HERO_MS, delayMillis = delayMs, easing = Motion.EmphasizedEasing)

/**
 * The card's bounds (D-A40): opening the vehicle page, the drawer slides up first and the card follows
 * [Motion.HERO_DELAY_MS] later, rising off the cards it covered; on Back the card and the drawer move together.
 * [page] is the vehicle page's card (entering on open), else Home's (leaving on open); whichever side's transform
 * Compose uses, the delay applies only to the opening.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private fun heroBounds(nav: AnimatedVisibilityScope, page: Boolean) = BoundsTransform { _, _ ->
    val entering = nav.transition.targetState == EnterExitState.Visible
    heroSpec(if (entering == page) Motion.HERO_DELAY_MS else 0)
}

/**
 * Clips the card in the overlay to its plate: the bounds include [GlassCard]'s 16 dp side margins, so the clip is
 * inset by them and rounded like the panel. As the bounds shrink, the Home card's footer slides up under its edge.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
private class CardPlateClip(private val margin: Dp, private val corner: Dp) : SharedTransitionScope.OverlayClip {
    override fun getClipPath(state: SharedTransitionScope.SharedContentState, bounds: Rect, layoutDirection: LayoutDirection, density: Density): Path {
        val inset = with(density) { margin.toPx() }
        val radius = with(density) { corner.toPx() }
        return Path().apply {
            addRoundRect(RoundRect(bounds.left + inset, bounds.top, bounds.right - inset, bounds.bottom, CornerRadius(radius)))
        }
    }
}

/**
 * Makes this card the hero for [vehicleId]: between Home and the vehicle page it glides from one position and
 * height to the other in [Motion.HERO_MS], drawn at full alpha and scale throughout (the arriving card shows at once; the
 * leaving card's content fades in [Motion.QUICK_MS] under or over it, the photos coinciding). Content keeps its own size, top-aligned, so the photo never stretches. No-op under
 * reduced motion or outside the shell.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.vehicleHero(vehicleId: String, page: Boolean = false): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val nav = LocalNavAnimatedScope.current ?: return this
    val reduced = rememberReducedMotion()
    if (reduced) return this
    val corner = glass.radiusLarge
    val clip = remember(corner) { CardPlateClip(CARD_MARGIN, corner) }
    val bounds = remember(nav, page) { heroBounds(nav, page) }
    return with(shared) {
        sharedBounds(
            sharedContentState = rememberSharedContentState(key = "vehicle-card-$vehicleId"),
            animatedVisibilityScope = nav,
            enter = EnterTransition.None,
            exit = fadeOut(tween(Motion.QUICK_MS)),
            boundsTransform = bounds,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(ContentScale.FillWidth, Alignment.TopCenter),
            clipInOverlayDuringTransition = clip,
        )
    }
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
 * The vehicle page's drawer: every card under the photo slides up from where the Home card's bottom edge was to
 * its place, in step with the hero, with a quick fade; on the way back it slides down the same way. Opened any
 * other way, it rises [DRAWER_FALLBACK_SLIDE]. One per page, from [rememberDrawerSlide].
 */
@Stable
class DrawerSlide internal constructor(
    internal val reduced: Boolean,
    private val originBottom: Float?,
    private val fallback: Float,
    /** 1 = off at the start offset, 0 = in place; follows the nav entry's transition. */
    internal val offProgress: State<Float>,
) {
    /** The hero's own bottom edge on this page (root px), from its first layout. */
    private var heroBottom by mutableStateOf<Float?>(null)

    internal val distance: Float?
        get() = when {
            originBottom == null -> fallback
            else -> heroBottom?.let { (originBottom - it).coerceAtLeast(0f) }
        }

    /** Put on the hero: its first resting bottom edge sets how far the drawer travels. */
    val heroModifier: Modifier
        get() = if (reduced || originBottom == null) {
            Modifier
        } else {
            Modifier.onGloballyPositioned { if (heroBottom == null) heroBottom = it.positionInRoot().y + it.size.height }
        }
}

/** The page's drawer slide. Call it where the cards are composed, with the vehicle the page shows. */
@Composable
fun rememberDrawerSlide(vehicleId: String): DrawerSlide {
    val reduced = rememberReducedMotion()
    val origins = LocalVehicleHeroOrigins.current
    val origin = remember(vehicleId) { origins?.take(vehicleId) }
    val fallback = with(LocalDensity.current) { DRAWER_FALLBACK_SLIDE.toPx() }
    val nav = LocalNavAnimatedScope.current
    val progress: State<Float> = if (nav == null || reduced) {
        rememberStatic(0f)
    } else {
        nav.transition.animateFloat(transitionSpec = { heroSpec() }, label = "drawer") { if (it == EnterExitState.Visible) 0f else 1f }
    }
    return remember(origin, fallback, progress) { DrawerSlide(reduced, origin, fallback, progress) }
}

/**
 * Slides this card with the page's drawer. Read in `graphicsLayer {}` only, so no frame recomposes. Until the hero
 * has been laid out the travel is unknown and a card still off is kept invisible rather than drawn in place.
 */
fun Modifier.drawerSlide(slide: DrawerSlide): Modifier = if (slide.reduced) this else graphicsLayer {
    val p = slide.offProgress.value
    if (p == 0f) return@graphicsLayer
    val d = slide.distance
    if (d == null) {
        alpha = 0f
        return@graphicsLayer
    }
    translationY = d * p
    alpha = ((1f - p) * DRAWER_ALPHA_RATE).coerceIn(0f, 1f)
}

/** How far the drawer rises when there is no Home card to follow. */
private val DRAWER_FALLBACK_SLIDE = 48.dp

/** The drawer is opaque by 40 % of the way, so it cross-fades with the leaving screen and doesn't flash. */
private const val DRAWER_ALPHA_RATE = 2.5f

/** [GlassCard]'s side margin. */
private val CARD_MARGIN = 16.dp

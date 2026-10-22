package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Perspective of the 3D tilt, in the platform camera unit (View's px ÷ dpi, so 8 = the classic 1280 dp default):
 * 7 ≈ the reference's `perspective: 1100px`.
 */
private const val CAMERA_DISTANCE = 7f

/** How far a layer at 1 dp of depth scales up, standing in for `translateZ` under that perspective (1 / 1100 dp). */
private const val DEPTH_SCALE_PER_DP = 1f / 1100f

/** Radius of the shine as a fraction of the card's diagonal; the reference fades to transparent at 45 %. */
private const val SHINE_RADIUS = 0.45f

/**
 * The depths (D-A30) the vehicle cards use, scaled from the reference's 40–95 px to a phone card: the photo sits
 * 24 dp *behind* the plate and slides the other way inside its frame, the plate · mileage line floats 16 dp, the
 * server name 24 dp, the title 32 dp, the status chip 40 dp, a figure on a tile 28 dp.
 */
object TiltDepths {
    val Photo: Dp = (-24).dp
    val Line: Dp = 16.dp
    val Subtitle: Dp = 24.dp
    val Title: Dp = 32.dp
    val Chip: Dp = 40.dp
    val Value: Dp = 28.dp
}

/** The card's [CardTilt], for the layers inside it. */
val LocalCardTilt = staticCompositionLocalOf<CardTilt?> { null }

/**
 * The "3D Tilt Parallax" card (D-A30): a [GlassPanel] that rotates up to [MAX_TILT_DEGREES] toward the pointer
 * (gyroscope, or the finger while pressed), carries a plate glow in [glow] and a radial shine that follows the
 * pointer, and lets its children float at their own depth through [TiltLayer] / [tiltLayer]. [rotate] false keeps
 * the plate and shine but no rotation or parallax, for content-dense cards. Under reduced motion (or when the
 * screen isn't resumed) the card is flat and the shine is off.
 *
 * The rotation is read inside `graphicsLayer`, the shine inside a cached draw lambda, so a frame of motion costs
 * no recomposition and allocates nothing.
 */
@Composable
fun TiltParallaxCard(
    modifier: Modifier = Modifier,
    tilt: CardTilt = rememberCardTilt(),
    glow: Color = LocalAuroraTint.current.color ?: MaterialTheme.colorScheme.primary,
    rotate: Boolean = true,
    shape: Shape = RoundedCornerShape(glass.radiusLarge),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    elevated: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalCardTilt provides tilt) {
        GlassPanel(
            modifier = modifier.then(if (rotate) Modifier.tiltRotation(tilt) else Modifier),
            shape = shape,
            onClick = onClick,
            onLongClick = onLongClick,
            contentPadding = contentPadding,
            glow = glow,
            shine = tilt,
            elevated = elevated,
            content = content,
        )
    }
}

/** [TiltParallaxCard] with the list cards' outer margin, the tilting counterpart of [GlassCard]. */
@Composable
fun TiltParallaxListCard(
    modifier: Modifier = Modifier,
    tilt: CardTilt = rememberCardTilt(),
    glow: Color = LocalAuroraTint.current.color ?: MaterialTheme.colorScheme.primary,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) = TiltParallaxCard(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    tilt = tilt,
    glow = glow,
    onClick = onClick,
    onLongClick = onLongClick,
    contentPadding = contentPadding,
    content = content,
)

/** The 3D rotation toward the pointer, with the reference's perspective. */
fun Modifier.tiltRotation(tilt: CardTilt): Modifier = if (!tilt.motion) this else graphicsLayer {
    rotationX = tilt.rotationX.value
    rotationY = tilt.rotationY.value
    cameraDistance = CAMERA_DISTANCE
}

/**
 * A layer inside a tilt-parallax card floating [depth] above its plate (negative: behind it, as a photo seen
 * through a window). Compose has no `translateZ`, so the layer is shifted toward the pointer by `depth · sin(angle)`
 * on each axis — exactly where a point at that depth lands under the card's rotation — and scaled by
 * `1 + depth / perspective`, the size it would have that much nearer the camera. A layer *behind* the plate
 * (negative depth: the photo in its frame) is instead scaled up just enough that its largest shift never shows an
 * edge. Reads the tilt in the layer lambda only; without a card tilt, or under reduced motion, it is a no-op.
 */
@Composable
fun Modifier.tiltLayer(depth: Dp): Modifier {
    val tilt = LocalCardTilt.current ?: return this
    if (!tilt.motion) return this
    return graphicsLayer {
        val depthPx = depth.toPx()
        val rx = Math.toRadians(tilt.rotationX.value.toDouble())
        val ry = Math.toRadians(tilt.rotationY.value.toDouble())
        translationX = (sin(ry) * depthPx).toFloat()
        translationY = (-sin(rx) * depthPx).toFloat()
        val scale = if (depthPx >= 0f) {
            1f + depth.value * DEPTH_SCALE_PER_DP
        } else {
            val reach = -depthPx * MAX_SIN
            1f + 2f * reach / minOf(size.width, size.height).coerceAtLeast(1f)
        }
        scaleX = scale
        scaleY = scale
    }
}

/** `sin(MAX_TILT_DEGREES)`: the farthest a layer travels per dp of depth. */
private val MAX_SIN = sin(Math.toRadians(MAX_TILT_DEGREES.toDouble())).toFloat()

/** A box floating at [depth] in the enclosing [TiltParallaxCard]; see [tiltLayer]. */
@Composable
fun TiltLayer(depth: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.tiltLayer(depth), content = content)
}

/**
 * The radial shine over a card's content: `radialGradient(center = pointer, shine → transparent at 45 %)`, screen
 * blended on dark. One brush per size, centred at the origin; a frame translates the canvas to the pointer rather
 * than building a shader. Skipped entirely while the strength is 0.
 */
fun Modifier.tiltShine(tilt: CardTilt, tokens: GlassTokens): Modifier = if (!tilt.motion) this else drawWithCache {
    val radius = hypot(size.width, size.height) * SHINE_RADIUS
    val brush = Brush.radialGradient(
        0f to tokens.shine,
        1f to Color.Transparent,
        center = Offset.Zero,
        radius = radius,
    )
    onDrawBehind {
        val strength = tilt.shine.value
        if (strength > 0.01f) {
            translate(tilt.px.value * size.width, tilt.py.value * size.height) {
                drawCircle(brush, radius, Offset.Zero, alpha = strength, blendMode = tokens.shineBlend)
            }
        }
    }
}

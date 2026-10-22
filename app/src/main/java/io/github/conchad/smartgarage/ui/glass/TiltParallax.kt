package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

/** Radius of the shine as a fraction of the frame's diagonal; the reference fades to transparent at 45 %. */
private const val SHINE_RADIUS = 0.45f

/**
 * How far across the frame the shine's centre travels for the full pointer range. D-A31 (2026-10-22): 40 % of the
 * earlier 0.6 (D-A30 was edge-to-edge). The shine follows the pointer, not the angle, so it isn't cut by the
 * [MAX_TILT_DEGREES] change on its own; at 0.12 the 45 %-radius blob would barely be seen to move.
 */
private const val SHINE_TRAVEL = 0.24f

/**
 * The depths the photo frames use (D-A31, 2026-10-22: 40 % of the earlier −14 / 14 / 19, themselves 60 % of D-A30):
 * the photo sits 6 dp *behind* the frame and slides the other way inside it, the server name floats 6 dp above,
 * the title 8 dp. A layer's shift is `depth · sin(angle)`, so the 8.4° → 1.68° cut already takes the parallax to
 * 20 % on its own; 40 % here (rather than 20 %) still lands at ≈ 0.18 dp of travel, under the old 20 % mark.
 */
object TiltDepths {
    val Photo: Dp = (-6).dp
    val Subtitle: Dp = 6.dp
    val Title: Dp = 8.dp
}

/** The frame's [CardTilt], for the layers inside it. */
val LocalCardTilt = staticCompositionLocalOf<CardTilt?> { null }

/**
 * The "3D Tilt Parallax" photo (D-A30, D-A31): a [MetallicFrame] that rotates up to [MAX_TILT_DEGREES] toward the
 * gyroscope pointer, carries a radial shine that follows it, and lets the photo and the name inside float at their
 * own depth through [TiltLayer] / [tiltLayer]. Only the photo moves (D-A31): the glass card around it, with its
 * rows, buttons and drop-down, is a plain [GlassCard] and stays flat. Under reduced motion (or when the screen
 * isn't resumed, or [tilt] doesn't follow the gyroscope) the frame is flat and the shine is off.
 *
 * The rotation is read inside `graphicsLayer`, the shine inside a cached draw lambda, so a frame of motion costs
 * no recomposition and allocates nothing.
 */
@Composable
fun TiltPhotoFrame(
    modifier: Modifier = Modifier,
    tilt: CardTilt = rememberCardTilt(),
    cornerRadius: Dp = glass.radiusMedium,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = glass
    CompositionLocalProvider(LocalCardTilt provides tilt) {
        MetallicFrame(modifier = modifier.tiltRotation(tilt), cornerRadius = cornerRadius) {
            content()
            // Its own layer: a frame of shine repaints this circle only, not the photo and text under it.
            Box(Modifier.matchParentSize().graphicsLayer().tiltShine(tilt, tokens))
        }
    }
}

/** The 3D rotation toward the pointer, with the reference's perspective. */
fun Modifier.tiltRotation(tilt: CardTilt): Modifier = if (!tilt.motion) this else graphicsLayer {
    rotationX = tilt.rotationX.value
    rotationY = tilt.rotationY.value
    cameraDistance = CAMERA_DISTANCE
}

/**
 * A layer inside a tilting frame floating [depth] above its plate (negative: behind it, as a photo seen through a
 * window). Compose has no `translateZ`, so the layer is shifted toward the pointer by `depth · sin(angle)` on each
 * axis — exactly where a point at that depth lands under the frame's rotation — and scaled by
 * `1 + depth / perspective`, the size it would have that much nearer the camera. A layer *behind* the plate
 * (negative depth: the photo in its frame) is instead scaled up just enough that its largest shift never shows an
 * edge. Reads the tilt in the layer lambda only; without a frame tilt, or under reduced motion, it is a no-op.
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

/** A box floating at [depth] in the enclosing [TiltPhotoFrame]; see [tiltLayer]. */
@Composable
fun TiltLayer(depth: Dp, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.tiltLayer(depth), content = content)
}

/**
 * The radial shine over a frame's content: `radialGradient(center = pointer, shine → transparent at 45 %)`, screen
 * blended on dark. The centre travels [SHINE_TRAVEL] of the frame around its middle for the full pointer range.
 * One brush per size, centred at the origin; a frame translates the canvas to the pointer rather than building a
 * shader. Skipped entirely while the strength is 0.
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
            val cx = (0.5f + (tilt.px.value - 0.5f) * SHINE_TRAVEL) * size.width
            val cy = (0.5f + (tilt.py.value - 0.5f) * SHINE_TRAVEL) * size.height
            translate(cx, cy) {
                drawCircle(brush, radius, Offset.Zero, alpha = strength, blendMode = tokens.shineBlend)
            }
        }
    }
}

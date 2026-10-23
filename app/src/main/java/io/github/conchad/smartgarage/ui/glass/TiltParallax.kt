package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Perspective of the 3D tilt, in the platform camera unit (View's px ÷ dpi, so 8 = the classic 1280 dp default):
 * 7 ≈ the reference's `perspective: 1100px`.
 */
private const val CAMERA_DISTANCE = 7f

/** How far a layer at 1 dp of depth scales up, standing in for `translateZ` under that perspective (1 / 1100 dp). */
private const val DEPTH_SCALE_PER_DP = 1f / 1100f

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
 * gyroscope pointer and lets the photo and the name inside float at their
 * own depth through [TiltLayer] / [tiltLayer]. Only the photo moves (D-A31): the glass card around it, with its
 * rows, buttons and drop-down, is a plain [GlassCard] and stays flat. Under reduced motion (or when the screen
 * isn't resumed, or [tilt] doesn't follow the gyroscope) the frame is flat. No shine over the photo (D-A46).
 *
 * The rotation is read inside `graphicsLayer`, so a frame of motion costs
 * no recomposition and allocates nothing.
 */
@Composable
fun TiltPhotoFrame(
    modifier: Modifier = Modifier,
    tilt: CardTilt = rememberCardTilt(),
    cornerRadius: Dp = glass.radiusMedium,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalCardTilt provides tilt) {
        MetallicFrame(modifier = modifier.tiltRotation(tilt), cornerRadius = cornerRadius) {
            content()
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

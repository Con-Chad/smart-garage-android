package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.tan

private val easeInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/**
 * The tint the shell's aurora should drift in, set by whichever glass screen is showing (the Dashboard sets the
 * current vehicle's tint). Null means "theme primary". Lives above the screens so the aurora runs behind the
 * floating bottom bar as well, not just inside one screen's bounds.
 *
 * The holder remembers which [AuroraTint] last set it: during a navigation transition the incoming screen sets its
 * tint while the outgoing one is still composed, and the outgoing one's dispose must not wipe it (that left the
 * vehicle page on the theme-primary fallback, a grey aurora over a green car).
 */
class AuroraTintHolder {
    var color: Color? by mutableStateOf(null)
        private set
    private var owner: Any? = null

    fun set(owner: Any, tint: Color?) {
        this.owner = owner
        color = tint
    }

    /** Clears the tint only if [owner] is still the one that set it. */
    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            color = null
        }
    }
}

val LocalAuroraTint = staticCompositionLocalOf { AuroraTintHolder() }

/** Sets the shell aurora's tint while this composable is in the tree; cleared again when it leaves, unless a newer screen has taken it over. */
@Composable
fun AuroraTint(tint: Color?) {
    val holder = LocalAuroraTint.current
    val owner = remember { Any() }
    LaunchedEffect(tint) { holder.set(owner, tint) }
    DisposableEffect(Unit) { onDispose { holder.clear(owner) } }
}

/**
 * The "Aurora Borealis" page background: three wide, soft ribbons drifting, skewing and stretching over the page
 * color at 9 s / 11 s / 13 s, alternating, under a veil that darkens the lower half (D-A24).
 *
 * Instead of `filter: blur(34px)` each ribbon is two overlapping radial gradients that fade to transparent, which
 * costs a gradient fill per ribbon rather than a full-screen blur pass. Screen-blended on dark like the snippet.
 * The ribbon palette comes from [tint] ([VehicleTint.palette]) and cross-fades when the tint changes, so a green
 * car gives a green aurora and a silver one a steel aurora. Composed without animation when the lifecycle isn't
 * RESUMED or the system animation scale is 0: one static frame, no frame callbacks.
 */
@Composable
fun AuroraBackground(tint: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val tokens = glass
    val animate = rememberAmbientMotion()
    val palette = VehicleTint.palette(tint)
    val c0 by animateColorAsState(palette[0], tween(800), label = "aurora0")
    val c1 by animateColorAsState(palette[1], tween(800), label = "aurora1")
    val c2 by animateColorAsState(palette[2], tween(800), label = "aurora2")

    val drift: List<State<Float>> = if (animate) {
        val transition = rememberInfiniteTransition(label = "aurora")
        listOf(9_000, 11_000, 13_000).map { duration ->
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(duration, easing = easeInOut), RepeatMode.Reverse),
                label = "ribbon$duration",
            )
        }
    } else {
        listOf(rememberStatic(0.35f), rememberStatic(0.6f), rememberStatic(0.5f))
    }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
            drawRect(tokens.pageBackground)
            val alpha = tokens.auroraAlpha
            // Keyframes from the snippet: translate (fractions of width/height), skewY (degrees), scaleY.
            ribbon(drift[0].value, top = -0.10f, colors = c0 to c1, alpha = alpha * 1.0f, blend = tokens.auroraBlend,
                from = Key(-0.08f, 0f, -4f, 0.9f), to = Key(0.10f, 0.08f, 5f, 1.15f))
            ribbon(drift[1].value, top = 0.18f, colors = c1 to c2, alpha = alpha * 0.82f, blend = tokens.auroraBlend,
                from = Key(0.06f, 0.04f, 6f, 1.1f), to = Key(-0.12f, -0.06f, -5f, 0.85f))
            ribbon(drift[2].value, top = 0.40f, colors = c2 to c0, alpha = alpha * 0.65f, blend = tokens.auroraBlend,
                from = Key(-0.05f, 0.02f, -3f, 1f), to = Key(0.14f, 0.10f, 7f, 1.25f))
            drawRect(
                Brush.verticalGradient(
                    0.4f to Color.Transparent,
                    1f to tokens.veil,
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }
        content()
    }
}

private data class Key(val tx: Float, val ty: Float, val skewDeg: Float, val scaleY: Float)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * One ribbon: an ellipse 160 % of the width and 70 % of the height, left at -30 %, drawn as two soft radial blobs
 * (left color at 35 %, right color at 65 %) so the gradient runs across it and fades to nothing at the edges.
 */
private fun DrawScope.ribbon(t: Float, top: Float, colors: Pair<Color, Color>, alpha: Float, blend: BlendMode, from: Key, to: Key) {
    val w = size.width
    val h = size.height
    val tx = lerp(from.tx, to.tx, t) * w
    val ty = lerp(from.ty, to.ty, t) * h
    val skew = lerp(from.skewDeg, to.skewDeg, t)
    val sy = lerp(from.scaleY, to.scaleY, t)
    val ribbonW = w * 1.6f
    val ribbonH = h * 0.7f
    val left = -0.3f * w
    val cy = top * h + ribbonH / 2f
    val matrix = Matrix().also { m ->
        m.translate(tx, ty)
        m.translate(w / 2f, cy)
        m.scale(1f, sy)
        m.values[Matrix.SkewY] = tan(Math.toRadians(skew.toDouble())).toFloat()
        m.translate(-w / 2f, -cy)
    }
    withTransform({ transform(matrix) }) {
        val radius = ribbonH * 0.62f
        blob(Offset(left + ribbonW * 0.38f, cy), radius, colors.first.copy(alpha = alpha), blend, Size(ribbonW, ribbonH))
        blob(Offset(left + ribbonW * 0.64f, cy), radius, colors.second.copy(alpha = alpha), blend, Size(ribbonW, ribbonH))
    }
}

private fun DrawScope.blob(center: Offset, radius: Float, color: Color, blend: BlendMode, bounds: Size) {
    // Wider than tall, like the ellipse: stretch the circle horizontally around its center.
    val stretch = (bounds.width / bounds.height) * 0.75f
    withTransform({
        translate(center.x, center.y)
        scale(stretch, 1f, Offset.Zero)
        translate(-center.x, -center.y)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color,
                0.45f to color.copy(alpha = color.alpha * 0.55f),
                1f to color.copy(alpha = 0f),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            blendMode = blend,
        )
    }
}

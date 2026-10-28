package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import kotlin.math.tan
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val easeInOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/**
 * The tint the shell's aurora should drift in, set by whichever glass screen is showing (the Dashboard sets the
 * current vehicle's tint). Null means "theme primary". Lives above the screens so the aurora runs behind the
 * floating bottom bar as well, not just inside one screen's bounds.
 *
 * The holder remembers which [AuroraTint] last set it: during a navigation transition the incoming screen sets its
 * tint while the outgoing one is still composed, and the outgoing one's dispose must not wipe it (that left the
 * vehicle page on the theme-primary fallback, a grey aurora over a green car). The reverse leak — a screen's tint
 * outliving the screen because NavHost kept its entry composed — is closed in the shell, which only honours
 * [color] on the glass routes and records what it drew in [shown] (D-A32).
 */
class AuroraTintHolder {
    var color: Color? by mutableStateOf(null)
        private set
    private var owner: Any? = null

    /**
     * What the shell actually draws the aurora in (D-A32): the screen's [color] on the glass routes (Home, Vehicles,
     * the vehicle page), the tab's fallback everywhere else, whatever [color] still says. Written by the shell each
     * time the destination changes; null outside the shell (the setup flows), where [effective] falls back to [color].
     */
    var shown: Color? by mutableStateOf(null)

    /** The tint the plates and the fan should agree with: [shown] when the shell has decided, else [color]. */
    val effective: Color? get() = shown ?: color

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
 *
 * Cost: the six blobs are each about a screen of blended gradient, so the aurora is drawn into an offscreen layer
 * at 1/[AURORA_DOWNSCALE] of the screen on each axis and scaled back up (1/16 of the pixels; the ribbons are the
 * software stand-in for a 34 px blur, so bilinear upscaling of them is indistinguishable). The gradient brushes
 * and the veil are built once per size and palette, so a frame reuses their shaders instead of creating seven.
 * The drift advances at most every [AURORA_FRAME_MS] (~30 Hz) from the shared ambient clock; the ribbons move
 * about 25 px/s, under a pixel a frame at that rate, so the panel's 60 or 120 Hz would buy nothing visible.
 */
@Composable
fun AuroraBackground(
    tint: Color,
    modifier: Modifier = Modifier,
    /** Where the aurora publishes its colour map for [auroraInk] (D-A46); one is made for [content] when null. */
    luma: AuroraLumaHolder? = null,
    content: @Composable () -> Unit = {},
) {
    val tokens = glass
    val animate = rememberAmbientMotion()
    val palette = VehicleTint.palette(tint)
    // Reduced motion swaps the tint at once instead of cross-fading it (D-A44).
    val fade = tween<Color>(Motion.TINT_MS).orSnap(rememberReducedMotion())
    val c0 = animateColorAsState(palette[0], fade, label = "aurora0")
    val c1 = animateColorAsState(palette[1], fade, label = "aurora1")
    val c2 = animateColorAsState(palette[2], fade, label = "aurora2")
    // Null when static: the ribbons then sit at the parked keyframe fractions below.
    val clock: State<Long>? = if (animate) rememberAmbientClock(AURORA_FRAME_MS) else null
    val holder = luma ?: remember { AuroraLumaHolder() }
    var auroraSize by remember { mutableStateOf(IntSize.Zero) }

    // D-A46: the colour map behind the adaptive ink, rendered from the target palette (not the cross-fade) once per
    // palette, size and theme, off the main thread. The ink eases on its own low-pass.
    val p0 = palette[0]
    val p1 = palette[1]
    val p2 = palette[2]
    // D-A88: the map's key, announced to the holder on apply (before this frame's layout and draw), so ink attaching
    // in this frame knows the map it would read is still the previous palette's.
    val mapKey = remember(p0, p1, p2, auroraSize, tokens) { Any() }
    SideEffect { holder.want(mapKey) }
    LaunchedEffect(mapKey) {
        val target = auroraSize
        if (target.width <= 0 || target.height <= 0) return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) { renderLumaMap(target, p0, p1, p2, tokens) }
        holder.publish(mapKey, rendered)
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val p = it.positionInRoot()
                holder.left = p.x
                holder.top = p.y
                holder.width = it.size.width.toFloat()
                holder.height = it.size.height.toFloat()
                auroraSize = it.size
            },
    ) {
        Spacer(
            Modifier
                .downscaledLayer(AURORA_DOWNSCALE)
                .clearAndSetSemantics {}
                .drawWithCache {
                    // Re-run on resize and whenever a cross-fading color changes (the colors are read here, not in the
                    // draw lambda, so the 800 ms fade rebuilds the brushes and the steady state never does).
                    val w = size.width
                    val h = size.height
                    val scene = AuroraScene(w, h, c0.value, c1.value, c2.value, tokens)
                    val matrix = Matrix()
                    onDrawBehind {
                        val elapsed = clock?.value
                        scene.draw(this, matrix) { ribbon -> if (elapsed == null) ribbon.parked else driftAt(elapsed, ribbon.durationMs) }
                    }
                },
        )
        // D-A87: screen-resolution dither over the upscaled layer, so its 8-bit steps don't show as contours.
        Spacer(Modifier.fillMaxSize().clearAndSetSemantics {}.auroraDither())
        CompositionLocalProvider(LocalAuroraLuma provides holder, content = content)
    }
}

/** The page colour, the three ribbons and the veil at one size and palette: what one aurora frame draws. */
private class AuroraScene(
    private val w: Float,
    private val h: Float,
    c0: Color,
    c1: Color,
    c2: Color,
    private val tokens: GlassTokens,
) {
    private val ribbons: List<PreparedRibbon>
    private val veil: Brush

    init {
        val alpha = tokens.auroraAlpha
        ribbons = listOf(
            Ribbon(top = -0.10f, colors = c0 to c1, alpha = alpha * 1.0f, durationMs = 9_000, parked = 0.35f,
                from = Key(-0.08f, 0f, -4f, 0.9f), to = Key(0.10f, 0.08f, 5f, 1.15f)),
            Ribbon(top = 0.18f, colors = c1 to c2, alpha = alpha * 0.82f, durationMs = 11_000, parked = 0.6f,
                from = Key(0.06f, 0.04f, 6f, 1.1f), to = Key(-0.12f, -0.06f, -5f, 0.85f)),
            Ribbon(top = 0.40f, colors = c2 to c0, alpha = alpha * 0.65f, durationMs = 13_000, parked = 0.5f,
                from = Key(-0.05f, 0.02f, -3f, 1f), to = Key(0.14f, 0.10f, 7f, 1.25f)),
        ).map { it.prepare(w, h) }
        veil = Brush.verticalGradient(
            0.4f to Color.Transparent,
            1f to tokens.veil,
            startY = 0f,
            endY = h,
        )
    }

    /** Draws the scene at its own size [w] × [h] (the caller scales); [drift] gives each ribbon's fraction. */
    inline fun draw(scope: DrawScope, matrix: Matrix, drift: (PreparedRibbon) -> Float) {
        val size = Size(w, h)
        scope.drawRect(tokens.pageBackground, size = size)
        for (ribbon in ribbons) ribbon.draw(scope, w, h, drift(ribbon), matrix, tokens.auroraBlend)
        scope.drawRect(veil, size = size)
    }
}

/**
 * Renders the aurora at [size] into an [AuroraLumaMap.COLS] × [AuroraLumaMap.ROWS] software bitmap at
 * [LUMA_PHASES] drift fractions and averages them (D-A46). A few hundred pixels per pass: well under a millisecond.
 */
private fun renderLumaMap(size: IntSize, c0: Color, c1: Color, c2: Color, tokens: GlassTokens): AuroraLumaMap {
    val cols = AuroraLumaMap.COLS
    val rows = AuroraLumaMap.ROWS
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val scene = AuroraScene(w, h, c0, c1, c2, tokens)
    val bitmap = ImageBitmap(cols, rows, ImageBitmapConfig.Argb8888)
    val canvas = Canvas(bitmap)
    val drawScope = CanvasDrawScope()
    val matrix = Matrix()
    val pixels = IntArray(cols * rows)
    val sum = FloatArray(cols * rows * 3)
    for (i in 0 until LUMA_PHASES) {
        val t = i / (LUMA_PHASES - 1f)
        drawScope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(cols.toFloat(), rows.toFloat())) {
            scale(cols / w, rows / h, pivot = Offset.Zero) {
                scene.draw(this, matrix) { t }
            }
        }
        bitmap.readPixels(pixels)
        for (p in pixels.indices) {
            val argb = pixels[p]
            sum[p * 3] += ((argb shr 16) and 0xFF) / 255f
            sum[p * 3 + 1] += ((argb shr 8) and 0xFF) / 255f
            sum[p * 3 + 2] += (argb and 0xFF) / 255f
        }
    }
    for (i in sum.indices) sum[i] /= LUMA_PHASES
    return AuroraLumaMap(cols, rows, sum)
}

/** Drift fractions averaged into the colour map: both ends of the loop and two between. */
private const val LUMA_PHASES = 4

/** How much smaller than the screen the aurora layer is drawn, per axis. */
private const val AURORA_DOWNSCALE = 4

/** Minimum time between drift updates; ~30 Hz. */
private const val AURORA_FRAME_MS = 33L

/**
 * Lays the content out at 1/[factor] of the available size and scales its layer back up to fill it. With an
 * offscreen compositing layer the content is rasterised at the small size and composited as one textured quad,
 * so the fill-rate work shrinks by `factor²`. Falls back to normal layout under unbounded constraints.
 */
private fun Modifier.downscaledLayer(factor: Int): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val layerWidth = ((width + factor - 1) / factor).coerceAtLeast(1)
    val layerHeight = ((height + factor - 1) / factor).coerceAtLeast(1)
    val placeable = measurable.measure(Constraints.fixed(layerWidth, layerHeight))
    layout(width, height) {
        placeable.placeWithLayer(0, 0) {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = width.toFloat() / layerWidth
            scaleY = height.toFloat() / layerHeight
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }
}

/**
 * Where a ribbon is on its `tween(duration, easeInOut)` + `RepeatMode.Reverse` loop at [elapsedMs]: forward on even
 * iterations, back on odd ones, the same curve `infiniteRepeatable` produced.
 */
private fun driftAt(elapsedMs: Long, durationMs: Int): Float {
    val iteration = elapsedMs / durationMs
    val fraction = (elapsedMs % durationMs).toFloat() / durationMs
    return easeInOut.transform(if (iteration % 2 == 0L) fraction else 1f - fraction)
}

private data class Key(val tx: Float, val ty: Float, val skewDeg: Float, val scaleY: Float)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/** A ribbon's keyframes from the snippet: translate (fractions of width/height), skewY (degrees), scaleY. */
private class Ribbon(
    val top: Float,
    val colors: Pair<Color, Color>,
    val alpha: Float,
    val durationMs: Int,
    /** The drift fraction shown when motion is off. */
    val parked: Float,
    val from: Key,
    val to: Key,
) {
    fun prepare(w: Float, h: Float): PreparedRibbon {
        // An ellipse 160 % of the width and 70 % of the height, left at -30 %, drawn as two soft radial blobs (left
        // color at 35 %, right color at 65 %) so the gradient runs across it and fades to nothing at the edges.
        val ribbonW = w * 1.6f
        val ribbonH = h * 0.7f
        val left = -0.3f * w
        val cy = top * h + ribbonH / 2f
        val radius = ribbonH * 0.62f
        // Wider than tall, like the ellipse: the circle is stretched horizontally around its center.
        val stretch = (ribbonW / ribbonH) * 0.75f
        return PreparedRibbon(
            this,
            cy = cy,
            radius = radius,
            stretch = stretch,
            blobs = listOf(
                Blob(Offset(left + ribbonW * 0.38f, cy), radialBlob(Offset(left + ribbonW * 0.38f, cy), radius, colors.first.copy(alpha = alpha))),
                Blob(Offset(left + ribbonW * 0.64f, cy), radialBlob(Offset(left + ribbonW * 0.64f, cy), radius, colors.second.copy(alpha = alpha))),
            ),
        )
    }

    private fun radialBlob(center: Offset, radius: Float, color: Color): Brush = Brush.radialGradient(
        0f to color,
        0.45f to color.copy(alpha = color.alpha * 0.55f),
        1f to color.copy(alpha = 0f),
        center = center,
        radius = radius,
    )
}

private class Blob(val center: Offset, val brush: Brush)

/** A [Ribbon] with its size-dependent geometry and brushes resolved; only the drift fraction varies per frame. */
private class PreparedRibbon(
    private val ribbon: Ribbon,
    private val cy: Float,
    private val radius: Float,
    private val stretch: Float,
    private val blobs: List<Blob>,
) {
    val parked: Float get() = ribbon.parked
    val durationMs: Int get() = ribbon.durationMs

    fun draw(scope: DrawScope, w: Float, h: Float, t: Float, matrix: Matrix, blend: BlendMode) {
        val from = ribbon.from
        val to = ribbon.to
        val tx = lerp(from.tx, to.tx, t) * w
        val ty = lerp(from.ty, to.ty, t) * h
        val skew = lerp(from.skewDeg, to.skewDeg, t)
        val sy = lerp(from.scaleY, to.scaleY, t)
        for (blob in blobs) {
            // The ribbon's translate / skew / scale about its center, then the blob's horizontal stretch about its own
            // center, folded into one matrix (same operation order as nested canvas transforms would apply).
            matrix.reset()
            matrix.translate(tx, ty)
            matrix.translate(w / 2f, cy)
            matrix.scale(1f, sy)
            matrix.values[Matrix.SkewY] = tan(Math.toRadians(skew.toDouble())).toFloat()
            matrix.translate(-w / 2f, -cy)
            matrix.translate(blob.center.x, blob.center.y)
            matrix.scale(stretch, 1f)
            matrix.translate(-blob.center.x, -blob.center.y)
            scope.withTransform({ transform(matrix) }) {
                drawCircle(brush = blob.brush, radius = radius, center = blob.center, blendMode = blend)
            }
        }
    }
}

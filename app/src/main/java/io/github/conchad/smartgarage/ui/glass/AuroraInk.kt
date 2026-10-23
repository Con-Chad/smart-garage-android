package io.github.conchad.smartgarage.ui.glass

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/*
 * Adaptive ink (D-A46): text that sits directly on the aurora (screen titles, group headers, section labels, the
 * greeting, Home's pills) takes its colour from the aurora behind it, white over the dark lower part of a gradient
 * and near-black over a pale upper part, passing through grey in a narrow band between, and following the text as
 * it scrolls.
 *
 * Cost by design:
 * - The aurora's colour is not read back from the GPU. [AuroraBackground] renders its own scene once per palette,
 *   size and theme into a [AuroraLumaMap.COLS] × [AuroraLumaMap.ROWS] software bitmap off the main thread, averaged
 *   over four drift phases (the ribbons drift slowly; the average is what the eye reads), and publishes it in
 *   [AuroraLumaHolder].
 * - Each ink node learns its on-screen top and bottom from `onGloballyPositioned` (a scroll re-places it), samples
 *   the map there (bilinear, a few multiplies) and eases toward the result with a first-order low-pass
 *   ([Motion.INK_SMOOTH_MS]) on a frame loop that runs only while it is settling.
 * - The colour is applied in the draw phase: the glyphs draw into the node's offscreen layer and one rect of a
 *   vertical light→dark gradient is composited `SrcIn`, its shader reused and re-aimed with a local matrix, so a
 *   tall block shades across the aurora too. No recomposition, no relayout and no allocation per frame.
 */

/** The pure mapping from a backdrop's luminance to the ink (unit-tested). */
object AdaptiveInk {
    /** WCAG AA for body text: the ink at either end of the band clears this against its backdrop. */
    const val TEXT_MIN_CONTRAST = 4.5f

    /** WCAG's floor for UI components and bold labels: the pills (D-A43 kept white wherever it clears 3:1). */
    const val PILL_MIN_CONTRAST = 3f

    /**
     * The grey band's width in backdrop luminance. Any grey is low-contrast on a backdrop of its own luminance, so
     * the band is kept thin: the ink still eases white → grey → black with no step (and the low-pass smooths it in
     * time), but only a sliver of backdrops ever gets grey. The phone test (a mid-green Home) showed a wide band puts
     * whole headers in grey on exactly the mid-tones an aurora is made of.
     */
    const val BAND = 0.024f

    /** WCAG relative luminance of an sRGB colour, components 0..1. */
    fun relativeLuminance(r: Float, g: Float, b: Float): Float = 0.2126f * lin(r) + 0.7152f * lin(g) + 0.0722f * lin(b)

    private fun lin(c: Float): Float = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    /** The backdrop on which [light] and [dark] ink contrast equally: either side of it one of them is the better. */
    fun crossover(lightLum: Float, darkLum: Float): Float = kotlin.math.sqrt((lightLum + 0.05f) * (darkLum + 0.05f)) - 0.05f

    /** The brightest backdrop [light] ink still clears [minContrast] on. */
    fun lightLimit(lightLum: Float, minContrast: Float): Float = (lightLum + 0.05f) / minContrast - 0.05f

    /** The darkest backdrop [dark] ink still clears [minContrast] on. */
    fun darkLimit(darkLum: Float, minContrast: Float): Float = minContrast * (darkLum + 0.05f) - 0.05f

    /**
     * The grey band as `[lo, hi]` in backdrop luminance: light ink at or below `lo`, dark at or above `hi`.
     * - No backdrop suits both inks at [minContrast] (4.5:1 for text with these inks: light reaches it up to 0.166,
     *   dark from 0.243): the band is [BAND] wide around the [crossover] (0.20), so each side gets whichever ink
     *   contrasts more, the best any ink can do there (≥ 4:1 at the band's edges, ≥ 4.5:1 outside the gap).
     * - Both suit a range (3:1 for the pills): light is preferred (D-A43), so the band ends where light stops
     *   clearing the target, [BAND] wide, never below where dark clears it.
     */
    fun band(lightLum: Float, darkLum: Float, minContrast: Float): Pair<Float, Float> {
        val light = lightLimit(lightLum, minContrast)
        val dark = darkLimit(darkLum, minContrast)
        if (light <= dark) {
            val c = crossover(lightLum, darkLum)
            return (c - BAND / 2f) to (c + BAND / 2f)
        }
        return maxOf(dark, light - BAND) to light
    }

    /**
     * 0 = light ink, 1 = dark ink, for a backdrop of luminance [backdrop]. Inside the band it is a smoothstep on
     * `ln(L + 0.05)`, the axis contrast ratios live on, so the grey passes evenly in perceived contrast.
     */
    fun mix(backdrop: Float, lo: Float, hi: Float): Float {
        if (backdrop <= lo) return 0f
        if (backdrop >= hi) return 1f
        val a = ln(lo + 0.05f)
        val b = ln(hi + 0.05f)
        val t = ((ln(backdrop + 0.05f) - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Where the ink comes to rest for a backdrop whose continuous [mix] this is, given where it rests now
     * ([previous], 0 or 1): light or dark, never grey, switching only once the mix is past [HYSTERESIS] of the way,
     * so a backdrop hovering at the crossover can't make it flicker. The grey of the band shows only in transit.
     */
    fun settle(mix: Float, previous: Float): Float = when {
        mix >= 1f - HYSTERESIS -> 1f
        mix <= HYSTERESIS -> 0f
        else -> if (previous >= 0.5f) 1f else 0f
    }

    /** How far into the band [settle] waits before switching. */
    const val HYSTERESIS = 0.3f

    /** The ink at [mix]: a straight sRGB blend from [light] through grey to [dark], as the gradient shader draws it. */
    fun ink(mix: Float, light: Color, dark: Color): Color {
        val m = mix.coerceIn(0f, 1f)
        return Color(
            red = light.red + (dark.red - light.red) * m,
            green = light.green + (dark.green - light.green) * m,
            blue = light.blue + (dark.blue - light.blue) * m,
        )
    }
}

/**
 * The aurora's colour on a coarse grid ([cols] × [rows] cells, sRGB 0..1, three floats per cell, row-major), the
 * average of its drift. Sampled bilinearly at a fraction of the aurora's bounds.
 */
@Immutable
class AuroraLumaMap(val cols: Int, val rows: Int, private val rgb: FloatArray) {
    init {
        require(cols > 0 && rows > 0 && rgb.size == cols * rows * 3)
    }

    /**
     * The relative luminance at ([xFraction], [yFraction]) of the aurora, with [overlay] (a pill's fill, a scrolled
     * bar's glass; transparent for none) composited over it in sRGB, as the panels draw.
     */
    fun luminanceAt(xFraction: Float, yFraction: Float, overlay: Color = Color.Transparent): Float {
        val fx = (xFraction.coerceIn(0f, 1f) * cols - 0.5f).coerceIn(0f, (cols - 1).toFloat())
        val fy = (yFraction.coerceIn(0f, 1f) * rows - 0.5f).coerceIn(0f, (rows - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = minOf(x0 + 1, cols - 1)
        val y1 = minOf(y0 + 1, rows - 1)
        val tx = fx - x0
        val ty = fy - y0
        var r = channel(0, x0, y0, x1, y1, tx, ty)
        var g = channel(1, x0, y0, x1, y1, tx, ty)
        var b = channel(2, x0, y0, x1, y1, tx, ty)
        val a = overlay.alpha
        if (a > 0f) {
            r = overlay.red * a + r * (1f - a)
            g = overlay.green * a + g * (1f - a)
            b = overlay.blue * a + b * (1f - a)
        }
        return AdaptiveInk.relativeLuminance(r, g, b)
    }

    private fun channel(c: Int, x0: Int, y0: Int, x1: Int, y1: Int, tx: Float, ty: Float): Float {
        val top = at(x0, y0, c) + (at(x1, y0, c) - at(x0, y0, c)) * tx
        val bottom = at(x0, y1, c) + (at(x1, y1, c) - at(x0, y1, c)) * tx
        return top + (bottom - top) * ty
    }

    private fun at(x: Int, y: Int, c: Int): Float = rgb[(y * cols + x) * 3 + c]

    companion object {
        /** The grid the aurora is rendered into: enough for its soft ribbons, a few hundred bytes. */
        const val COLS = 12
        const val ROWS = 96
    }
}

/**
 * Where the shell's aurora publishes its [AuroraLumaMap] and its bounds in the root, for the ink nodes. [map] is
 * snapshot state, so a new palette re-aims every ink; the bounds are plain fields (written on layout, read on the
 * nodes' next placement).
 */
@Stable
class AuroraLumaHolder {
    var map: AuroraLumaMap? by mutableStateOf(null)
    internal var left = 0f
    internal var top = 0f
    internal var width = 0f
    internal var height = 0f
}

/** The aurora behind this screen's content; null where there is none (previews, tests): ink then stays as composed. */
val LocalAuroraLuma = staticCompositionLocalOf<AuroraLumaHolder?> { null }

/**
 * How a piece of text on the aurora is inked: the target contrast and what lies between it and the aurora
 * ([overlay], read in the node, so a state it reads re-aims the ink without recomposing).
 */
@Stable
class AuroraInkSpec(val minContrast: Float, val overlay: () -> Color = { Color.Transparent }) {
    companion object {
        /** Titles and labels straight on the aurora. */
        val Text = AuroraInkSpec(AdaptiveInk.TEXT_MIN_CONTRAST)
    }
}

/**
 * Set where everything in a slot sits on the aurora (a top bar's title): [MetallicText] and [onAuroraInk] inside it
 * pick it up. Null elsewhere: text on cards keeps its card-based colour.
 */
val LocalAuroraInk = staticCompositionLocalOf<AuroraInkSpec?> { null }

/**
 * Inks this content by the aurora behind it (D-A46). Put it last in a chain (after paddings and surfaces), so only
 * the glyphs and icons drawn inside are recoloured, not a pill's fill or its ripple. With [sheen] the metallic
 * title's band (D-A24, D-A38) sweeps over the ink. Light and dark ink are [GlassTokens.onPhoto] and
 * [GlassTokens.onPale]. A no-op where there is no aurora map.
 */
fun Modifier.auroraInk(spec: AuroraInkSpec = AuroraInkSpec.Text, sheen: State<Float>? = null): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .then(AuroraInkElement(spec, sheen))

private data class AuroraInkElement(val spec: AuroraInkSpec, val sheen: State<Float>?) : ModifierNodeElement<AuroraInkNode>() {
    override fun create() = AuroraInkNode(spec, sheen)

    override fun update(node: AuroraInkNode) {
        node.update(spec, sheen)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "auroraInk"
    }
}

private class AuroraInkNode(
    private var spec: AuroraInkSpec,
    private var sheen: State<Float>?,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode {

    // Placement in the root, from the last layout.
    private var centerX = Float.NaN
    private var top = 0f
    private var bottom = 0f

    // Targets and the eased values the draw reads (0 = light ink, 1 = dark), and whether any map applies.
    private var targetTop = 0f
    private var targetBottom = 0f
    private var mixTop = 0f
    private var mixBottom = 0f
    private var active = false
    private val tallPx get() = TALL_BLOCK_DP * currentValueOf(androidx.compose.ui.platform.LocalDensity).density
    private var settled = false
    private var easing: Job? = null

    private var light = Color.White
    private var dark = Color.Black
    private var tokens: GlassTokens? = null

    // Draw state reused every frame: one paint, one 0..1 gradient re-aimed by a local matrix.
    private val paint = Paint().apply { blendMode = android.graphics.BlendMode.SRC_IN }
    private var shader: LinearGradient? = null
    private var shaderLight = 0
    private var shaderDark = 0
    private val matrix = android.graphics.Matrix()

    // The sheen band, rebuilt only when the size changes.
    private var bandBrush: Brush? = null
    private var bandSize = Size.Zero
    private var bandTravel = 0f

    fun update(spec: AuroraInkSpec, sheen: State<Float>?) {
        this.spec = spec
        this.sheen = sheen
        retarget()
        invalidateDraw()
    }

    override fun onAttach() {
        settled = false
        retarget()
    }

    override fun onDetach() {
        easing = null
    }

    override fun onObservedReadsChanged() {
        retarget()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        val h = coordinates.size.height
        val cx = p.x + coordinates.size.width / 2f
        if (cx == centerX && p.y == top && p.y + h == bottom) return
        centerX = cx
        top = p.y
        bottom = p.y + h
        retarget()
    }

    /** Re-reads the map, the theme and the overlay (observed), samples the targets and starts easing toward them. */
    private fun retarget() {
        if (!isAttached) return
        var map: AuroraLumaMap? = null
        var holder: AuroraLumaHolder? = null
        var overlay = Color.Transparent
        observeReads {
            holder = currentValueOf(LocalAuroraLuma)
            map = holder?.map
            tokens = currentValueOf(LocalGlassTokens)
            overlay = spec.overlay()
        }
        val t = tokens ?: return
        light = t.onPhoto
        dark = t.onPale
        val m = map
        val bounds = holder
        if (m == null || bounds == null || centerX.isNaN() || bounds.width <= 0f || bounds.height <= 0f) {
            if (active) {
                active = false
                invalidateDraw()
            }
            return
        }
        val (lo, hi) = AdaptiveInk.band(light.luminanceOf(), dark.luminanceOf(), spec.minContrast)
        val x = (centerX - bounds.left) / bounds.width
        val mixTopRaw = AdaptiveInk.mix(m.luminanceAt(x, (top - bounds.top) / bounds.height, overlay), lo, hi)
        val mixBottomRaw = AdaptiveInk.mix(m.luminanceAt(x, (bottom - bounds.top) / bounds.height, overlay), lo, hi)
        // Grey is only ever passed through, never rested on (D-A46, phone test: a mid-green aurora sat right on the
        // crossover and left a whole line grey on green). Each edge settles on light or dark with hysteresis, and
        // the low-pass carries the ink through grey as the aurora under it crosses over. A short block (a line or
        // two) takes one decision from its centre, so it never shades top-light / bottom-dark.
        if (bottom - top < tallPx) {
            val c = AdaptiveInk.settle((mixTopRaw + mixBottomRaw) / 2f, targetTop)
            targetTop = c
            targetBottom = c
        } else {
            targetTop = AdaptiveInk.settle(mixTopRaw, targetTop)
            targetBottom = AdaptiveInk.settle(mixBottomRaw, targetBottom)
        }
        if (!active || !settled) {
            // The first sample lands at once: a title doesn't fade in from white on every screen it opens.
            mixTop = targetTop
            mixBottom = targetBottom
            active = true
            settled = true
            invalidateDraw()
            return
        }
        if (abs(targetTop - mixTop) < SNAP && abs(targetBottom - mixBottom) < SNAP) return
        if (easing?.isActive == true) return
        easing = coroutineScope.launch {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                // A first-order low-pass: the same easing whatever the frame rate.
                val k = 1f - exp(-((now - last) / 1_000_000f) / Motion.INK_SMOOTH_MS)
                last = now
                mixTop += (targetTop - mixTop) * k
                mixBottom += (targetBottom - mixBottom) * k
                val done = abs(targetTop - mixTop) < SNAP && abs(targetBottom - mixBottom) < SNAP
                if (done) {
                    mixTop = targetTop
                    mixBottom = targetBottom
                }
                invalidateDraw()
                if (done) break
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!active || size.width <= 0f || size.height <= 0f) return
        drawInk()
        val phase = sheen ?: return
        drawSheen(phase.value)
    }

    private fun ContentDrawScope.drawInk() {
        val l = light.toArgb()
        val d = dark.toArgb()
        if (abs(mixBottom - mixTop) < FLAT) {
            paint.shader = null
            paint.color = AdaptiveInk.ink((mixTop + mixBottom) / 2f, light, dark).toArgb()
        } else {
            var s = shader
            if (s == null || shaderLight != l || shaderDark != d) {
                // Light at y = 0, dark at y = 1, clamped: the local matrix maps the text's top to mixTop and its
                // bottom to mixBottom, so the shader paints the ink of every row in between.
                s = LinearGradient(0f, 0f, 0f, 1f, l, d, Shader.TileMode.CLAMP)
                shader = s
                shaderLight = l
                shaderDark = d
            }
            val h = size.height
            val span = mixBottom - mixTop
            matrix.reset()
            matrix.setScale(1f, h / span)
            matrix.postTranslate(0f, -mixTop * h / span)
            s.setLocalMatrix(matrix)
            paint.shader = s
        }
        drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
    }

    /** The metallic title's band, as [metallicSheen] slides it, over the glyphs only (`SrcAtop`). */
    private fun ContentDrawScope.drawSheen(phase: Float) {
        val t = tokens ?: return
        if (bandBrush == null || bandSize != size) {
            val w = size.width
            val h = size.height
            val gradientW = w * 2.8f
            val angle = Math.toRadians(20.0)
            val dx = (cos(angle) * gradientW).toFloat()
            val dy = (sin(angle) * gradientW).toFloat()
            val clear = t.sheenBand.copy(alpha = 0f)
            bandBrush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to clear,
                    0.44f to clear,
                    0.49f to t.sheenBand.copy(alpha = SHEEN_ALPHA),
                    0.50f to t.sheenHighlight.copy(alpha = SHEEN_ALPHA),
                    0.51f to t.sheenBand.copy(alpha = SHEEN_ALPHA),
                    0.56f to clear,
                    1f to clear,
                ),
                start = Offset(0f, h / 2f - dy / 2f),
                end = Offset(dx, h / 2f + dy / 2f),
                tileMode = TileMode.Repeated,
            )
            bandSize = size
            bandTravel = (gradientW / cos(angle)).toFloat()
        }
        val brush = bandBrush ?: return
        val offset = -0.9f * size.width + bandTravel * (phase - 0.5f)
        translate(left = offset) {
            drawRect(brush, topLeft = Offset(-offset, 0f), size = size, blendMode = BlendMode.SrcAtop)
        }
    }

    private fun Color.luminanceOf(): Float = AdaptiveInk.relativeLuminance(red, green, blue)

    private companion object {
        /** Blocks at least this tall shade across the aurora; shorter ones take one ink. */
        const val TALL_BLOCK_DP = 56f

        /** Mix differences below this are invisible (under one 8-bit step of the light→dark ramp). */
        const val SNAP = 1f / 512f

        /** Top and bottom inks this close draw as one flat colour, no shader. */
        const val FLAT = 1f / 256f

        /** The band's strength over the ink: a glint that reads on white and on near-black alike. */
        const val SHEEN_ALPHA = 0.85f
    }
}

/** [auroraInk] with the slot's [LocalAuroraInk], or nothing outside such a slot. */
@androidx.compose.runtime.Composable
fun Modifier.onAuroraInk(): Modifier {
    val spec = LocalAuroraInk.current ?: return this
    return auroraInk(spec)
}

/** The pill fill composited over a glass card's panel: what lies between a card's pill label and the aurora. */
fun pillInCardOverlay(tokens: GlassTokens): Color = tokens.pill.compositeOver(tokens.panel)

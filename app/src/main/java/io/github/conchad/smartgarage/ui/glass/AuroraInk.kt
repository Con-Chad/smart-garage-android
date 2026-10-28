package io.github.conchad.smartgarage.ui.glass

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.ui.node.requireGraphicsContext
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
 * greeting, Home's pills) takes its colour from the aurora behind it: white where white contrasts more, the dark ink
 * ([GlassTokens.inkDark]) where that does (D-A70), and since D-A74 the greys between them, continuously, across the
 * backdrops where neither reaches 4.5:1 ([AdaptiveInk.mix]); eased in time, and following the text as it scrolls.
 *
 * Cost by design:
 * - The aurora's colour is not read back from the GPU. [AuroraBackground] renders its own scene once per palette,
 *   size and theme into a [AuroraLumaMap.COLS] × [AuroraLumaMap.ROWS] software bitmap off the main thread, averaged
 *   over four drift phases (the ribbons drift slowly; the average is what the eye reads), and publishes it in
 *   [AuroraLumaHolder].
 * - Each ink node learns its on-screen top and bottom from `onGloballyPositioned` (a scroll re-places it), samples
 *   the map there (bilinear, a few multiplies) and eases toward the result with a first-order low-pass
 *   ([Motion.INK_SMOOTH_MS]) on a frame loop that runs only while it is settling. The observed inputs (map, theme,
 *   overlay) are re-read only when they change, not per placement, and the loop repaints only on a visible step
 *   (D-A48).
 * - The colour is applied in the draw phase: the glyphs draw into the node's offscreen layer and one rect of a
 *   vertical light→dark gradient is composited `SrcIn`, its shader reused and re-aimed with a local matrix, so a
 *   tall block shades across the aurora too. No recomposition, no relayout and no allocation per frame.
 * - D-A52: where the ink falls short of [AdaptiveInk.MIN_CONTRAST] against the backdrop (a bright band, or while it
 *   crosses through grey), a soft halo in the opposite tone is drawn under the glyphs, as strong as the shortfall:
 *   the glyphs are recorded into a layer once and drawn again blurred and tinted, `DstOver`. Where the ink already
 *   reads, nothing extra is drawn.
 */

/**
 * The one text-colour rule for the glass screens (D-A70), pure and unit-tested. Every piece of text picks its colour
 * by WCAG contrast against what is actually behind it, aiming for [MIN_CONTRAST]:
 * - Text straight on the aurora (titles, group headers, the greeting's sync line, pill labels) takes the light ink
 *   ([GlassTokens.onPhoto]) wherever it reaches [MIN_CONTRAST] against the aurora under it, the dark ink
 *   ([GlassTokens.inkDark]) wherever that does, and between them, where neither can, a grey that follows the backdrop
 *   continuously ([mix], D-A74) rather than a flip. Where the ink falls short, a halo makes up the rest
 *   ([haloStrength], [haloTone]).
 * - Text on glass panels (the cards) keeps the theme's colours, each moved just far enough toward white or the dark
 *   ink to clear [MIN_CONTRAST] on every backdrop the panel can be over ([legible], [legibleOnPanels]).
 */
object AdaptiveInk {
    /**
     * The backdrop luminance where the light ink ([GlassTokens.onPhoto], L 0.92) and the dark ink
     * ([GlassTokens.inkDark] #1B2130, L 0.015) contrast equally (both ≈ 3.9:1): `sqrt((Ll + 0.05)(Ld + 0.05)) − 0.05`.
     * Below it the light ink contrasts more, above it the dark one (D-A70; D-A52's grey band around it is gone).
     */
    const val CENTER = 0.2017f

    /**
     * How far past [CENTER] (as a factor on `L + 0.05`, ≈ 8 % on the contrast ratios) a backdrop must move before the
     * ink flips, so text sitting right on the crossover, or a ribbon drifting across it, doesn't flicker.
     */
    const val HYSTERESIS = 1.04f

    /** The contrast the ink aims for; where neither ink reaches it, the halo ([haloStrength]) makes up the rest. */
    const val MIN_CONTRAST = 4.5f

    /**
     * The halo starts below this contrast (D-A70: above [MIN_CONTRAST], because the ink is chosen on the aurora's
     * average over its drift, while a ribbon passing behind the text can make the moment's backdrop a little
     * lighter or darker), and is at full strength at or below [HALO_FULL_AT].
     */
    const val HALO_FROM = 5.5f
    const val HALO_FULL_AT = 3f

    /** [mix]'s shape: the share of the ramp spent slowly across the gap, and the width of the quick middle sweep. */
    private const val HOLD_SHARE = 0.4f
    private const val CROSS_WIDTH = 0.08f

    /** The halo tone's turn spans this factor either side of [HALO_TURN] on `L + 0.05`. */
    private const val HALO_TONE_SPAN = 1.25f

    /** No ink chosen yet (a node's first sample). */
    const val NONE = -1f

    /** WCAG relative luminance of an sRGB colour, components 0..1. */
    fun relativeLuminance(r: Float, g: Float, b: Float): Float = 0.2126f * lin(r) + 0.7152f * lin(g) + 0.0722f * lin(b)

    private fun lin(c: Float): Float = if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    /**
     * 0 = light ink, 1 = dark ink, for a backdrop of luminance [backdrop] (D-A70): whichever contrasts more, never
     * between. Within [HYSTERESIS] of [CENTER] the [previous] choice stands (or, with none yet, the nearer side).
     * Since D-A74 only for a composed colour on an estimated backdrop ([pillInk]); text on the aurora map uses [mix].
     */
    fun choose(backdrop: Float, previous: Float = NONE): Float {
        val ratio = (backdrop + 0.05f) / (CENTER + 0.05f)
        return when {
            ratio >= HYSTERESIS -> 1f
            ratio <= 1f / HYSTERESIS -> 0f
            previous == 0f || previous == 1f -> previous
            else -> if (ratio > 1f) 1f else 0f
        }
    }

    /**
     * The backdrop luminances where the light ink ([GlassTokens.onPhoto]) just reaches [MIN_CONTRAST] (below: it
     * clears it) and where the dark ink ([GlassTokens.inkDark]) does (above: it clears it). Between them neither ink
     * can (D-A70's crossover gap), and that is where [mix] shades from one to the other (D-A74).
     */
    const val LIGHT_UNTIL = 0.1658f
    const val DARK_FROM = 0.2440f

    private val LN_LIGHT_UNTIL = ln(LIGHT_UNTIL + 0.05f)
    private val LN_DARK_FROM = ln(DARK_FROM + 0.05f)

    /**
     * D-A74: the ink as a continuous function of the backdrop, 0 = light ink, 1 = dark. Fully light up to
     * [LIGHT_UNTIL] and fully dark from [DARK_FROM], so wherever either ink can reach [MIN_CONTRAST] it is that ink, as
     * with [choose]; across the gap between, where neither can, it shades through the greys instead of flipping, so
     * text moving over the aurora passes through the in-between shades. No hysteresis needed: there is no threshold
     * to flicker across.
     *
     * The shape, over the gap's position `t` on `ln(L + 0.05)` (the contrast scale): part of the ramp is one slow
     * smoothstep across the whole gap (40 %), the light ink greying and the dark ink lightening while they still read
     * (≥ ~2.8:1 on the map); the rest a quick one over the middle [CROSS_WIDTH], where any ink between must pass the
     * backdrop's own luminance. So it holds on the readable greys and only sweeps through the unreadable mid-grey,
     * where a continuous ink can't avoid ~1:1 for an instant (under 2:1 on ~7 % of the gap, against a third for a
     * single smoothstep).
     */
    fun mix(backdrop: Float): Float {
        val t = ((ln(backdrop + 0.05f) - LN_LIGHT_UNTIL) / (LN_DARK_FROM - LN_LIGHT_UNTIL)).coerceIn(0f, 1f)
        val c = ((t - (1f - CROSS_WIDTH) / 2f) / CROSS_WIDTH).coerceIn(0f, 1f)
        return HOLD_SHARE * t * t * (3f - 2f * t) + (1f - HOLD_SHARE) * c * c * (3f - 2f * c)
    }

    /**
     * The glyph luminance at which a black and a white halo contrast equally with it: `sqrt(1.05 · 0.05) − 0.05`.
     * The halo's tone ([haloTone]) turns around here.
     */
    private const val HALO_TURN = 0.1791f
    private val LN_HALO_LO = ln((HALO_TURN + 0.05f) / HALO_TONE_SPAN)
    private val LN_HALO_HI = ln((HALO_TURN + 0.05f) * HALO_TONE_SPAN)

    /**
     * D-A74: the halo's tone for ink of luminance [ink], 0 = [GlassTokens.inkHaloDark], 1 = [GlassTokens.inkHaloLight]:
     * whichever sets the glyph off more, so a mid-grey ink resting in the gap keeps a dark halo rather than a grey one
     * that would vanish into the backdrop with it. At the ends it is D-A52's (dark under the light ink, light under the
     * dark), and it turns continuously over a short span around [HALO_TURN], so it never pops.
     */
    fun haloTone(ink: Float): Float {
        val t = ((ln(ink + 0.05f) - LN_HALO_LO) / (LN_HALO_HI - LN_HALO_LO)).coerceIn(0f, 1f)
        return 1f - t * t * (3f - 2f * t)
    }

    /** WCAG 2 contrast between two relative luminances. */
    fun contrast(a: Float, b: Float): Float = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)

    /**
     * How strongly to draw the halo behind ink of luminance [ink] on a backdrop of luminance [backdrop] (D-A52, D-A70),
     * 0..1: none from [HALO_FROM] up, rising linearly to full at [HALO_FULL_AT]. Continuous in both, so it fades with
     * the ink's own low-pass and never pops.
     */
    fun haloStrength(ink: Float, backdrop: Float): Float =
        ((HALO_FROM - contrast(ink, backdrop)) / (HALO_FROM - HALO_FULL_AT)).coerceIn(0f, 1f)

    /** The ink at [mix]: a straight sRGB blend from [light] through grey to [dark], as the gradient shader draws it. */
    fun ink(mix: Float, light: Color, dark: Color): Color {
        val m = mix.coerceIn(0f, 1f)
        return Color(
            red = light.red + (dark.red - light.red) * m,
            green = light.green + (dark.green - light.green) * m,
            blue = light.blue + (dark.blue - light.blue) * m,
        )
    }

    /**
     * The lowest contrast [lum] has with any backdrop luminance in [lo]..[hi]: 1 when it lies inside the range, else
     * the nearer end's.
     */
    fun worstContrast(lum: Float, lo: Float, hi: Float): Float =
        if (lum in lo..hi) 1f else minOf(contrast(lum, lo), contrast(lum, hi))

    /**
     * [preferred] (a theme text colour) as is when it clears [MIN_CONTRAST] on every backdrop in [lo]..[hi], else
     * blended toward [lightInk] (a light colour) or [darkInk] (a dark one) just far enough that it does, or all the
     * way when even that falls short (D-A70). Keeps the theme's tone wherever it already reads, and never goes past
     * the inks, so secondary text never outshines primary.
     */
    fun legible(preferred: Color, lo: Float, hi: Float, lightInk: Color, darkInk: Color, target: Float = MIN_CONTRAST): Color {
        fun lum(c: Color) = relativeLuminance(c.red, c.green, c.blue)
        if (worstContrast(lum(preferred), lo, hi) >= target) return preferred
        val toward = if (lum(preferred) > CENTER) lightInk else darkInk
        if (worstContrast(lum(toward), lo, hi) < target) return toward
        var a = 0f
        var b = 1f
        repeat(16) {
            val m = (a + b) / 2f
            if (worstContrast(lum(ink(m, preferred, toward)), lo, hi) >= target) b = m else a = m
        }
        return ink(b, preferred, toward)
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

    /**
     * The darkest and the lightest the aurora gets anywhere under [overlay] (a glass panel's fill), as relative
     * luminances (D-A70): every backdrop a card's text can land on as it scrolls.
     */
    fun luminanceRange(overlay: Color): ClosedFloatingPointRange<Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        val a = overlay.alpha
        for (i in 0 until cols * rows) {
            val r = overlay.red * a + rgb[i * 3] * (1f - a)
            val g = overlay.green * a + rgb[i * 3 + 1] * (1f - a)
            val b = overlay.blue * a + rgb[i * 3 + 2] * (1f - a)
            val l = AdaptiveInk.relativeLuminance(r, g, b)
            if (l < lo) lo = l
            if (l > hi) hi = l
        }
        return lo..hi
    }

    /**
     * Where content at ([xFraction], [yFraction]) takes its ink from (D-A49): above the column's lightest band, from the
     * lightest row between it and that band, so text that turned dark there stays dark as it scrolls up toward the
     * darker top of the aurora and under the bar, instead of flipping back to white. At or below the band, and where
     * nothing lighter lies below, its own position. As text rises the held value only ever grows, so it never flickers.
     */
    fun holdY(xFraction: Float, yFraction: Float): Float {
        val c = (xFraction.coerceIn(0f, 1f) * cols).toInt().coerceIn(0, cols - 1)
        val r = (yFraction.coerceIn(0f, 1f) * rows).toInt().coerceIn(0, rows - 1)
        val hy = (holdRows[c * rows + r] + 0.5f) / rows
        // Only ever toward the band (down the screen), never up from it.
        if (hy <= yFraction) return yFraction
        return if (luminanceAt(xFraction, hy) > luminanceAt(xFraction, yFraction)) hy else yFraction
    }

    // Per column and row, the lightest cell between that row and the column's lightest one (itself below it).
    private val holdRows: IntArray by lazy {
        val out = IntArray(cols * rows)
        val lum = FloatArray(rows)
        for (c in 0 until cols) {
            var peak = 0
            for (r in 0 until rows) {
                lum[r] = AdaptiveInk.relativeLuminance(at(c, r, 0), at(c, r, 1), at(c, r, 2))
                if (lum[r] > lum[peak]) peak = r
                out[c * rows + r] = r
            }
            var best = peak
            for (r in peak downTo 0) {
                if (lum[r] > lum[best]) best = r
                out[c * rows + r] = best
            }
        }
        out
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

/**
 * A screen's content area for the ink (D-A49): [y] is the bottom of its top bar in root px, written by the bar on
 * layout; content scrolled above it samples the aurora at that line. Its presence also turns on
 * [AuroraLumaMap.holdY] for that content, so text keeps its dark ink up to and under the bar. A plain field, read on
 * the nodes' placement.
 */
class AuroraInkCeiling {
    var y = Float.NEGATIVE_INFINITY
}

/** Provided to a screen's content (not its bar's title, which inks by what is actually behind it). */
val LocalAuroraInkCeiling = staticCompositionLocalOf<AuroraInkCeiling?> { null }

/** The aurora behind this screen's content; null where there is none (previews, tests): ink then stays as composed. */
val LocalAuroraLuma = staticCompositionLocalOf<AuroraLumaHolder?> { null }

/**
 * How a piece of text on the aurora is inked: what lies between it and the aurora
 * ([overlay], read in the node, so a state it reads re-aims the ink without recomposing).
 */
@Stable
class AuroraInkSpec(val overlay: () -> Color = { Color.Transparent }) {
    companion object {
        /** Titles and labels straight on the aurora. */
        val Text = AuroraInkSpec()
    }
}

/**
 * Text that reads as one unit on the aurora (a group header's title and its "See all", Home's greeting and its sync
 * line) takes one ink between them (D-A70): each member reports the backdrop under it and all take the ink
 * [AdaptiveInk.mix] gives their mean (D-A74), so a row never shows a dark title beside a white action. Each member keeps
 * its own halo.
 */
@Stable
class AuroraInkGroup {
    private val samples = HashMap<Any, Float>()

    /** The group's ink, 0 light, 1 dark, greys between (D-A74); [AdaptiveInk.NONE] until a member reports. Snapshot state: members follow it. */
    var choice by mutableFloatStateOf(AdaptiveInk.NONE)
        private set

    internal fun report(member: Any, luminance: Float) {
        samples[member] = luminance
        rechoose()
    }

    internal fun remove(member: Any) {
        if (samples.remove(member) != null) rechoose()
    }

    private fun rechoose() {
        if (samples.isEmpty()) return
        // D-A74: the continuous ink of the members' mean backdrop. Written only on a step of [GROUP_STEP] (under one 8-bit level
        // of the ramp) or on landing at an end, so a scroll doesn't churn the state, and the members it re-aims, with
        // moves no one could see.
        var sum = 0f
        for (v in samples.values) sum += v
        val next = AdaptiveInk.mix(sum / samples.size)
        if (choice == AdaptiveInk.NONE || abs(next - choice) >= GROUP_STEP || (next != choice && (next == 0f || next == 1f))) choice = next
    }
}

/** A group's ink is rewritten only on a step of this (under one 8-bit level of the ramp, as [AuroraInkNode]'s snap). */
private const val GROUP_STEP = 1f / 256f

/** The [AuroraInkGroup] the adaptive ink in this slot belongs to; null: each text decides alone. */
val LocalAuroraInkGroup = staticCompositionLocalOf<AuroraInkGroup?> { null }

/**
 * Set where everything in a slot sits on the aurora (a top bar's title): [MetallicText] and [onAuroraInk] inside it
 * pick it up. Null elsewhere: text on cards keeps its card-based colour.
 */
val LocalAuroraInk = staticCompositionLocalOf<AuroraInkSpec?> { null }

/**
 * Inks this content by the aurora behind it (D-A46). Put it last in a chain (after paddings and surfaces), so only
 * the glyphs and icons drawn inside are recoloured, not a pill's fill or its ripple. With [sheen] the metallic
 * title's band (D-A24, D-A38) sweeps over the ink. Light and dark ink are [GlassTokens.onPhoto] and
 * [GlassTokens.inkDark] (D-A49). A no-op where there is no aurora map.
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
    // D-A70: where the text starts; the ink is sampled there as well as at the centre, since a line's glyphs start at
    // its left edge and a wide line can span a light and a dark part of the aurora.
    private var startX = Float.NaN
    private var top = 0f
    private var bottom = 0f

    // Targets and the eased values the draw reads (0 = light ink, 1 = dark), and whether any map applies.
    private var targetTop = 0f
    private var targetBottom = 0f
    private var mixTop = 0f
    private var mixBottom = 0f
    // The backdrop's luminance at the text (D-A52, for the halo), eased with the ink.
    private var lumTarget = 0f
    private var lum = 0f
    private var drawnLum = 0f
    private var active = false
    private var settled = false

    // Inputs read under observation (map, theme, overlay, density) and what derives from them, refreshed only when
    // one of them changes (D-A48): a scroll re-places the node every frame, and the placement path then only samples.
    private var holder: AuroraLumaHolder? = null
    private var group: AuroraInkGroup? = null
    private var ceiling: AuroraInkCeiling? = null
    private var map: AuroraLumaMap? = null
    private var overlay = Color.Transparent
    private var tallPx = 0f

    // The eased values last drawn: the frame loop repaints only once the ink has moved a visible step.
    private var drawnTop = 0f
    private var drawnBottom = 0f
    private var easing: Job? = null

    private var light = Color.White
    private var dark = Color.Black
    private var haloDark = Color.Black
    private var haloLight = Color.White

    // The halo's layers (D-A52): the glyphs recorded once, and a blurred, tinted copy of them; made on first need.
    private var glyphLayer: GraphicsLayer? = null
    private var haloLayer: GraphicsLayer? = null
    private var haloBlurPx = -1f
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
        refreshInputs()
        retarget()
        invalidateDraw()
    }

    override fun onAttach() {
        settled = false
        refreshInputs()
        retarget()
    }

    override fun onDetach() {
        easing = null
        group?.remove(this)
        group = null
        if (isAttached && (glyphLayer != null || haloLayer != null)) {
            val context = requireGraphicsContext()
            glyphLayer?.let(context::releaseGraphicsLayer)
            haloLayer?.let(context::releaseGraphicsLayer)
        }
        glyphLayer = null
        haloLayer = null
        haloBlurPx = -1f
    }

    override fun onObservedReadsChanged() {
        refreshInputs()
        retarget()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val p = coordinates.positionInRoot()
        val h = coordinates.size.height
        val cx = p.x + coordinates.size.width / 2f
        if (cx == centerX && p.y == top && p.y + h == bottom) return
        centerX = cx
        startX = p.x + coordinates.size.width * START_SAMPLE
        top = p.y
        bottom = p.y + h
        retarget()
    }

    /** Re-reads the map, the theme, the overlay and the density under observation, and the band they give. */
    private fun refreshInputs() {
        if (!isAttached) return
        observeReads {
            holder = currentValueOf(LocalAuroraLuma)
            val g = currentValueOf(LocalAuroraInkGroup)
            if (g !== group) group?.remove(this)
            group = g
            // Read so a change of the group's ink (another member moved) re-aims this one.
            g?.choice
            ceiling = currentValueOf(LocalAuroraInkCeiling)
            map = holder?.map
            tokens = currentValueOf(LocalGlassTokens)
            overlay = spec.overlay()
            tallPx = TALL_BLOCK_DP * currentValueOf(androidx.compose.ui.platform.LocalDensity).density
        }
        val t = tokens ?: return
        light = t.onPhoto
        dark = t.inkDark
        haloDark = t.inkHaloDark
        haloLight = t.inkHaloLight
    }

    /** Samples the targets at the current placement from the cached inputs and starts easing toward them. */
    private fun retarget() {
        if (!isAttached) return
        if (tokens == null) return
        val m = map
        val bounds = holder
        if (m == null || bounds == null || centerX.isNaN() || bounds.width <= 0f || bounds.height <= 0f) {
            if (active) {
                active = false
                invalidateDraw()
            }
            return
        }
        val x = (centerX - bounds.left) / bounds.width
        // D-A49: under the screen's top bar the content keeps the ink of the aurora at the bar's lower edge.
        // Above the aurora's lightest band it holds the ink of the lightest row between ([AuroraLumaMap.holdY]).
        val content = ceiling
        val floorY = content?.y ?: Float.NEGATIVE_INFINITY
        var yTop = (maxOf(top, floorY) - bounds.top) / bounds.height
        var yBottom = (maxOf(bottom, floorY) - bounds.top) / bounds.height
        if (content != null) {
            yTop = m.holdY(x, yTop)
            yBottom = m.holdY(x, yBottom)
        }
        val xs = (startX - bounds.left) / bounds.width
        val lumTopRaw = (m.luminanceAt(x, yTop, overlay) + m.luminanceAt(xs, yTop, overlay)) / 2f
        val lumBottomRaw = (m.luminanceAt(x, yBottom, overlay) + m.luminanceAt(xs, yBottom, overlay)) / 2f
        lumTarget = (lumTopRaw + lumBottomRaw) / 2f
        // D-A74 (amends D-A70): the ink is a continuous function of the backdrop ([AdaptiveInk.mix]): the light or the
        // dark ink wherever one reaches 4.5:1, shading through the greys across the gap where neither can; the
        // low-pass below only smooths it in time. A short block (a line or two) takes one ink from its mean backdrop, so it
        // never shades top-light / bottom-dark within a line; a tall one may take a different ink at each end.
        val g = group
        if (bottom - top < tallPx && g != null) {
            g.report(this, lumTarget)
            val c = g.choice
            targetTop = c
            targetBottom = c
        } else if (bottom - top < tallPx) {
            val c = AdaptiveInk.mix(lumTarget)
            targetTop = c
            targetBottom = c
        } else {
            targetTop = AdaptiveInk.mix(lumTopRaw)
            targetBottom = AdaptiveInk.mix(lumBottomRaw)
        }
        if (!active || !settled) {
            // The first sample lands at once: a title doesn't fade in from white on every screen it opens.
            mixTop = targetTop
            mixBottom = targetBottom
            lum = lumTarget
            active = true
            settled = true
            invalidateDraw()
            return
        }
        // Read by the frame loop below on its next tick; while it runs, a scroll only moves the targets.
        if (abs(targetTop - mixTop) < SNAP && abs(targetBottom - mixBottom) < SNAP && abs(lumTarget - lum) < LUM_SNAP) return
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
                lum += (lumTarget - lum) * k
                val done = abs(targetTop - mixTop) < SNAP && abs(targetBottom - mixBottom) < SNAP && abs(lumTarget - lum) < LUM_SNAP
                if (done) {
                    mixTop = targetTop
                    mixBottom = targetBottom
                    lum = lumTarget
                }
                // Repaint (and so re-render the node's offscreen layer) only once the ink has moved ~0.8 of an
                // 8-bit step of the ramp, or to land: smaller moves draw the same pixels.
                if (done || abs(mixTop - drawnTop) >= SNAP || abs(mixBottom - drawnBottom) >= SNAP || abs(lum - drawnLum) >= LUM_SNAP) invalidateDraw()
                if (done) break
            }
        }
    }

    override fun ContentDrawScope.draw() {
        if (!active || size.width <= 0f || size.height <= 0f) {
            drawContent()
            return
        }
        drawnLum = lum
        // D-A52: how far the ink (its middle, for a shaded block) falls short of the target contrast here.
        val mid = (mixTop + mixBottom) / 2f
        val inkColor = AdaptiveInk.ink(mid, light, dark)
        val inkLum = AdaptiveInk.relativeLuminance(inkColor.red, inkColor.green, inkColor.blue)
        val strength = AdaptiveInk.haloStrength(inkLum, lum)
        if (strength < HALO_MIN) {
            drawContent()
            drawInk()
            sheen?.let { drawSheen(it.value) }
            return
        }
        val context = requireGraphicsContext()
        val glyphs = glyphLayer ?: context.createGraphicsLayer().also { glyphLayer = it }
        glyphs.record { this@draw.drawContent() }
        drawLayer(glyphs)
        drawInk()
        sheen?.let { drawSheen(it.value) }
        // The halo under everything drawn so far: the same glyphs, blurred, in the tone opposite the ink.
        val halo = haloLayer ?: context.createGraphicsLayer().also { haloLayer = it }
        val blur = HALO_BLUR_DP * density
        if (blur != haloBlurPx) {
            halo.renderEffect = BlurEffect(blur, blur, TileMode.Decal)
            halo.blendMode = BlendMode.DstOver
            haloBlurPx = blur
        }
        val tone = lerp(haloDark, haloLight, AdaptiveInk.haloTone(inkLum))
        halo.colorFilter = ColorFilter.tint(tone.copy(alpha = HALO_MAX_ALPHA * strength), BlendMode.SrcIn)
        halo.record { drawLayer(glyphs) }
        drawLayer(halo)
    }

    private fun ContentDrawScope.drawInk() {
        drawnTop = mixTop
        drawnBottom = mixBottom
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

    private companion object {
        /** The second sample's place across the text, from its start (D-A70). */
        const val START_SAMPLE = 0.15f

        /** Blocks at least this tall shade across the aurora; shorter ones take one ink. */
        const val TALL_BLOCK_DP = 56f

        /**
         * Mix differences below this are invisible: the light→dark ramp spans ~213 8-bit levels, so this is ~0.8 of
         * one. D-A74: was 1/512; with a continuous ink, text in the gap re-aims on every scrolled frame, and this
         * halves the repaints of its offscreen layer (and halo) for no visible difference.
         */
        const val SNAP = 1f / 256f

        /** Top and bottom inks this close draw as one flat colour, no shader. */
        const val FLAT = 1f / 256f

        /** Backdrop luminance steps below this don't change the halo visibly. */
        const val LUM_SNAP = 1f / 256f

        /** Below this halo strength nothing extra is drawn (D-A52). */
        const val HALO_MIN = 0.02f

        /** The halo's opacity at full strength and its blur radius (D-A52): a soft glow, not an outline. */
        const val HALO_MAX_ALPHA = 0.7f
        const val HALO_BLUR_DP = 2.5f

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

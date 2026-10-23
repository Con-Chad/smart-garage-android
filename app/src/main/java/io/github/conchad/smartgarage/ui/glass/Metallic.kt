package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the sheen band is on its 4.76 s loop, 0..1, shared by every metallic title on a screen so they glint
 * together and only one animation runs. Defaults to the parked frame (sheen over center) when a screen hasn't
 * provided one, which is also the reduced-motion frame.
 */
val LocalSheenPhase = compositionLocalOf<State<Float>> { ParkedSheen }

private val ParkedSheen = object : State<Float> {
    override val value: Float get() = 0.5f
}

private val wipeEasing = CubicBezierEasing(0.5f, 0f, 0.18f, 1f)

/**
 * Starts the sheen sweep for [content] (D-A24): the "Gradient Wipe" loop, a hold before the light arrives, then the
 * eased sweep. D-A38 slowed it: the sweep 2.95 → 3.25 s (+10 %) and the hold 0.65 → 1.51 s, so the time the band
 * is off the text between glints grows 3.18 → 4.29 s (+35 %); the loop is 4.76 s. Static when motion is reduced or the screen isn't resumed. The phase is derived from
 * the shared ambient clock at up to 60 Hz (the reference CSS ran at that), so a 120 Hz panel repaints every title
 * half as often, and every title on the screen advances on the same tick.
 */
@Composable
fun MetallicSheenHost(content: @Composable () -> Unit) {
    val animate = rememberAmbientMotion()
    val phase: State<Float> = if (animate) {
        val clock = rememberAmbientClock(SHEEN_FRAME_MS)
        remember(clock) {
            object : State<Float> {
                override val value: Float get() = sheenPhaseAt(clock.value)
            }
        }
    } else {
        ParkedSheen
    }
    CompositionLocalProvider(LocalSheenPhase provides phase, content = content)
}

private const val SHEEN_FRAME_MS = 16L
private const val SHEEN_HOLD_MS = 1_510
private const val SHEEN_SWEEP_MS = 3_247
private const val SHEEN_PERIOD_MS = SHEEN_HOLD_MS + SHEEN_SWEEP_MS
private const val SHEEN_HOLD_END = 0.075f

/**
 * The keyframes `0 at 0 (linear) → 0.075 at the hold's end (wipe easing) → 1 at the period's end`, restarting each period: a slow linear
 * creep during the hold, then the eased sweep.
 */
private fun sheenPhaseAt(elapsedMs: Long): Float {
    val t = (elapsedMs % SHEEN_PERIOD_MS).toInt()
    return if (t < SHEEN_HOLD_MS) {
        SHEEN_HOLD_END * (t.toFloat() / SHEEN_HOLD_MS)
    } else {
        SHEEN_HOLD_END + (1f - SHEEN_HOLD_END) * wipeEasing.transform((t - SHEEN_HOLD_MS).toFloat() / SHEEN_SWEEP_MS)
    }
}

/**
 * Metallic text (D-A24). Two tiers:
 * - [animated] (titles, group headers, card names): a muted silver sheen band (D-A38; gold before) sweeps across the glyphs. Drawn in the
 *   draw phase only (offscreen layer + `SrcIn` gradient), so the frames cost no recomposition or relayout.
 * - static (body and labels): a quiet steel gradient as the text brush, no animation. The base metal is a light
 *   steel on dark and a dark steel on light, so body copy keeps ≥ 4.5:1 contrast on the glass.
 */
@Composable
fun MetallicText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    animated: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val tokens = glass
    if (animated) {
        val phase = LocalSheenPhase.current
        Text(
            text,
            style = style.copy(color = tokens.metalMid),
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign,
            modifier = modifier.metallicSheen(tokens, phase),
        )
    } else {
        Text(
            text,
            style = style.copy(brush = staticMetallicBrush(tokens)),
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign,
            modifier = modifier,
        )
    }
}

/** The static tier as a brush, for `TextStyle(brush = …)` on text that isn't a [MetallicText]. */
fun staticMetallicBrush(tokens: GlassTokens): Brush = Brush.linearGradient(
    0f to tokens.metalDim,
    0.5f to tokens.metalMid,
    1f to tokens.metalDim,
)

/**
 * Tints the content with the sweeping metallic gradient: the content is drawn to an offscreen layer and the
 * gradient is composited `SrcIn`, so only the glyphs' pixels take the color. Position follows [phase] like the
 * snippet's `background-position: 130% → -30%` on a 280 %-wide gradient. The gradient is built once per size, at
 * offset 0; each frame slides the canvas under it rather than creating a new shader, so an animation frame costs
 * no allocation beyond the draw itself.
 */
fun Modifier.metallicSheen(tokens: GlassTokens, phase: State<Float>): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val w = size.width
        val h = size.height
        val gradientW = w * 2.8f
        // 110°: mostly horizontal, slightly downward.
        val angle = Math.toRadians(20.0)
        val dx = (cos(angle) * gradientW).toFloat()
        val dy = (sin(angle) * gradientW).toFloat()
        val brush = Brush.linearGradient(
            colorStops = arrayOf(
                0f to tokens.metalDim,
                0.22f to tokens.metalMid,
                0.44f to tokens.metalDim,
                0.49f to tokens.sheenBand,
                0.50f to tokens.sheenHighlight,
                0.51f to tokens.sheenBand,
                0.56f to tokens.metalDim,
                0.78f to tokens.metalMid,
                1f to tokens.metalDim,
            ),
            start = Offset(0f, h / 2f - dy / 2f),
            end = Offset(dx, h / 2f + dy / 2f),
        )
        onDrawWithContent {
            drawContent()
            if (w <= 0f || h <= 0f) return@onDrawWithContent
            // CSS background-position p: offset = p * (container - image) = -1.8 w p; p runs 1.3 → -0.3.
            val p = 1.3f - 1.6f * phase.value
            val offset = -1.8f * w * p
            translate(left = offset) {
                drawRect(brush, topLeft = Offset(-offset, 0f), size = size, blendMode = BlendMode.SrcIn)
            }
        }
    }

/** Convenience for places that already have a color slot: the metal mid tone, legible on glass. */
val GlassTokens.metalText: Color get() = metalMid

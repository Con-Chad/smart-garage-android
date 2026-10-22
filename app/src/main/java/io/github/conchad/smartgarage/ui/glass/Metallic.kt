package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the sheen band is on its 3.6 s sweep, 0..1, shared by every metallic title on a screen so they glint
 * together and only one animation runs. Defaults to the parked frame (sheen over center) when a screen hasn't
 * provided one, which is also the reduced-motion frame.
 */
val LocalSheenPhase = compositionLocalOf<State<Float>> { ParkedSheen }

private val ParkedSheen = object : State<Float> {
    override val value: Float get() = 0.5f
}

private val wipeEasing = CubicBezierEasing(0.5f, 0f, 0.18f, 1f)

/**
 * Starts the sheen sweep for [content] (D-A24): the "Gradient Wipe" loop, 3.6 s with an 18 % anticipation hold
 * before the light arrives. Static when motion is reduced or the screen isn't resumed.
 */
@Composable
fun MetallicSheenHost(content: @Composable () -> Unit) {
    val animate = rememberAmbientMotion()
    val phase: State<Float> = if (animate) {
        rememberInfiniteTransition(label = "sheen").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 3_600
                    0f at 0 using LinearEasing
                    0.075f at 648 using wipeEasing
                    1f at 3_600
                },
                RepeatMode.Restart,
            ),
            label = "sheenPhase",
        )
    } else {
        ParkedSheen
    }
    CompositionLocalProvider(LocalSheenPhase provides phase, content = content)
}

/**
 * Metallic text (D-A24). Two tiers:
 * - [animated] (titles, group headers, card names): the gold sheen band sweeps across the glyphs. Drawn in the
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
 * snippet's `background-position: 130% → -30%` on a 280 %-wide gradient.
 */
fun Modifier.metallicSheen(tokens: GlassTokens, phase: State<Float>): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@drawWithContent
        val gradientW = w * 2.8f
        // CSS background-position p: offset = p * (container - image) = -1.8 w p; p runs 1.3 → -0.3.
        val p = 1.3f - 1.6f * phase.value
        val offset = -1.8f * w * p
        // 110°: mostly horizontal, slightly downward.
        val angle = Math.toRadians(20.0)
        val dx = (cos(angle) * gradientW).toFloat()
        val dy = (sin(angle) * gradientW).toFloat()
        val start = Offset(offset, h / 2f - dy / 2f)
        val end = Offset(offset + dx, h / 2f + dy / 2f)
        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to tokens.metalDim,
                    0.22f to tokens.metalMid,
                    0.44f to tokens.metalDim,
                    0.49f to tokens.sheen,
                    0.50f to tokens.sheenHighlight,
                    0.51f to tokens.sheen,
                    0.56f to tokens.metalDim,
                    0.78f to tokens.metalMid,
                    1f to tokens.metalDim,
                ),
                start = start,
                end = end,
            ),
            blendMode = BlendMode.SrcIn,
        )
    }

/** Convenience for places that already have a color slot: the metal mid tone, legible on glass. */
val GlassTokens.metalText: Color get() = metalMid

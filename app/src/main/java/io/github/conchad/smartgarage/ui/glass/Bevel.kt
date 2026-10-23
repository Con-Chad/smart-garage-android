package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Raised glass (D-A52): a surface that sits *on* the aurora or on a card rather than being a window in it. A
 * bevelled rim (light along the top edge, shade along the bottom) and a soft gloss over the top half give the edge a
 * lit, three-dimensional lip; pair it with [glassShadow] for the lift. Used by the owner pill and the frosted
 * quick-action buttons. Colours come from [GlassTokens.rimLight] / [GlassTokens.rimShade] only.
 */

/** The bevelled border: [width] wide, [GlassTokens.rimLight] at the top fading to [GlassTokens.rimShade] at the bottom. */
fun Modifier.bevelRim(tokens: GlassTokens, shape: Shape, width: Dp = 1.5.dp): Modifier = border(
    BorderStroke(
        width,
        Brush.verticalGradient(
            0f to tokens.rimLight,
            0.45f to tokens.rimLight.copy(alpha = tokens.rimLight.alpha * 0.3f),
            0.6f to tokens.rimShade.copy(alpha = tokens.rimShade.alpha * 0.4f),
            1f to tokens.rimShade,
        ),
    ),
    shape,
)

/**
 * [fill] with a gloss over its top half and a faint shade along the bottom, behind the content. Clip to the shape
 * before it.
 */
fun Modifier.bevelFill(tokens: GlassTokens, fill: Color): Modifier = drawWithCache {
    val gloss = Brush.verticalGradient(
        0f to tokens.panelHighlight,
        0.5f to Color.Transparent,
        0.75f to Color.Transparent,
        1f to tokens.rimShade.copy(alpha = tokens.rimShade.alpha * 0.5f),
    )
    onDrawBehind {
        drawRect(fill)
        drawRect(gloss)
    }
}

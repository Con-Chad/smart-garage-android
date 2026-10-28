package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
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

/**
 * The glass of the Service / Fuel header controls (D-A87, over D-A85's flat slate): [fill] (the dark-enough tint that
 * keeps the labels ≥ 4.5:1 over the pale aurora), a [GlassTokens.headerChipGloss] sheen over the top half that is gone
 * by mid-height where the label sits, a faint shade along the bottom, and a bevelled 1 dp rim — [rimTop] at the top
 * edge (the specular lip) easing to [rimBottom] at the bottom. Clips to [shape]; brushes cached per size.
 */
fun Modifier.headerGlass(tokens: GlassTokens, shape: Shape, fill: Color, rimTop: Color, rimBottom: Color): Modifier = this
    .clip(shape)
    .drawWithCache {
        val gloss = Brush.verticalGradient(
            0f to tokens.headerChipGloss,
            0.25f to tokens.headerChipGloss.copy(alpha = tokens.headerChipGloss.alpha * 0.35f),
            0.5f to Color.Transparent,
            0.8f to Color.Transparent,
            1f to tokens.rimShade.copy(alpha = tokens.rimShade.alpha * 0.35f),
        )
        onDrawBehind {
            drawRect(fill)
            drawRect(gloss)
        }
    }
    .border(BorderStroke(1.dp, Brush.verticalGradient(0f to rimTop, 0.5f to lerp(rimTop, rimBottom, 0.7f), 1f to rimBottom)), shape)

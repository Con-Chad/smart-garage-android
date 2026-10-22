package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The "Frosted Glass" panel (D-A24): a translucent fill over the aurora, a 1 dp border, a 1 dp inset highlight
 * along the top edge and a lift shadow. A real backdrop blur (`backdrop-filter: blur(16px)`) would need a
 * `RenderEffect` on a layer that contains what is *behind* this panel, which Compose cannot express for a drawn
 * background without a third-party dependency; translucency + highlight + border approximates the frosted look
 * over the already-soft aurora, and costs one fill. Sets the content color to the glass text color.
 *
 * [onClick] gives the whole panel the snippet's press lift (scale 1.02) in addition to the ripple.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(glass.radiusLarge),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = glass
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) 1.02f else 1f, spring(stiffness = 600f), label = "lift")
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation = if (tokens.dark) 10.dp else 6.dp, shape = shape, clip = false, ambientColor = tokens.shadow, spotColor = tokens.shadow)
            .clip(shape)
            .drawBehind {
                drawRect(tokens.panel)
                // Inset top highlight: a 1 dp band fading out over the first 24 dp.
                drawRect(
                    Brush.verticalGradient(
                        0f to tokens.panelHighlight,
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = 24.dp.toPx(),
                    ),
                    size = size.copy(height = 24.dp.toPx()),
                )
                drawLine(tokens.panelHighlight, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), strokeWidth = 1.dp.toPx())
            }
            .border(1.dp, tokens.panelBorder, shape)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.glassClickable(interaction, onClick, onLongClick)
                } else {
                    Modifier
                },
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides tokens.textPrimary) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/** [GlassPanel] with the list cards' outer margin, the drop-in for `GroupCard` on glass screens. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) = GlassPanel(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    onClick = onClick,
    onLongClick = onLongClick,
    contentPadding = contentPadding,
    content = content,
)

@Composable
private fun Modifier.glassClickable(interaction: MutableInteractionSource, onClick: (() -> Unit)?, onLongClick: (() -> Unit)?): Modifier {
    val indication: Indication = LocalIndication.current
    return if (onLongClick != null) {
        combinedClickable(
            interactionSource = interaction,
            indication = indication,
            onLongClick = onLongClick,
            onClick = onClick ?: {},
        )
    } else {
        clickable(interactionSource = interaction, indication = indication, onClick = onClick ?: {})
    }
}

/** The small uppercase pill on a glass panel ("Frosted"). */
@Composable
fun GlassPillBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val tokens = glass
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier
            .clip(shape)
            .drawBehind { drawRect(tokens.pill) }
            .border(1.dp, tokens.pillBorder, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { content() }
}

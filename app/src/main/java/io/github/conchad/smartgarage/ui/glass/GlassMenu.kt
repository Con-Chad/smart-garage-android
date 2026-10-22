package io.github.conchad.smartgarage.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.conchad.smartgarage.ui.components.RowDivider

/**
 * A menu on glass (D-A31), replacing the stock `DropdownMenu` over the vehicle photo: a [Popup] centred on the
 * composable it is called from, holding a [GlassPanel] with a metallic header (the car's name), a divider and
 * [GlassMenuItem] rows. Menus stay readable on any photo: the panel sits on the page colour at 94 % before the
 * frosted fill, so text keeps ≥ 4.5:1 (D-A30 kept menus opaque for the same reason). Springs in from 90 % under
 * motion, appears at once under reduced motion. Back and an outside tap dismiss.
 */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val tokens = glass
    val reduced = rememberReducedMotion()
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }
    val shape = RoundedCornerShape(tokens.radiusMedium)
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    val a = appear.value
                    alpha = a
                    scaleX = 0.9f + 0.1f * a
                    scaleY = 0.9f + 0.1f * a
                }
                .padding(16.dp)
                .widthIn(min = 240.dp, max = 320.dp)
                .shadow(16.dp, shape, clip = false, ambientColor = tokens.shadow, spotColor = tokens.shadow)
                .clip(shape)
                .drawBehind { drawRect(tokens.pageBackground.copy(alpha = 0.94f)) },
        ) {
            GlassPanel(shape = shape, elevated = false, glow = LocalAuroraTint.current.color ?: MaterialTheme.colorScheme.primary) {
                MetallicText(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    animated = true,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                )
                RowDivider()
                content()
            }
        }
    }
}

/** One row of a [GlassMenu]: a sheen icon and the label, 48 dp tall, the whole row the target. */
@Composable
fun GlassMenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    val tokens = glass
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tokens.textPrimary, modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * `AlertDialog` on glass (D-A31): the page colour as the container with the panel border, a large radius and an
 * animated metallic title; buttons and fields use [glassTextButtonColors] / [glassTextFieldColors]. The dialog's
 * own window scrim stays.
 */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
) {
    val tokens = glass
    val shape = RoundedCornerShape(tokens.radiusLarge)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { MetallicText(title, style = MaterialTheme.typography.headlineSmall, animated = true) },
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        shape = shape,
        containerColor = tokens.pageBackground,
        titleContentColor = tokens.textPrimary,
        textContentColor = tokens.textPrimary,
        modifier = Modifier.border(1.dp, tokens.panelBorder, shape),
    )
}

/** Text buttons on glass take the sheen. */
@Composable
fun glassTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = glass.sheen)

/** Outlined fields on glass: sheen focus ring and cursor, panel border at rest, glass text colours. */
@Composable
fun glassTextFieldColors(): TextFieldColors {
    val tokens = glass
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = tokens.sheen,
        unfocusedBorderColor = tokens.panelBorder,
        focusedLabelColor = tokens.sheen,
        unfocusedLabelColor = tokens.textSecondary,
        cursorColor = tokens.sheen,
        focusedTextColor = tokens.textPrimary,
        unfocusedTextColor = tokens.textPrimary,
    )
}

/**
 * A pill button on glass (D-A31): the panel's pill fill and border, the label in the sheen, an optional trailing
 * icon. Full size is 48 dp tall; [compact] draws a 36 dp pill inside a 48 dp touch target for a button that sits
 * at the end of a 48 dp row ("Add" / "Update"). The ripple is clipped to the pill either way.
 */
@Composable
fun GlassPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    val tokens = glass
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (compact) Modifier.minimumInteractiveComponentSize() else Modifier)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = if (compact) 36.dp else 48.dp)
                .clip(CircleShape)
                .drawBehind { drawRect(tokens.pill) }
                .border(1.dp, tokens.pillBorder, CircleShape)
                .indication(interaction, ripple())
                .padding(horizontal = if (compact) 14.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = tokens.sheen, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tokens.sheen, modifier = Modifier.padding(start = 4.dp).size(18.dp))
            }
        }
    }
}

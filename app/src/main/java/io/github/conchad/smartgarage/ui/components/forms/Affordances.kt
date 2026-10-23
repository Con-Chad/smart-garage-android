package io.github.conchad.smartgarage.ui.components.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.bevelFill
import io.github.conchad.smartgarage.ui.glass.bevelRim
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassShadow
import io.github.conchad.smartgarage.ui.glass.pressGlow
import io.github.conchad.smartgarage.ui.glass.pressScale
import io.github.conchad.smartgarage.ui.glass.rememberPressFeedback

/**
 * "Waiting to sync" on a row whose change the server hasn't taken yet (D-A16). Icon and words together, never a
 * color on its own; the whole mark is one TalkBack item appended to the row's text.
 */
@Composable
fun PendingMark(modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.CloudUpload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(14.dp).padding(end = 2.dp),
        )
        Text(
            stringResource(R.string.pending_mark),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/**
 * The "Add" button of a list tab (D-A20): one extended FAB at the bottom end, with a verb for a label ("Log
 * fill-up"), above the bottom bar. Detail screens use inline buttons ([ActionButton]) instead of a FAB, and
 * edit/delete live in the row's detail sheet.
 */
@Composable
fun AddFab(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Add) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
    )
}

/**
 * Inline action on a detail screen ("Log fill-up", "Update odometer"): at least 48 dp tall, icon plus verb. The
 * label may take two lines, so it survives font scale 1.3 in half the screen's width instead of being cut.
 *
 * D-A52: frosted glass raised on the card it sits in (the vehicle page's quick actions and papers): the denser
 * [io.github.conchad.smartgarage.ui.glass.GlassTokens.frost] fill with a top gloss, a bevelled rim and a small lift
 * shadow, the label in the glass text colour (≥ 4.5:1 on the frost whatever the aurora) and the icon in the sheen.
 * Pressed it shrinks and brightens like the pills; disabled it drops to half strength.
 */
@Composable
fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val tokens = glass
    val shape = ButtonDefaults.outlinedShape
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    // Icon above a centred label: a half-width chip at font scale 1.3 holds "Update odometer" on two whole lines
    // instead of an icon pinned left beside a truncated label. Nothing is ellipsized; the button grows instead.
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = tokens.textPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = tokens.textSecondary,
        ),
        border = null,
        interactionSource = interaction,
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(press)
            .glassShadow(shape, elevation = 4.dp, color = tokens.shadow)
            .clip(shape)
            .bevelFill(tokens, tokens.frost)
            .pressGlow(press)
            .bevelRim(tokens, shape)
            .alpha(if (enabled) 1f else 0.5f),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null, tint = if (enabled) tokens.sheen else tokens.textSecondary, modifier = Modifier.size(20.dp))
            Text(label, maxLines = 2, overflow = TextOverflow.Clip, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Where a viewer would see an action: "View only · shared by Alex". Shown instead of the add/edit/delete
 * affordances on a vehicle the user can't edit (D-030), so the absence of a button has an explanation.
 */
@Composable
fun ViewOnlyNote(owner: String?, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Visibility, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            if (owner != null) stringResource(R.string.view_only_shared_by, owner) else stringResource(R.string.view_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

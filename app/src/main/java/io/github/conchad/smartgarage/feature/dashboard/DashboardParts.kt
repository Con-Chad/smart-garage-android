package io.github.conchad.smartgarage.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.OdometerReminder
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.GlassEmphasisPill
import io.github.conchad.smartgarage.ui.glass.GlassPillButton
import io.github.conchad.smartgarage.ui.glass.rememberPillInk
import io.github.conchad.smartgarage.ui.glass.rememberPillInkSpec
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.theme.LocalStatusColors
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors

/** "Odometer last updated 6 weeks ago" with Update and Later; shown at the top of Needs attention (D-A24). */
@Composable
internal fun OdometerReminderRow(reminder: OdometerReminder.Reminder, onUpdate: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    val days = reminder.daysSince.toInt()
    val ago = if (days >= 14) pluralStringResource(R.plurals.odometer_reminder_weeks_ago, days / 7, days / 7) else pluralStringResource(R.plurals.due_days_ago, days, days)
    GlassCard(modifier) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Speed, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                MetallicText(stringResource(R.string.odometer_reminder_title, ago), style = MaterialTheme.typography.titleSmall, animated = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.odometer_reminder_body), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onLater, colors = ButtonDefaults.textButtonColors(contentColor = tokens.textSecondary)) { Text(stringResource(R.string.action_later)) }
            TextButton(onClick = onUpdate, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_update)) }
        }
    }
}

/**
 * The small glass pill at the end of Home's "Your vehicles" header (D-A43): "Swipe to view more ›" or "Add vehicle +".
 * The label size is a step down from the card pills so it fits beside the title at font scale 1.3; 48 dp to touch.
 */
@Composable
internal fun VehiclesHeaderPill(label: String, icon: ImageVector, onClick: () -> Unit, emphasized: Boolean = false) {
    if (emphasized) {
        // D-A52 ("Add vehicle"): the call-to-action pill (white label on a strong tint of the theme colour, light rim)
        // instead of the adaptive ink on the faint glass pill, so the one call to action on an empty garage stands out.
        GlassEmphasisPill(label, onClick, icon = icon, compact = true, modifier = Modifier.padding(start = 8.dp, end = 12.dp))
        return
    }
    GlassPillButton(
        label = label,
        onClick = onClick,
        icon = icon,
        compact = true,
        textStyle = MaterialTheme.typography.labelMedium,
        contentColor = rememberPillInk(),
        // On the header's glass bar (D-A54): the adaptive ink (D-A46) against the aurora under the bar and the pill.
        adaptiveInk = rememberPillInkSpec(inCard = true),
        modifier = Modifier.padding(start = 8.dp, end = 12.dp),
    )
}

@Composable
internal fun AllCaughtUp() {
    val colors = LocalStatusColors.current
    GlassCard(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.ok)
            Column(Modifier.padding(start = 16.dp)) {
                MetallicText(stringResource(R.string.dashboard_all_caught_up), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.dashboard_all_caught_up_body), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
            }
        }
    }
}

@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Int = 32) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.size(size.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                style = if (size >= 48) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            )
        }
    }
}

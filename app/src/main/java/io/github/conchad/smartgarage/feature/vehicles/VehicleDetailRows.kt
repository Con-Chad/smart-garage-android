package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.forms.PendingMark
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.glass

/**
 * Up to three figures side by side, value above label. Values stay on one line and shrink to fit rather than wrap;
 * labels may take two lines and hyphenate, so "Durchschnittsverbrauch" breaks at a syllable rather than anywhere.
 * Each figure is one TalkBack item ("123,456 km, Odometer").
 */
@Composable
fun StatStrip(stats: List<Pair<String, String>>, modifier: Modifier = Modifier, title: String? = null) {
    val tokens = glass
    // A frosted card like the rest of the page (D-A32), not an opaque Material box; the figures sit straight on it.
    GlassPanel(modifier = modifier.fillMaxWidth(), contentPadding = 16.dp) {
        title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = tokens.sheen, modifier = Modifier.padding(bottom = 8.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = MaterialTheme.typography.titleMedium.copy(color = tokens.textPrimary)
            val labelStyle = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto)
            stats.forEach { (label, value) ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(value, style = style, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize))
                    Text(label, style = labelStyle, color = tokens.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** An inspection or policy row: one tap target that opens its sheet, where Edit and Delete live (D-A20). */
@Composable
internal fun PaperRow(icon: ImageVector, title: String, subtitle: String, pending: Boolean, onClick: () -> Unit) {
    val tokens = glass
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(22.dp))
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            if (pending) PendingMark(Modifier.padding(top = 2.dp))
        }
    }
}

/** A past service: name, then date, vehicle and odometer; cost on the right. */
@Composable
fun RecordRow(record: MaintenanceRecord, name: String?, vehicle: Vehicle?, fmt: Formatter, pending: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                name ?: stringResource(R.string.record_untitled),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    record.performedAt.toInstantOrNull()?.let(fmt::shortDate),
                    vehicle?.displayName,
                    record.odometerKm?.let(fmt::distance),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (pending) PendingMark(Modifier.padding(top = 2.dp))
        }
        Text(fmt.money(record.costCents, record.currency), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
internal fun EmptyGroup(text: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textSecondary,
            modifier = Modifier.padding(16.dp),
        )
    }
}

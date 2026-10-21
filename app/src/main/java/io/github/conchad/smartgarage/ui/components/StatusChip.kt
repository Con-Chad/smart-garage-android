package io.github.conchad.smartgarage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.DueStatus
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.ui.theme.LocalStatusColors

/** Small pill with a colored dot, e.g. "2 overdue". Color always comes with text, never color alone. */
@Composable
fun StatusChip(status: DueStatus, text: String, modifier: Modifier = Modifier) {
    val colors = LocalStatusColors.current
    val (container, content, dot) = when (status) {
        DueStatus.OVERDUE -> Triple(colors.overdueContainer, colors.onOverdueContainer, colors.overdue)
        DueStatus.DUE_SOON -> Triple(colors.dueSoonContainer, colors.onDueSoonContainer, colors.dueSoon)
        DueStatus.OK -> Triple(colors.okContainer, colors.onOkContainer, colors.ok)
    }
    Pill(text = text, container = container, content = content, dot = dot, modifier = modifier)
}

@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier, dot: Color? = null) {
    Surface(color = container, contentColor = content, shape = CircleShape, modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dot != null) Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** "1 overdue" and "2 due soon" side by side, or "All good". */
@Composable
fun VehicleStatusChip(summary: VehicleSummary, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (summary.overdue > 0) {
            StatusChip(DueStatus.OVERDUE, pluralStringResource(R.plurals.status_overdue_count, summary.overdue, summary.overdue))
        }
        if (summary.dueSoon > 0) {
            StatusChip(DueStatus.DUE_SOON, pluralStringResource(R.plurals.status_due_soon_count, summary.dueSoon, summary.dueSoon))
        }
        if (summary.overdue == 0 && summary.dueSoon == 0) StatusChip(DueStatus.OK, stringResource(R.string.status_all_good))
    }
}

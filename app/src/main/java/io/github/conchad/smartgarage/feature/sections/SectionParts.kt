package io.github.conchad.smartgarage.feature.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.SyncBanner
import io.github.conchad.smartgarage.ui.components.VehicleFilterRow
import io.github.conchad.smartgarage.ui.sync.SyncInfo
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Frame shared by the sections under More: back arrow, stale-data banner, vehicle filter, then (inside
 * pull-to-refresh) loading, can't-reach, an empty state or the list.
 */
@Composable
fun SectionScaffold(
    title: String,
    onBack: () -> Unit,
    vehicles: List<Vehicle>,
    selectedVehicleId: String?,
    onSelectVehicle: (String?) -> Unit,
    loaded: Boolean,
    sync: SyncInfo,
    onRefresh: () -> Unit,
    isEmpty: Boolean,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptyBody: String,
    content: LazyListScope.() -> Unit,
) {
    AppScaffold(title = title, onBack = onBack) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            SyncBanner(sync, onRetry = onRefresh)
            VehicleFilterRow(vehicles, selectedVehicleId, onSelectVehicle, Modifier.padding(bottom = 4.dp))
            RefreshBox(sync, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                LoadedContent(loaded = loaded, sync = sync, onRetry = onRefresh) {
                    if (isEmpty) {
                        EmptyState(icon = emptyIcon, title = emptyTitle, body = emptyBody)
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                            modifier = Modifier.fillMaxSize(),
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A list row: icon badge, title, up to two lines of detail, and an optional trailing value with its own detail line.
 * The title column takes whatever width the trailing value leaves, so large fonts wrap the title, never the amount.
 * A row without [onClick] is still read as one item by TalkBack.
 */
@Composable
fun SectionRow(
    icon: ImageVector,
    title: String,
    detail: String?,
    onClick: (() -> Unit)?,
    trailing: String? = null,
    trailingDetail: String? = null,
    trailingColor: Color = Color.Unspecified,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null || trailingDetail != null) {
            Column(horizontalAlignment = Alignment.End) {
                trailing?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, color = trailingColor, textAlign = TextAlign.End)
                }
                trailingDetail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/** "October 2026" in the app language. */
@Composable
fun monthLabel(month: YearMonth): String {
    val format = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()) }
    return month.format(format).replaceFirstChar { it.uppercase() }
}

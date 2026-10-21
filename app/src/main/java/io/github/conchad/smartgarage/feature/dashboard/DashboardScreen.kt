package io.github.conchad.smartgarage.feature.dashboard

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.Account
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Dashboard
import io.github.conchad.smartgarage.core.model.DueItem
import io.github.conchad.smartgarage.core.model.DueStatus
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.StatusChip
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.dueText
import io.github.conchad.smartgarage.ui.components.relativeDays
import io.github.conchad.smartgarage.ui.theme.LocalStatusColors
import java.time.LocalTime

@Composable
fun DashboardScreen(
    profile: ServerProfile,
    account: Account,
    onOpenSettings: () -> Unit,
    onOpenVehicle: (String) -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenFuel: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val greeting = stringResource(greetingFor(remember { LocalTime.now() }), account.greetingName)
    val settingsLabel = stringResource(R.string.nav_settings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(greeting, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            syncLine(profile.name, state.sync),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings, modifier = Modifier.semantics { contentDescription = settingsLabel }) {
                        Avatar(account.greetingName)
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.sync.status.running && !state.firstLoad,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            val dashboard = state.dashboard
            when {
                state.firstLoad -> FirstSync()
                dashboard == null -> Unit
                dashboard.vehicles.isEmpty() && state.sync.lastSyncedAt == null -> CantReach(onRetry = viewModel::refresh)
                dashboard.vehicles.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Garage,
                    title = stringResource(R.string.dashboard_no_vehicles_title),
                    body = stringResource(R.string.dashboard_no_vehicles_body),
                )
                else -> DashboardContent(
                    dashboard = dashboard,
                    fmt = state.formatter,
                    onOpenVehicle = onOpenVehicle,
                    onOpenMaintenance = onOpenMaintenance,
                    onOpenFuel = onOpenFuel,
                )
            }
        }
    }
}

@Composable
private fun DashboardContent(
    dashboard: Dashboard,
    fmt: Formatter,
    onOpenVehicle: (String) -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenFuel: () -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item { Header(stringResource(R.string.dashboard_vehicles)) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(dashboard.vehicles, key = { it.vehicle.id }) { summary ->
                    VehicleCard(summary, fmt, onClick = { onOpenVehicle(summary.vehicle.id) })
                }
            }
        }

        item { Header(stringResource(R.string.dashboard_attention), onMore = onOpenMaintenance) }
        if (dashboard.attention.isEmpty()) {
            item { AllCaughtUp() }
        } else {
            item {
                GroupCard {
                    dashboard.attention.take(5).forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        DueRow(item, fmt, onClick = onOpenMaintenance)
                    }
                }
            }
        }

        if (dashboard.nextInspection != null || dashboard.insurance != null) {
            item { Header(stringResource(R.string.dashboard_coming_up)) }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    dashboard.nextInspection?.let { (inspection, vehicle) ->
                        val due = inspection.nextDueAt.toInstantOrNull()
                        InfoTile(
                            icon = Icons.Outlined.VerifiedUser,
                            label = stringResource(R.string.dashboard_inspection, inspection.type),
                            value = due?.let { fmt.date(it) } ?: "—",
                            detail = listOfNotNull(due?.let { relativeDays(fmt.daysFromToday(it).toInt()) }, vehicle.displayName)
                                .joinToString(" · "),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    dashboard.insurance?.let { (policy, vehicle) ->
                        InfoTile(
                            icon = Icons.Outlined.Shield,
                            label = stringResource(R.string.dashboard_insurance),
                            value = policy.provider,
                            detail = listOfNotNull(
                                policy.endDate.toInstantOrNull()?.let { stringResource(R.string.dashboard_insurance_until, fmt.date(it)) },
                                vehicle.displayName,
                            ).joinToString(" · "),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (dashboard.recentFuel.isNotEmpty()) {
            item { Header(stringResource(R.string.dashboard_recent_fuel), onMore = onOpenFuel) }
            item {
                GroupCard {
                    dashboard.recentFuel.forEachIndexed { index, (entry, vehicle) ->
                        if (index > 0) RowDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onOpenFuel).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBadge(Icons.Outlined.LocalGasStation)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(
                                    listOfNotNull(entry.filledAt.toInstantOrNull()?.let(fmt::date), entry.stationName).joinToString(" · "),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        vehicle.displayName,
                                        entry.liters?.let(fmt::volume),
                                        entry.liters?.let { fmt.unitPrice(entry.totalCostCents, it, entry.currency) },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(fmt.money(entry.totalCostCents, entry.currency), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(summary: VehicleSummary, fmt: Formatter, onClick: () -> Unit) {
    val vehicle = summary.vehicle
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.width(272.dp),
    ) {
        VehiclePhoto(vehicle, Modifier.fillMaxWidth().aspectRatio(16f / 10f), iconSize = 56.dp)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(vehicle.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(vehicle.licensePlate, fmt.distance(vehicle.currentOdometerKm)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            when {
                summary.overdue > 0 -> StatusChip(
                    DueStatus.OVERDUE,
                    pluralStringResource(R.plurals.status_overdue_count, summary.overdue, summary.overdue),
                )
                summary.dueSoon > 0 -> StatusChip(
                    DueStatus.DUE_SOON,
                    pluralStringResource(R.plurals.status_due_soon_count, summary.dueSoon, summary.dueSoon),
                )
                else -> StatusChip(DueStatus.OK, stringResource(R.string.status_all_good))
            }
        }
    }
}

@Composable
private fun DueRow(item: DueItem, fmt: Formatter, onClick: () -> Unit) {
    val colors = LocalStatusColors.current
    val overdue = item.status == DueStatus.OVERDUE
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = if (overdue) colors.overdue else colors.dueSoon, modifier = Modifier.size(10.dp)) {}
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.vehicle.displayName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            dueText(item, fmt),
            style = MaterialTheme.typography.labelMedium,
            color = if (overdue) colors.overdue else colors.dueSoon,
            maxLines = 2,
            modifier = Modifier.padding(start = 12.dp).width(132.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun AllCaughtUp() {
    val colors = LocalStatusColors.current
    Surface(
        color = colors.okContainer,
        contentColor = colors.onOkContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null)
            Column(Modifier.padding(start = 16.dp)) {
                Text(stringResource(R.string.dashboard_all_caught_up), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.dashboard_all_caught_up_body), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoTile(icon: ImageVector, label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconBadge(icon, size = 36)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Header(text: String, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (onMore != null) {
            TextButton(onClick = onMore) { Text(stringResource(R.string.action_see_all)) }
        }
    }
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) { Column { content() } }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun FirstSync() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.dashboard_first_sync), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CantReach(onRetry: () -> Unit) {
    EmptyState(
        icon = Icons.Outlined.CloudOff,
        title = stringResource(R.string.dashboard_cant_reach_title),
        body = stringResource(R.string.dashboard_cant_reach_body),
        action = { Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) } },
    )
}

/** "Garage · updated 5 min ago", "Garage · syncing…" or "Garage · offline, updated 2 h ago". */
@Composable
private fun syncLine(profileName: String, sync: SyncInfo): String {
    val last = sync.lastSyncedAt?.let {
        DateUtils.getRelativeTimeSpanString(it.toEpochMilli(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val status = when {
        sync.status.running -> stringResource(R.string.sync_running)
        sync.status.lastProblem is ServerProblem.Unreachable && last != null -> stringResource(R.string.sync_offline, last)
        sync.status.lastProblem != null && last != null -> stringResource(R.string.sync_failed, last)
        last != null -> stringResource(R.string.sync_updated, last)
        else -> null
    }
    return listOfNotNull(profileName, status).joinToString(" · ")
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

private fun greetingFor(time: LocalTime): Int = when (time.hour) {
    in 5..11 -> R.string.dashboard_greeting_morning
    in 12..17 -> R.string.dashboard_greeting_afternoon
    else -> R.string.dashboard_greeting_evening
}

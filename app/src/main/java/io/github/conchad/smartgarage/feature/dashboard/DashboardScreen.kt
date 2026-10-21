package io.github.conchad.smartgarage.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.Account
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Dashboard
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.ui.components.DueRow
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.FuelRow
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.relativeTime
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
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
    var open by rememberSheetTarget()
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item { GroupHeader(stringResource(R.string.dashboard_vehicles)) }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(dashboard.vehicles, key = { it.vehicle.id }) { summary ->
                    VehicleCard(summary, fmt, onClick = { onOpenVehicle(summary.vehicle.id) })
                }
            }
        }

        item { GroupHeader(stringResource(R.string.dashboard_attention), onAction = onOpenMaintenance) }
        if (dashboard.attention.isEmpty()) {
            item { AllCaughtUp() }
        } else {
            item {
                GroupCard {
                    dashboard.attention.take(MAX_ATTENTION).forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        DueRow(item, fmt, onClick = { open = SheetTarget.Schedule(item.schedule.id) })
                    }
                }
            }
        }

        if (dashboard.nextInspection != null || dashboard.insurance != null) {
            item { GroupHeader(stringResource(R.string.dashboard_coming_up)) }
            item {
                Row(
                    Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    dashboard.nextInspection?.let { (inspection, vehicle) ->
                        val due = inspection.nextDueAt.toInstantOrNull()
                        InfoTile(
                            icon = Icons.Outlined.EventAvailable,
                            label = inspectionLabel(inspection.type),
                            value = due?.let { fmt.date(it) } ?: "—",
                            detail = listOfNotNull(due?.let { dueDays(fmt.daysFromToday(it).toInt()) }, vehicle.displayName),
                            onClick = { onOpenVehicle(vehicle.id) },
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
                            ),
                            onClick = { onOpenVehicle(vehicle.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (dashboard.recentFuel.isNotEmpty()) {
            item { GroupHeader(stringResource(R.string.dashboard_recent_fuel), onAction = onOpenFuel) }
            item {
                GroupCard {
                    dashboard.recentFuel.forEachIndexed { index, (entry, vehicle) ->
                        if (index > 0) RowDivider()
                        FuelRow(entry, vehicle, fmt, onClick = { open = SheetTarget.Fuel(entry.id) })
                    }
                }
            }
        }
    }

    when (val target = open) {
        is SheetTarget.Schedule -> dashboard.attention.firstOrNull { it.schedule.id == target.id }?.let { ScheduleSheet(it, fmt) { open = null } }
        is SheetTarget.Fuel -> dashboard.recentFuel.firstOrNull { it.item.id == target.id }?.let { (entry, vehicle) ->
            FuelSheet(entry, vehicle, dashboard.fuelSegments[entry.id], fmt) { open = null }
        }
        else -> Unit
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
        VehiclePhoto(vehicle, Modifier.fillMaxWidth().aspectRatio(16f / 9f), iconSize = 56.dp)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(vehicle.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(vehicle.licensePlate, fmt.distance(vehicle.currentOdometerKm)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            VehicleStatusChip(summary)
        }
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
private fun InfoTile(
    icon: ImageVector,
    label: String,
    value: String,
    detail: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxHeight(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            IconBadge(icon, size = 36)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
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
    val last = sync.lastSyncedAt?.let { relativeTime(it) }
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

private const val MAX_ATTENTION = 5

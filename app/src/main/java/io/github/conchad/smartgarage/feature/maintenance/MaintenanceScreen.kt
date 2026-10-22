package io.github.conchad.smartgarage.feature.maintenance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.DueItem
import io.github.conchad.smartgarage.core.model.DueStatus
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.feature.fuel.FAB_CLEARANCE
import io.github.conchad.smartgarage.feature.vehicles.RecordRow
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.DueRow
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RecordSheet
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.intervalText
import io.github.conchad.smartgarage.ui.links.dueCalendarEvent
import io.github.conchad.smartgarage.ui.links.rememberAddToCalendar
import io.github.conchad.smartgarage.ui.components.SearchField
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.VehicleFilterRow
import io.github.conchad.smartgarage.ui.components.WriteQueueBanner
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
import java.time.ZoneId

@Composable
fun MaintenanceScreen(
    onLogService: (scheduleId: String?, vehicleId: String?) -> Unit,
    onEditRecord: (recordId: String) -> Unit,
    onAddSchedule: (vehicleId: String?) -> Unit,
    onEditSchedule: (scheduleId: String) -> Unit,
    onEditScheduleDefaults: (scheduleId: String) -> Unit,
    onAttachReceipt: (vehicleId: String, recordId: String) -> Unit,
    viewModel: MaintenanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var open by rememberSheetTarget()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    DeniedSnackbar(viewModel, snackbar)
    val addToCalendar = rememberAddToCalendar(snackbar)

    AppScaffold(
        title = stringResource(R.string.tab_service),
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            // A schedule is a setting rather than a log entry: it hangs off the bar, the FAB keeps its verb (D-A20).
            if (state.loaded && state.canAddSchedule) {
                IconButton(onClick = { onAddSchedule(state.selectedVehicleId) }) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.action_add_schedule))
                }
            }
        },
        floatingActionButton = {
            if (state.loaded && state.canAdd) {
                AddFab(stringResource(R.string.action_log_service), onClick = { onLogService(null, state.selectedVehicleId) }, icon = Icons.Outlined.Build)
            }
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            WriteQueueBanner(sync, viewModel)
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.maintenance_upcoming)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.maintenance_history)) })
            }
            VehicleFilterRow(
                state.vehicles,
                state.selectedVehicleId,
                viewModel::selectVehicle,
                Modifier.padding(top = 12.dp),
            )
            // The web's history search (name, vendor, notes, parts); filtered in the ViewModel (D-A23).
            if (tab == 1 && state.loaded && (state.unfilteredRecords > 0 || state.query.isNotEmpty())) {
                SearchField(state.query, viewModel::search, stringResource(R.string.search_history_hint), Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
            val searching = state.query.isNotBlank() && state.unfilteredRecords > 0
            RefreshBox(sync, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                LoadedContent(loaded = state.loaded, sync = sync, onRetry = viewModel::refresh) {
                    when {
                        tab == 0 && state.due.isEmpty() -> EmptyState(
                            icon = Icons.Outlined.Build,
                            title = stringResource(R.string.maintenance_no_schedules_title),
                            body = stringResource(R.string.maintenance_no_schedules_body),
                            action = if (state.canAddSchedule) {
                                { Button(onClick = { onAddSchedule(state.selectedVehicleId) }) { Text(stringResource(R.string.action_add_schedule)) } }
                            } else {
                                null
                            },
                        )
                        tab == 0 -> Upcoming(state.due, state, onOpen = { open = SheetTarget.Schedule(it.schedule.id) })
                        state.records.isEmpty() -> EmptyState(
                            icon = if (searching) Icons.Outlined.Search else Icons.Outlined.History,
                            title = stringResource(if (searching) R.string.search_no_matches_title else R.string.maintenance_no_records_title),
                            body = stringResource(if (searching) R.string.search_no_matches_body else R.string.maintenance_no_records_body),
                        )
                        else -> History(state, pendingIds, onOpen = { open = SheetTarget.Record(it) })
                    }
                }
            }
        }
    }

    when (val target = open) {
        is SheetTarget.Schedule -> state.due.firstOrNull { it.schedule.id == target.id }?.let { item ->
            // A reminder in the user's calendar for the due day (D-A25), when the schedule has one.
            val event = item.dueAt?.let { due ->
                dueCalendarEvent(item.name, item.vehicle, fmt.localDate(due), intervalText(item.schedule.intervalKm, item.schedule.intervalMonths, fmt), item.schedule.notes)
            }
            ScheduleSheet(
                item,
                fmt,
                onDismiss = { open = null },
                onLogService = {
                    open = null
                    onLogService(item.schedule.id, item.vehicle.id)
                },
                pending = item.schedule.id in pendingIds,
                onEdit = {
                    open = null
                    onEditSchedule(item.schedule.id)
                },
                onEditDefaults = {
                    open = null
                    onEditScheduleDefaults(item.schedule.id)
                },
                onDelete = {
                    open = null
                    viewModel.deleteSchedule(item.schedule.id)
                },
                onAddToCalendar = event?.let { e -> { addToCalendar(e) } },
            )
        }
        is SheetTarget.Record -> state.records.firstOrNull { it.id == target.id }?.let { record ->
            RecordSheet(
                record,
                state.recordNames[record.id] ?: stringResource(R.string.record_untitled),
                state.vehicleById[record.vehicleId],
                fmt,
                onDismiss = { open = null },
                pending = record.id in pendingIds,
                onEdit = {
                    open = null
                    onEditRecord(record.id)
                },
                onDelete = {
                    open = null
                    viewModel.deleteRecord(record.id)
                },
                onAttachReceipt = {
                    open = null
                    onAttachReceipt(record.vehicleId, record.id)
                },
            )
        }
        else -> Unit
    }
}

@Composable
private fun Upcoming(due: List<DueItem>, state: MaintenanceUiState, onOpen: (DueItem) -> Unit) {
    val groups = due.groupBy { it.status }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    LazyColumn(contentPadding = PaddingValues(bottom = FAB_CLEARANCE), modifier = Modifier.fillMaxSize()) {
        listOf(
            DueStatus.OVERDUE to R.string.status_overdue,
            DueStatus.DUE_SOON to R.string.status_due_soon,
            DueStatus.OK to R.string.maintenance_later,
        ).forEach { (status, label) ->
            val items = groups[status].orEmpty()
            if (items.isNotEmpty()) {
                item(key = status.name) { GroupHeader("${stringResource(label)} · ${items.size}") }
                item(key = "${status.name}-list") {
                    GroupCard {
                        items.forEachIndexed { index, item ->
                            if (index > 0) RowDivider()
                            DueRow(item, state.formatter, onClick = { onOpen(item) }, showVehicle = showVehicle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun History(state: MaintenanceUiState, pendingIds: Set<String>, onOpen: (String) -> Unit) {
    val fmt = state.formatter
    val zone: ZoneId = fmt.zone
    val byYear = state.records.groupBy { it.performedAt.toInstantOrNull()?.atZone(zone)?.year }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    LazyColumn(contentPadding = PaddingValues(bottom = FAB_CLEARANCE), modifier = Modifier.fillMaxSize()) {
        byYear.forEach { (year, records) ->
            item(key = "y$year") {
                val total = records.groupBy { it.currency }.map { (currency, list) -> fmt.money(list.sumOf { it.costCents }, currency) }
                GroupHeader(listOfNotNull(year?.toString() ?: "—", total.joinToString(" + ")).joinToString(" · "))
            }
            item(key = "y$year-list") {
                GroupCard {
                    records.forEachIndexed { index, record ->
                        if (index > 0) RowDivider()
                        RecordRow(
                            record,
                            state.recordNames[record.id],
                            vehicle = state.vehicleById[record.vehicleId].takeIf { showVehicle },
                            fmt = fmt,
                            pending = record.id in pendingIds,
                            onClick = { onOpen(record.id) },
                        )
                    }
                }
            }
        }
    }
}

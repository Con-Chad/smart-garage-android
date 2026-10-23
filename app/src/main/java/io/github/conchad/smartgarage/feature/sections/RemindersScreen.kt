package io.github.conchad.smartgarage.feature.sections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.Reminder
import io.github.conchad.smartgarage.core.model.Urgency
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.intervalText
import io.github.conchad.smartgarage.ui.links.dueCalendarEvent
import io.github.conchad.smartgarage.ui.links.rememberAddToCalendar
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.dueText
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.shortName
import io.github.conchad.smartgarage.ui.components.EmptyAction

@Composable
internal fun RemindersScreen(onBack: () -> Unit, actions: SectionActions, viewModel: RemindersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var openSchedule by rememberSaveable { mutableStateOf<String?>(null) }
    val addSchedule = { actions.onAddSchedule(state.selectedVehicleId) }
    val snackbar = remember { SnackbarHostState() }
    val addToCalendar = rememberAddToCalendar(snackbar)
    SectionScaffold(
        title = stringResource(R.string.nav_reminders),
        onBack = onBack,
        viewModel = viewModel,
        snackbar = snackbar,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty(),
        emptyIcon = Icons.Outlined.NotificationsActive,
        emptyTitle = stringResource(R.string.reminders_empty_title),
        actions = {
            // A schedule is a setting, not a log entry, so it hangs off the bar rather than a FAB (D-A20).
            if (state.loaded && state.canEditAny) {
                IconButton(onClick = addSchedule) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.action_add_schedule))
                }
            }
        },
        emptyAction = if (state.loaded && state.canEditAny) {
            { EmptyAction(stringResource(R.string.action_add_schedule), Icons.Outlined.Add, onClick = addSchedule) }
        } else {
            null
        },
    ) {
        state.data.groupBy { it.urgency }.forEach { (urgency, reminders) ->
            item(key = urgency.name, contentType = "header") {
                GroupHeader(
                    when (urgency) {
                        Urgency.OVERDUE -> stringResource(R.string.status_overdue)
                        Urgency.SOON -> stringResource(R.string.reminders_soon)
                        Urgency.LATER -> stringResource(R.string.maintenance_later)
                    },
                )
            }
            item(key = "${urgency.name}-list", contentType = "group") {
                GroupCard {
                    reminders.forEachIndexed { index, reminder ->
                        if (index > 0) RowDivider()
                        when (reminder) {
                            is Reminder.Service -> SectionRow(
                                icon = Icons.Outlined.Build,
                                title = reminder.item.name,
                                detail = listOfNotNull(dueText(reminder.item, fmt), reminder.vehicle.shortName).joinToString(" · "),
                                onClick = { openSchedule = reminder.item.schedule.id },
                                pending = reminder.item.schedule.id in pendingIds,
                            )
                            is Reminder.InspectionDue -> SectionRow(
                                icon = Icons.Outlined.EventAvailable,
                                title = inspectionLabel(reminder.inspection.type),
                                detail = listOfNotNull(reminder.date?.let { dueDays(fmt.daysFromToday(it).toInt()) }, reminder.vehicle.shortName)
                                    .joinToString(" · "),
                                onClick = { actions.onOpenVehicle(reminder.vehicle.id) },
                                pending = reminder.inspection.id in pendingIds,
                            )
                            is Reminder.InsuranceEnds -> SectionRow(
                                icon = Icons.Outlined.Shield,
                                title = stringResource(
                                    if (reminder.policy.autoRenew) R.string.reminders_insurance_renews else R.string.reminders_insurance_ends,
                                    reminder.policy.provider,
                                ),
                                detail = listOfNotNull(reminder.date?.let { dueDays(fmt.daysFromToday(it).toInt()) }, reminder.vehicle.shortName)
                                    .joinToString(" · "),
                                onClick = { actions.onOpenVehicle(reminder.vehicle.id) },
                                pending = reminder.policy.id in pendingIds,
                            )
                        }
                    }
                }
            }
        }
    }
    openSchedule?.let { id ->
        state.data.filterIsInstance<Reminder.Service>().firstOrNull { it.item.schedule.id == id }?.let { reminder ->
            val item = reminder.item
            val event = item.dueAt?.let { due ->
                dueCalendarEvent(item.name, item.vehicle, fmt.localDate(due), intervalText(item.schedule.intervalKm, item.schedule.intervalMonths, fmt), item.schedule.notes)
            }
            ScheduleSheet(
                item,
                fmt,
                onDismiss = { openSchedule = null },
                onLogService = {
                    openSchedule = null
                    actions.onLogService(item.schedule.id)
                },
                pending = item.schedule.id in pendingIds,
                onEdit = {
                    openSchedule = null
                    actions.onEditSchedule(item.schedule.id)
                },
                onEditDefaults = {
                    openSchedule = null
                    actions.onEditScheduleDefaults(item.schedule.id)
                },
                onDelete = {
                    openSchedule = null
                    viewModel.deleteSchedule(item.schedule.id)
                },
                onAddToCalendar = event?.let { e -> { addToCalendar(e) } },
            )
        }
    }
}

package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.InspectionSheet
import io.github.conchad.smartgarage.ui.components.InsuranceSheet
import io.github.conchad.smartgarage.ui.components.RecordSheet
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.coverageLabel
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.intervalText
import io.github.conchad.smartgarage.ui.links.dueCalendarEvent
import io.github.conchad.smartgarage.ui.links.rememberAddToCalendar
import io.github.conchad.smartgarage.ui.links.renewalCalendarEvent

/**
 * The vehicle page's detail sheets (split out of VehicleDetailScreen, D-A42): the one [target] names, if it still
 * exists, with its edit / delete / calendar actions. [onClose] clears the target before any action runs.
 */
@Composable
internal fun VehicleDetailSheets(
    target: SheetTarget?,
    onClose: () -> Unit,
    detail: VehicleDetail,
    state: VehicleDetailUiState,
    pendingIds: Set<String>,
    actions: VehicleActions,
    snackbar: SnackbarHostState,
    viewModel: VehicleDetailViewModel,
) {
    val fmt = state.formatter
    val vehicle = detail.vehicle
    val canEdit = vehicle.access.canEdit
    val addToCalendar = rememberAddToCalendar(snackbar)
    when (target) {
        is SheetTarget.Schedule -> detail.due.firstOrNull { it.schedule.id == target.id }?.let { item ->
            val event = item.dueAt?.let { due ->
                dueCalendarEvent(item.name, vehicle, fmt.localDate(due), intervalText(item.schedule.intervalKm, item.schedule.intervalMonths, fmt), item.schedule.notes)
            }
            ScheduleSheet(
                item,
                fmt,
                onDismiss = { onClose() },
                onLogService = {
                    onClose()
                    actions.onLogService(item.schedule.id)
                },
                pending = item.schedule.id in pendingIds,
                onEdit = {
                    onClose()
                    actions.onEditSchedule(item.schedule.id)
                },
                onEditDefaults = {
                    onClose()
                    actions.onEditScheduleDefaults(item.schedule.id)
                },
                onDelete = {
                    onClose()
                    viewModel.deleteSchedule(item.schedule.id)
                },
                onAddToCalendar = event?.let { e -> { addToCalendar(e) } },
            )
        }
        is SheetTarget.Record -> detail.records.firstOrNull { it.id == target.id }?.let { record ->
            RecordSheet(
                record,
                state.recordName(record) ?: stringResource(R.string.record_untitled),
                vehicle,
                fmt,
                onDismiss = { onClose() },
                pending = record.id in pendingIds,
                onEdit = {
                    onClose()
                    actions.onEditRecord(record.id)
                },
                onDelete = {
                    onClose()
                    viewModel.deleteRecord(record.id)
                },
                onAttachReceipt = {
                    onClose()
                    actions.onAttachReceipt(vehicle.id, record.id)
                }.takeIf { canEdit },
            )
        }
        is SheetTarget.Fuel -> detail.fuel.firstOrNull { it.id == target.id }?.let { entry ->
            FuelSheet(
                entry,
                vehicle,
                detail.segments[entry.id],
                fmt,
                onDismiss = { onClose() },
                pending = entry.id in pendingIds,
                onDelete = {
                    onClose()
                    viewModel.deleteFuelEntry(entry.id)
                },
                showEconomy = state.trackFuelEconomy,
            )
        }
        is SheetTarget.Inspection -> detail.inspections.firstOrNull { it.id == target.id }?.let { inspection ->
            val event = inspection.nextDueAt.toInstantOrNull()?.let { due ->
                dueCalendarEvent(
                    inspectionLabel(inspection.type), vehicle, fmt.localDate(due),
                    inspection.stickerNumber?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.inspection_sticker) + ": " + it },
                    inspection.notes,
                )
            }
            InspectionSheet(
                inspection,
                vehicle,
                fmt,
                onDismiss = { onClose() },
                pending = inspection.id in pendingIds,
                onEdit = {
                    onClose()
                    actions.onEditInspection(vehicle.id, inspection.id)
                },
                onDelete = {
                    onClose()
                    viewModel.deleteInspection(inspection.id)
                },
                onAddToCalendar = event?.let { e -> { addToCalendar(e) } },
            )
        }
        is SheetTarget.Insurance -> detail.policies.firstOrNull { it.id == target.id }?.let { policy ->
            val event = policy.endDate.toInstantOrNull()?.let { end ->
                renewalCalendarEvent(
                    policy.provider, vehicle, fmt.localDate(end), coverageLabel(policy.coverageType),
                    policy.policyNumber?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.insurance_policy_number) + ": " + it },
                )
            }
            InsuranceSheet(
                policy,
                vehicle,
                fmt,
                onDismiss = { onClose() },
                pending = policy.id in pendingIds,
                onEdit = {
                    onClose()
                    actions.onEditInsurance(vehicle.id, policy.id)
                },
                onDelete = {
                    onClose()
                    viewModel.deleteInsurance(policy.id)
                },
                onAddToCalendar = event?.let { e -> { addToCalendar(e) } },
            )
        }
        null -> Unit
    }
}

package io.github.conchad.smartgarage.feature.vehicles

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.Specs
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.DetailRow
import io.github.conchad.smartgarage.ui.components.DueRow
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.FuelRow
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.InspectionSheet
import io.github.conchad.smartgarage.ui.components.InsuranceSheet
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RecordSheet
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.WriteQueueBanner
import io.github.conchad.smartgarage.ui.components.forms.ActionButton
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.components.forms.PendingMark
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.components.bodyTypeLabel
import io.github.conchad.smartgarage.ui.components.driveTypeLabel
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.enumLabel
import io.github.conchad.smartgarage.ui.components.fuelTypeLabel
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.files.UploadKind
import io.github.conchad.smartgarage.ui.files.fileProblemText

/** What the detail screen can start: the quick logs, and every editor that belongs to one vehicle (CP11 B). */
class VehicleActions(
    val onLogFillUp: (vehicleId: String) -> Unit,
    val onLogService: (scheduleId: String) -> Unit,
    val onEditRecord: (recordId: String) -> Unit,
    val onAddSchedule: (vehicleId: String) -> Unit,
    val onEditSchedule: (scheduleId: String) -> Unit,
    val onEditScheduleDefaults: (scheduleId: String) -> Unit,
    val onAddExpense: (vehicleId: String) -> Unit,
    val onAddDocument: (vehicleId: String) -> Unit,
    val onEditVehicle: (vehicleId: String) -> Unit,
    val onAddInspection: (vehicleId: String) -> Unit,
    val onEditInspection: (vehicleId: String, inspectionId: String) -> Unit,
    val onAddInsurance: (vehicleId: String) -> Unit,
    val onEditInsurance: (vehicleId: String, policyId: String) -> Unit,
)

@Composable
fun VehicleDetailScreen(
    onBack: () -> Unit,
    actions: VehicleActions,
    viewModel: VehicleDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val photoPick by viewModel.photoPick.collectAsStateWithLifecycle()
    val detail = state.detail
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    DeniedSnackbar(viewModel, snackbar)
    LaunchedEffect(closed) { if (closed) onBack() }

    // A photo the server would refuse is said so here, before anything is queued (D-A21).
    val photoMessage = when (val pick = photoPick) {
        null -> null
        PhotoPick.Unreadable -> stringResource(R.string.file_unreadable)
        is PhotoPick.Rejected -> fileProblemText(pick.problem, UploadKind.VehiclePhoto)
    }
    LaunchedEffect(photoMessage) {
        if (photoMessage != null) {
            snackbar.showSnackbar(photoMessage)
            viewModel.clearPhotoPick()
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::setPhoto)
    }

    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableIntStateOf(0) }

    // The heading below the photo names the vehicle; the bar takes the name over once that scrolls away.
    val headingGone by remember { derivedStateOf { listState.firstVisibleItemIndex >= 2 } }
    AppScaffold(
        title = if (headingGone) detail?.vehicle?.displayName.orEmpty() else "",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbar) },
        actions = {
            val vehicle = detail?.vehicle
            if (vehicle != null && vehicle.access.canEdit) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit_vehicle)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            actions.onEditVehicle(vehicle.id)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_change_photo)) },
                        leadingIcon = { Icon(Icons.Outlined.AddAPhoto, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    if (!vehicle.access.isShared) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete_vehicle), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = 1
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
            WriteQueueBanner(sync, viewModel)
            RefreshBox(sync, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                LoadedContent(loaded = state.loaded, sync = sync, onRetry = viewModel::refresh) {
                    if (detail != null) {
                        VehicleDetailContent(
                            detail, state, listState, PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
                            pendingIds = pendingIds,
                            actions = actions,
                            snackbar = snackbar,
                            viewModel = viewModel,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Outlined.DirectionsCar,
                            title = stringResource(R.string.vehicle_gone_title),
                            body = stringResource(R.string.vehicle_gone_body),
                        )
                    }
                }
            }
        }
    }

    // Deleting a vehicle takes everything with it, so it asks twice; the second time with the consequences spelled out.
    val name = detail?.vehicle?.displayName.orEmpty()
    when (confirmDelete) {
        1 -> ConfirmDialog(
            title = stringResource(R.string.vehicle_delete_title, name),
            body = stringResource(R.string.vehicle_delete_body),
            confirmLabel = stringResource(R.string.action_continue),
            onConfirm = { confirmDelete = 2 },
            onDismiss = { confirmDelete = 0 },
            destructive = true,
        )
        2 -> ConfirmDialog(
            title = stringResource(R.string.vehicle_delete_again_title),
            body = stringResource(R.string.vehicle_delete_again_body, name),
            confirmLabel = stringResource(R.string.action_delete_vehicle),
            onConfirm = {
                confirmDelete = 0
                viewModel.deleteVehicle()
            },
            onDismiss = { confirmDelete = 0 },
            destructive = true,
        )
    }
}

@Composable
private fun VehicleDetailContent(
    detail: VehicleDetail,
    state: VehicleDetailUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    pendingIds: Set<String>,
    actions: VehicleActions,
    snackbar: SnackbarHostState,
    viewModel: VehicleDetailViewModel,
) {
    val fmt = state.formatter
    val vehicle = detail.vehicle
    val canEdit = vehicle.access.canEdit
    var open by rememberSheetTarget()
    var allFuel by rememberSaveable { mutableStateOf(false) }
    var allRecords by rememberSaveable { mutableStateOf(false) }
    var odometerOpen by rememberSaveable { mutableStateOf(false) }

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        item {
            VehiclePhoto(
                vehicle,
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f),
                shape = MaterialTheme.shapes.extraLarge,
                iconSize = 72.dp,
            )
        }
        item { Heading(vehicle, detail, fmt) }
        item { Stats(detail, fmt) }
        item {
            // The things done at the car first (a fill-up, a reading), then the paperwork. Viewers see why there is nothing to tap.
            if (canEdit) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.action_log_fill_up), Icons.Outlined.LocalGasStation, onClick = { actions.onLogFillUp(vehicle.id) }, modifier = Modifier.weight(1f))
                        ActionButton(stringResource(R.string.action_update_odometer), Icons.Outlined.Speed, onClick = { odometerOpen = true }, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.action_add_expense), Icons.AutoMirrored.Outlined.ReceiptLong, onClick = { actions.onAddExpense(vehicle.id) }, modifier = Modifier.weight(1f))
                        ActionButton(stringResource(R.string.action_add_document), Icons.Outlined.UploadFile, onClick = { actions.onAddDocument(vehicle.id) }, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                ViewOnlyNote(vehicle.owner?.name, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            }
        }
        if (vehicle.id in pendingIds) {
            item { PendingMark(Modifier.padding(start = 16.dp, top = 8.dp)) }
        }

        item {
            GroupHeader(
                stringResource(R.string.vehicle_service),
                action = stringResource(R.string.action_add),
                onAction = { actions.onAddSchedule(vehicle.id) }.takeIf { canEdit },
            )
        }
        item {
            if (detail.due.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_schedules))
            } else {
                GroupCard {
                    detail.due.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        DueRow(item, fmt, onClick = { open = SheetTarget.Schedule(item.schedule.id) }, showVehicle = false)
                    }
                }
            }
        }

        if (detail.inspections.isNotEmpty() || detail.policies.isNotEmpty() || canEdit) {
            item { GroupHeader(stringResource(R.string.vehicle_papers)) }
            item {
                GroupCard {
                    detail.inspections.forEachIndexed { index, inspection ->
                        if (index > 0) RowDivider()
                        val due = inspection.nextDueAt.toInstantOrNull()
                        PaperRow(
                            Icons.Outlined.EventAvailable,
                            inspectionLabel(inspection.type),
                            listOfNotNull(due?.let(fmt::date), due?.let { dueDays(fmt.daysFromToday(it).toInt()) }).joinToString(" · "),
                            pending = inspection.id in pendingIds,
                            onClick = { open = SheetTarget.Inspection(inspection.id) },
                        )
                    }
                    detail.policies.forEachIndexed { index, policy ->
                        if (index > 0 || detail.inspections.isNotEmpty()) RowDivider()
                        PaperRow(
                            Icons.Outlined.Shield,
                            policy.provider,
                            listOfNotNull(
                                policy.endDate.toInstantOrNull()?.let { stringResource(R.string.dashboard_insurance_until, fmt.date(it)) },
                                fmt.money(policy.premiumCents, policy.currency).let { stringResource(R.string.insurance_premium, it) },
                            ).joinToString(" · "),
                            pending = policy.id in pendingIds,
                            onClick = { open = SheetTarget.Insurance(policy.id) },
                        )
                    }
                    if (canEdit) {
                        if (detail.inspections.isNotEmpty() || detail.policies.isNotEmpty()) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(stringResource(R.string.action_add_inspection), Icons.Outlined.EventAvailable, onClick = { actions.onAddInspection(vehicle.id) }, modifier = Modifier.weight(1f))
                            ActionButton(stringResource(R.string.action_add_insurance), Icons.Outlined.Shield, onClick = { actions.onAddInsurance(vehicle.id) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        item {
            GroupHeader(
                stringResource(R.string.nav_fuel),
                action = if (allFuel) stringResource(R.string.action_show_less) else stringResource(R.string.action_show_all, detail.fuel.size),
                onAction = { allFuel = !allFuel }.takeIf { detail.fuel.size > PREVIEW },
            )
        }
        item {
            if (detail.fuel.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_fuel))
            } else {
                GroupCard {
                    detail.fuel.take(if (allFuel) Int.MAX_VALUE else PREVIEW).forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        FuelRow(
                            entry,
                            vehicle = null,
                            fmt = fmt,
                            onClick = { open = SheetTarget.Fuel(entry.id) },
                            economy = detail.segments[entry.id]?.let { fmt.economy(it.litersPer100Km) },
                            pending = entry.id in pendingIds,
                        )
                    }
                }
            }
        }

        item {
            GroupHeader(
                stringResource(R.string.vehicle_history),
                action = if (allRecords) stringResource(R.string.action_show_less) else stringResource(R.string.action_show_all, detail.records.size),
                onAction = { allRecords = !allRecords }.takeIf { detail.records.size > PREVIEW },
            )
        }
        item {
            if (detail.records.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_records))
            } else {
                GroupCard {
                    detail.records.take(if (allRecords) Int.MAX_VALUE else PREVIEW).forEachIndexed { index, record ->
                        if (index > 0) RowDivider()
                        RecordRow(record, state.recordName(record), vehicle = null, fmt, pending = record.id in pendingIds) { open = SheetTarget.Record(record.id) }
                    }
                }
            }
        }

        specs(vehicle, fmt)
    }

    when (val target = open) {
        is SheetTarget.Schedule -> detail.due.firstOrNull { it.schedule.id == target.id }?.let { item ->
            ScheduleSheet(
                item,
                fmt,
                onDismiss = { open = null },
                onLogService = {
                    open = null
                    actions.onLogService(item.schedule.id)
                },
                pending = item.schedule.id in pendingIds,
                onEdit = {
                    open = null
                    actions.onEditSchedule(item.schedule.id)
                },
                onEditDefaults = {
                    open = null
                    actions.onEditScheduleDefaults(item.schedule.id)
                },
                onDelete = {
                    open = null
                    viewModel.deleteSchedule(item.schedule.id)
                },
            )
        }
        is SheetTarget.Record -> detail.records.firstOrNull { it.id == target.id }?.let { record ->
            RecordSheet(
                record,
                state.recordName(record) ?: stringResource(R.string.record_untitled),
                vehicle,
                fmt,
                onDismiss = { open = null },
                pending = record.id in pendingIds,
                onEdit = {
                    open = null
                    actions.onEditRecord(record.id)
                },
                onDelete = {
                    open = null
                    viewModel.deleteRecord(record.id)
                },
            )
        }
        is SheetTarget.Fuel -> detail.fuel.firstOrNull { it.id == target.id }?.let { entry ->
            FuelSheet(
                entry,
                vehicle,
                detail.segments[entry.id],
                fmt,
                onDismiss = { open = null },
                pending = entry.id in pendingIds,
                onDelete = {
                    open = null
                    viewModel.deleteFuelEntry(entry.id)
                },
            )
        }
        is SheetTarget.Inspection -> detail.inspections.firstOrNull { it.id == target.id }?.let { inspection ->
            InspectionSheet(
                inspection,
                vehicle,
                fmt,
                onDismiss = { open = null },
                pending = inspection.id in pendingIds,
                onEdit = {
                    open = null
                    actions.onEditInspection(vehicle.id, inspection.id)
                },
                onDelete = {
                    open = null
                    viewModel.deleteInspection(inspection.id)
                },
            )
        }
        is SheetTarget.Insurance -> detail.policies.firstOrNull { it.id == target.id }?.let { policy ->
            InsuranceSheet(
                policy,
                vehicle,
                fmt,
                onDismiss = { open = null },
                pending = policy.id in pendingIds,
                onEdit = {
                    open = null
                    actions.onEditInsurance(vehicle.id, policy.id)
                },
                onDelete = {
                    open = null
                    viewModel.deleteInsurance(policy.id)
                },
            )
        }
        null -> Unit
    }

    if (odometerOpen) {
        OdometerSheet(
            vehicleId = vehicle.id,
            onDismiss = { odometerOpen = false },
            snackbar = snackbar,
        )
    }
}

@Composable
private fun Heading(vehicle: Vehicle, detail: VehicleDetail, fmt: Formatter) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(vehicle.displayName, style = MaterialTheme.typography.headlineSmall)
        val line = listOfNotNull(vehicle.licensePlate, vehicle.color).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vehicle.access.isShared) SharedBy(vehicle.owner?.name)
        Row(Modifier.padding(top = 8.dp)) { VehicleStatusChip(detail.summary) }
    }
}

@Composable
private fun Stats(detail: VehicleDetail, fmt: Formatter) {
    StatStrip(
        listOf(
            stringResource(R.string.field_odometer) to fmt.distance(detail.vehicle.currentOdometerKm),
            stringResource(R.string.fuel_avg_economy) to (detail.averageConsumption?.let { fmt.economy(it) } ?: "—"),
            stringResource(R.string.vehicle_spent_this_year) to fmt.money(detail.spentThisYearCents, detail.vehicle.purchaseCurrency),
        ),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    )
}

/**
 * Up to three figures side by side, value above label. Values stay on one line and shrink to fit rather than wrap;
 * labels may take two lines and hyphenate, so "Durchschnittsverbrauch" breaks at a syllable rather than anywhere.
 * Each figure is one TalkBack item ("123,456 km, Odometer").
 */
@Composable
fun StatStrip(stats: List<Pair<String, String>>, modifier: Modifier = Modifier, title: String? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface)
            val labelStyle = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto)
            stats.forEach { (label, value) ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(value, style = style, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize))
                    Text(label, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
      }
    }
}

/** An inspection or policy row: one tap target that opens its sheet, where Edit and Delete live (D-A20). */
@Composable
private fun PaperRow(icon: ImageVector, title: String, subtitle: String, pending: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (pending) PendingMark(Modifier.padding(top = 2.dp))
        }
        Text(fmt.money(record.costCents, record.currency), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun EmptyGroup(text: String) {
    GroupCard {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun LazyListScope.specs(vehicle: Vehicle, fmt: Formatter) {
    item { GroupHeader(stringResource(R.string.vehicle_details)) }
    item {
        GroupCard(Modifier.padding(bottom = 8.dp)) {
            val specs: Specs? = vehicle.specs
            DetailRow(stringResource(R.string.spec_engine), specs?.engineDescription)
            DetailRow(
                stringResource(R.string.spec_power),
                listOfNotNull(specs?.powerKw?.let { "$it kW" }, specs?.powerPs?.let { "$it ${stringResource(R.string.unit_ps)}" })
                    .joinToString(" / ").ifEmpty { null },
            )
            DetailRow(stringResource(R.string.spec_torque), specs?.torqueNm?.let { "$it Nm" })
            DetailRow(stringResource(R.string.spec_displacement), specs?.displacementCc?.let { "$it cm³" })
            DetailRow(stringResource(R.string.spec_fuel), specs?.fuelType?.let { fuelTypeLabel(it) })
            DetailRow(stringResource(R.string.spec_transmission), specs?.transmissionTypes?.joinToString { enumLabel(it) }?.ifEmpty { null })
            DetailRow(stringResource(R.string.spec_drive), specs?.driveType?.let { driveTypeLabel(it) })
            DetailRow(stringResource(R.string.spec_body), specs?.bodyType?.let { bodyTypeLabel(it) })
            DetailRow(stringResource(R.string.field_vin), vehicle.vin)
            DetailRow(stringResource(R.string.field_plate), vehicle.licensePlate)
            DetailRow(stringResource(R.string.vehicle_form_hsn_tsn), listOfNotNull(vehicle.hsn, vehicle.tsn).joinToString(" / ").ifEmpty { null })
            DetailRow(stringResource(R.string.field_color), vehicle.color)
            DetailRow(stringResource(R.string.field_purchased), vehicle.purchaseDate.toInstantOrNull()?.let(fmt::date))
            DetailRow(stringResource(R.string.field_purchase_price), vehicle.purchasePriceCents?.let { fmt.money(it, vehicle.purchaseCurrency) })
            DetailRow(stringResource(R.string.field_notes), vehicle.notes)
        }
    }
}

private const val PREVIEW = 3

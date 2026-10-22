package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import io.github.conchad.smartgarage.core.model.Modification
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
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.InspectionSheet
import io.github.conchad.smartgarage.ui.components.InsuranceSheet
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RecordSheet
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.components.WriteQueueBanner
import io.github.conchad.smartgarage.ui.components.bodyTypeLabel
import io.github.conchad.smartgarage.ui.components.coverageLabel
import io.github.conchad.smartgarage.ui.components.driveTypeLabel
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.enumLabel
import io.github.conchad.smartgarage.ui.components.forms.ActionButton
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.components.forms.PendingMark
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.components.fuelTypeLabel
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.intervalText
import io.github.conchad.smartgarage.ui.components.modificationCategoryLabel
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
import io.github.conchad.smartgarage.ui.glass.AuroraTint
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.GlassDropdown
import io.github.conchad.smartgarage.ui.glass.GlassGroupHeader
import io.github.conchad.smartgarage.ui.glass.MetallicFrame
import io.github.conchad.smartgarage.ui.glass.MetallicSheenHost
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.VehicleTint
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.rememberTilt
import io.github.conchad.smartgarage.ui.glass.staticMetallicBrush
import io.github.conchad.smartgarage.ui.links.dueCalendarEvent
import io.github.conchad.smartgarage.ui.links.rememberAddToCalendar
import io.github.conchad.smartgarage.ui.links.renewalCalendarEvent

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
    /** The camera, pre-linked to a service record (D-A23). */
    val onAttachReceipt: (vehicleId: String, recordId: String) -> Unit,
    val onEditVehicle: (vehicleId: String) -> Unit,
    val onAddInspection: (vehicleId: String) -> Unit,
    val onEditInspection: (vehicleId: String, inspectionId: String) -> Unit,
    val onAddInsurance: (vehicleId: String) -> Unit,
    val onEditInsurance: (vehicleId: String, policyId: String) -> Unit,
)

/**
 * One vehicle on glass (D-A25 for the order, D-A26 for the look): the car in its chrome frame, who owns it, then
 * every group as its own drop-down card on the shell's aurora, which takes the car's colour while this screen is up.
 * The photo card has the same tap / long-press menu as the home card (change or upload the photo, a device-local
 * nickname), so the name shown here is the nickname when one is set.
 */
@Composable
fun VehicleDetailScreen(
    onBack: () -> Unit,
    actions: VehicleActions,
    viewModel: VehicleDetailViewModel = hiltViewModel(),
    cardActionsViewModel: VehicleCardActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    val detail = state.detail
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    DeniedSnackbar(viewModel, snackbar)
    // Photo picker, rename dialog and their feedback are the home card's (D-A24), hosted once here.
    val cardActions = rememberVehicleCardActions(cardActionsViewModel, snackbar)
    LaunchedEffect(closed) { if (closed) onBack() }
    AuroraTint(VehicleTint.color(detail?.vehicle?.color, MaterialTheme.colorScheme.primary))

    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableIntStateOf(0) }

    // The photo card carries the vehicle's name (note 13); the bar takes the name over once that scrolls away.
    val headingGone by remember { derivedStateOf { listState.firstVisibleItemIndex >= 1 } }
    MetallicSheenHost {
        AppScaffold(
            transparent = true,
            title = {
                val vehicle = detail?.vehicle
                if (headingGone && vehicle != null) {
                    MetallicText(cardActions.titleOf(vehicle), style = MaterialTheme.typography.titleLarge, animated = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            onBack = onBack,
            snackbarHost = { SnackbarHost(snackbar) },
            actions = {
                val vehicle = detail?.vehicle
                if (vehicle != null) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more_options))
                    }
                    // The same choices as the photo's long-press, for anyone who doesn't find that; renaming is local, so viewers get it too.
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (vehicle.access.canEdit) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit_vehicle)) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    actions.onEditVehicle(vehicle.id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(photoActionRes(vehicle))) },
                                leadingIcon = { Icon(Icons.Outlined.AddAPhoto, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    cardActions.changePhoto(vehicle)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename_vehicle)) },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                cardActions.rename(vehicle)
                            },
                        )
                        if (vehicle.access.canEdit && !vehicle.access.isShared) {
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
                                cardActions = cardActions,
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
    }

    // Deleting a vehicle takes everything with it, so it asks twice; the second time with the consequences spelled out.
    // The server's name, not the nickname: that is what is being deleted.
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
    cardActions: VehicleCardActions,
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
    // The figures and the actions start open: they are what one came for. The car's paperwork and specs start closed
    // (note 15); each remembers its own state across rotation.
    var glanceOpen by rememberSaveable { mutableStateOf(true) }
    var quickOpen by rememberSaveable { mutableStateOf(true) }
    val addToCalendar = rememberAddToCalendar(snackbar)
    val sections = detailSections(vehicle, detail, state, fmt)

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
        // Notes 13–15: the name inside the photo, then owner, the four immediate figures, the quick actions,
        // and the rest of the car as its own drop-downs; service, papers, fuel and history follow.
        item { PhotoCard(vehicle, cardActions) }
        item { OwnerLine(state.ownerName, vehicle.access.isShared, detail) }
        item {
            GlassDropdown(stringResource(R.string.vehicle_at_a_glance), expanded = glanceOpen, onToggle = { glanceOpen = !glanceOpen }, modifier = Modifier.padding(top = 12.dp)) {
                ImmediateInfo(detail, fmt)
            }
        }
        item {
            // The things done at the car first (a fill-up, a reading), then the paperwork. Viewers see why there is nothing to tap.
            if (canEdit) {
                GlassDropdown(stringResource(R.string.vehicle_quick_actions), expanded = quickOpen, onToggle = { quickOpen = !quickOpen }, modifier = Modifier.padding(top = 12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(stringResource(R.string.action_log_fill_up), Icons.Outlined.LocalGasStation, onClick = { actions.onLogFillUp(vehicle.id) }, modifier = Modifier.weight(1f))
                            ActionButton(stringResource(R.string.action_update_odometer), Icons.Outlined.Speed, onClick = { odometerOpen = true }, modifier = Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton(stringResource(R.string.action_add_expense), Icons.AutoMirrored.Outlined.ReceiptLong, onClick = { actions.onAddExpense(vehicle.id) }, modifier = Modifier.weight(1f))
                            ActionButton(stringResource(R.string.action_add_document), Icons.Outlined.UploadFile, onClick = { actions.onAddDocument(vehicle.id) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                ViewOnlyNote(vehicle.owner?.name, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            }
        }
        if (vehicle.id in pendingIds) {
            item { PendingMark(Modifier.padding(start = 16.dp, top = 8.dp)) }
        }

        // "Vehicle details" (note 15): everything about the car itself and nothing about service, each group its own
        // drop-down (note 12) so the cards below slide as one opens. Groups with nothing in them aren't shown at all.
        item { GlassGroupHeader(stringResource(R.string.vehicle_details_all)) }
        if (sections.isEmpty()) {
            item { EmptyGroup(stringResource(R.string.vehicle_no_details)) }
        } else {
            items(sections, key = { it.key }) { section ->
                var expanded by rememberSaveable { mutableStateOf(false) }
                GlassDropdown(
                    section.title,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    modifier = Modifier.padding(bottom = 12.dp),
                ) { section.content() }
            }
        }

        item {
            GlassGroupHeader(
                stringResource(R.string.vehicle_service),
                action = stringResource(R.string.action_add),
                onAction = { actions.onAddSchedule(vehicle.id) }.takeIf { canEdit },
            )
        }
        item {
            if (detail.due.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_schedules))
            } else {
                GlassCard {
                    detail.due.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        DueRow(item, fmt, onClick = { open = SheetTarget.Schedule(item.schedule.id) }, showVehicle = false)
                    }
                }
            }
        }

        if (detail.inspections.isNotEmpty() || detail.policies.isNotEmpty() || canEdit) {
            item { GlassGroupHeader(stringResource(R.string.vehicle_papers)) }
            item {
                GlassCard {
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
            GlassGroupHeader(
                stringResource(R.string.nav_fuel),
                action = if (allFuel) stringResource(R.string.action_show_less) else stringResource(R.string.action_show_all, detail.fuel.size),
                onAction = { allFuel = !allFuel }.takeIf { detail.fuel.size > PREVIEW },
            )
        }
        item {
            if (detail.fuel.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_fuel))
            } else {
                GlassCard {
                    // Consumption figures only while fuel economy is tracked (D-A25); costs and volumes always.
                    val average = detail.averageConsumption?.takeIf { state.trackFuelEconomy }?.let { fmt.economy(it) }
                    if (average != null) {
                        DetailRow(stringResource(R.string.fuel_avg_economy), average)
                        RowDivider()
                    }
                    detail.fuel.take(if (allFuel) Int.MAX_VALUE else PREVIEW).forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        FuelRow(
                            entry,
                            vehicle = null,
                            fmt = fmt,
                            onClick = { open = SheetTarget.Fuel(entry.id) },
                            economy = detail.segments[entry.id]?.takeIf { state.trackFuelEconomy }?.let { fmt.economy(it.litersPer100Km) },
                            pending = entry.id in pendingIds,
                        )
                    }
                }
            }
        }

        item {
            GlassGroupHeader(
                stringResource(R.string.vehicle_history),
                action = if (allRecords) stringResource(R.string.action_show_less) else stringResource(R.string.action_show_all, detail.records.size),
                onAction = { allRecords = !allRecords }.takeIf { detail.records.size > PREVIEW },
            )
        }
        item {
            if (detail.records.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_records))
            } else {
                GlassCard {
                    detail.records.take(if (allRecords) Int.MAX_VALUE else PREVIEW).forEachIndexed { index, record ->
                        if (index > 0) RowDivider()
                        RecordRow(record, state.recordName(record), vehicle = null, fmt, pending = record.id in pendingIds) { open = SheetTarget.Record(record.id) }
                    }
                }
            }
        }
    }

    when (val target = open) {
        is SheetTarget.Schedule -> detail.due.firstOrNull { it.schedule.id == target.id }?.let { item ->
            val event = item.dueAt?.let { due ->
                dueCalendarEvent(item.name, vehicle, fmt.localDate(due), intervalText(item.schedule.intervalKm, item.schedule.intervalMonths, fmt), item.schedule.notes)
            }
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
                onAddToCalendar = event?.let { e -> { addToCalendar(e) } },
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
                onAttachReceipt = {
                    open = null
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
                onDismiss = { open = null },
                pending = entry.id in pendingIds,
                onDelete = {
                    open = null
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
                onAddToCalendar = event?.let { e -> { addToCalendar(e) } },
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

/**
 * The photo in the chrome frame with the vehicle's name inside it, bottom-left over the shared photo scrim (note 13),
 * 16:9. The name is the device-local nickname when there is one, with the server's name under it, as on the home
 * card. Tap or hold for the photo / rename menu (note 10); TalkBack gets the same as custom actions. The frame tilts
 * with the phone under the D-A24 sensor rules (only while this screen is resumed, released on dispose).
 */
@Composable
private fun PhotoCard(vehicle: Vehicle, cardActions: VehicleCardActions) {
    val tokens = glass
    val tilt = rememberTilt(enabled = true)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val nickname = cardActions.nicknameOf(vehicle)
    val menuLabel = stringResource(R.string.vehicle_photo_options)
    Box(Modifier.padding(horizontal = 16.dp)) {
        MetallicFrame(
            tilt = tilt,
            cornerRadius = tokens.radiusLarge,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .vehicleCardSemantics(vehicle, cardActions, onOpen = null),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .combinedClickable(onClick = { menuOpen = true }, onClickLabel = menuLabel, onLongClick = { menuOpen = true }),
            ) {
                VehiclePhoto(vehicle, Modifier.fillMaxSize(), iconSize = 72.dp)
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(tokens.photoScrim))
                        .padding(start = 20.dp, end = 20.dp, top = 48.dp, bottom = 16.dp),
                ) {
                    Text(cardActions.titleOf(vehicle), style = MaterialTheme.typography.titleLarge, color = tokens.onPhoto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (nickname != null) {
                        Text(vehicle.displayName, style = MaterialTheme.typography.bodySmall, color = tokens.onPhotoSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            VehicleCardMenu(vehicle, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = cardActions)
        }
    }
}

/** "Owner: Connor" under the photo (note 14) with the service status chips on the right; the owner line is one TalkBack item. */
@Composable
private fun OwnerLine(ownerName: String?, shared: Boolean, detail: VehicleDetail) {
    val tokens = glass
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (shared) Icons.Outlined.People else Icons.Outlined.Person,
                contentDescription = null,
                tint = tokens.sheen,
                modifier = Modifier.padding(end = 8.dp).size(22.dp),
            )
            MetallicText(stringResource(R.string.vehicle_owner, ownerName ?: "—"), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        VehicleStatusChip(detail.summary, Modifier.padding(start = 8.dp))
    }
}

/**
 * The four figures wanted at a glance (note 14): mileage, the insurer in force, the plate and this year's money,
 * two by two. Each tile is one TalkBack item ("Mileage, 123,456 km").
 */
@Composable
private fun ImmediateInfo(detail: VehicleDetail, fmt: Formatter) {
    val vehicle = detail.vehicle
    val tiles = listOf(
        Triple(Icons.Outlined.Speed, stringResource(R.string.vehicle_mileage), fmt.distance(vehicle.currentOdometerKm)),
        Triple(Icons.Outlined.Shield, stringResource(R.string.vehicle_insurance_plan), detail.currentPolicy?.provider ?: "—"),
        Triple(Icons.Outlined.Badge, stringResource(R.string.field_plate), vehicle.licensePlate ?: "—"),
        Triple(Icons.Outlined.Payments, stringResource(R.string.vehicle_spent_this_year), fmt.money(detail.spentThisYearCents, vehicle.purchaseCurrency)),
    )
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (icon, label, value) -> InfoTile(icon, label, value, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** One figure on a glass pill with its label above; the value shrinks before it wraps so a long plate or sum stays readable. */
@Composable
private fun InfoTile(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    val tokens = glass
    val shape = RoundedCornerShape(tokens.radiusMedium)
    Column(
        modifier
            .clip(shape)
            .background(tokens.pill)
            .border(1.dp, tokens.pillBorder, shape)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(18.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(hyphens = Hyphens.Auto),
                color = tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        val style = MaterialTheme.typography.titleMedium.copy(brush = staticMetallicBrush(tokens))
        BasicText(value, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize))
    }
}

/** One drop-down under "Vehicle details": a stable key for its saved open/closed state, the header, and the rows. */
private class DetailSection(val key: String, val title: String, val content: @Composable () -> Unit)

/**
 * The groups under "Vehicle details" (note 15) that have anything to show, in reading order: registration (plate,
 * HSN/TSN, inspection sticker), VIN, colour, purchase, specs, tyres, modifications, the owner's custom fields and the
 * notes. Tyres are only a group when the owner keeps a tyre custom field or modification; there is no tyre model to
 * invent one from.
 */
@Composable
private fun detailSections(vehicle: Vehicle, detail: VehicleDetail, state: VehicleDetailUiState, fmt: Formatter): List<DetailSection> {
    val specs: Specs? = vehicle.specs
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    val registration = listOfNotNull(
        vehicle.licensePlate?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.field_plate) to it },
        listOfNotNull(vehicle.hsn, vehicle.tsn).joinToString(" / ").ifEmpty { null }?.let { stringResource(R.string.vehicle_form_hsn_tsn) to it },
        detail.inspections.firstNotNullOfOrNull { it.stickerNumber?.takeIf(String::isNotBlank) }?.let { stringResource(R.string.inspection_sticker) to it },
    )
    val vin = vehicle.vin?.takeIf { it.isNotBlank() }
    val colour = vehicle.color?.takeIf { it.isNotBlank() }
    val purchase = listOfNotNull(
        vehicle.purchaseDate.toInstantOrNull()?.let { stringResource(R.string.field_purchased) to fmt.date(it) },
        vehicle.purchasePriceCents?.let { stringResource(R.string.field_purchase_price) to fmt.money(it, vehicle.purchaseCurrency) },
    )
    val specRows = listOfNotNull(
        specs?.engineDescription?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.spec_engine) to it },
        listOfNotNull(specs?.powerKw?.let { "$it kW" }, specs?.powerPs?.let { "$it ${stringResource(R.string.unit_ps)}" })
            .joinToString(" / ").ifEmpty { null }?.let { stringResource(R.string.spec_power) to it },
        specs?.torqueNm?.let { stringResource(R.string.spec_torque) to "$it Nm" },
        specs?.displacementCc?.let { stringResource(R.string.spec_displacement) to "$it cm³" },
        specs?.fuelType?.let { stringResource(R.string.spec_fuel) to fuelTypeLabel(it) },
        specs?.transmissionTypes?.joinToString { enumLabel(it) }?.ifEmpty { null }?.let { stringResource(R.string.spec_transmission) to it },
        specs?.driveType?.let { stringResource(R.string.spec_drive) to driveTypeLabel(it) },
        specs?.bodyType?.let { stringResource(R.string.spec_body) to bodyTypeLabel(it) },
    )
    val tires = state.tireField?.let { (field, value) -> fmt.customFieldValue(field.fieldType, field.unit, value, yes, no) }
        ?: state.tireModification?.let { mod ->
            listOfNotNull(mod.name.ifBlank { null }, mod.installedAt.toInstantOrNull()?.let { stringResource(R.string.modification_installed, fmt.date(it)) })
                .joinToString(" · ").ifEmpty { null }
        }
    val custom = state.otherCustomFields.map { (field, value) -> field.label to fmt.customFieldValue(field.fieldType, field.unit, value, yes, no) }
    val notes = vehicle.notes?.takeIf { it.isNotBlank() }

    return listOfNotNull(
        registration.takeIf { it.isNotEmpty() }?.let { rows -> DetailSection("registration", stringResource(R.string.vehicle_registration)) { DetailRows(rows) } },
        vin?.let { DetailSection("vin", stringResource(R.string.field_vin)) { ValueBlock(it) } },
        colour?.let { DetailSection("colour", stringResource(R.string.field_color)) { ColourBlock(it) } },
        purchase.takeIf { it.isNotEmpty() }?.let { rows -> DetailSection("purchase", stringResource(R.string.vehicle_purchase)) { DetailRows(rows) } },
        specRows.takeIf { it.isNotEmpty() }?.let { rows -> DetailSection("specs", stringResource(R.string.vehicle_specs)) { DetailRows(rows) } },
        tires?.let { DetailSection("tires", stringResource(R.string.vehicle_tires)) { ValueBlock(it) } },
        state.modifications.takeIf { it.isNotEmpty() }?.let { mods ->
            DetailSection("modifications", stringResource(R.string.vehicle_modifications)) {
                mods.forEachIndexed { index, mod ->
                    if (index > 0) RowDivider()
                    ModificationRow(mod, fmt)
                }
            }
        },
        custom.takeIf { it.isNotEmpty() }?.let { rows -> DetailSection("custom", stringResource(R.string.vehicle_custom_fields)) { DetailRows(rows) } },
        notes?.let { DetailSection("notes", stringResource(R.string.field_notes)) { ValueBlock(it) } },
    )
}

@Composable
private fun DetailRows(rows: List<Pair<String, String>>) {
    rows.forEach { (label, value) -> DetailRow(label, value) }
}

/** A group whose header is the label: the value alone, full width, so a VIN or a note has the room. */
@Composable
private fun ValueBlock(value: String) {
    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

/** The colour as written, with a swatch in the tint the aurora takes from it when the text names a colour. */
@Composable
private fun ColourBlock(colour: String) {
    val tokens = glass
    val swatch = VehicleTint.argb(colour)?.let { Color(it) }
    Row(Modifier.fillMaxWidth().padding(16.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        if (swatch != null) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(1.dp, tokens.pillBorder, CircleShape),
            )
        }
        Text(colour, style = MaterialTheme.typography.bodyLarge)
    }
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
    val tokens = glass
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, container = tokens.pill, content = tokens.sheen)
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
    GlassCard {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = glass.textSecondary,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun ModificationRow(mod: Modification, fmt: Formatter) {
    val tokens = glass
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(Icons.Outlined.Build, container = tokens.pill, content = tokens.sheen)
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(mod.name.ifBlank { modificationCategoryLabel(mod.category) }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    modificationCategoryLabel(mod.category),
                    mod.installedAt.toInstantOrNull()?.let { stringResource(R.string.modification_installed, fmt.date(it)) },
                    mod.costCents?.let { fmt.money(it, mod.currency) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            mod.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        val gains = listOfNotNull(
            mod.addedPowerPs?.let { "+$it ${stringResource(R.string.unit_ps)}" },
            mod.addedPowerKw?.let { "+$it kW" },
            mod.addedTorqueNm?.let { "+$it Nm" },
        )
        if (gains.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                gains.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary) }
            }
        }
    }
}

private const val PREVIEW = 3

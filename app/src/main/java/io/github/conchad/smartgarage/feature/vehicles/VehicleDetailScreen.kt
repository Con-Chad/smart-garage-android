package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.DetailRow
import io.github.conchad.smartgarage.ui.components.DueRow
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.FuelRow
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.SwipeToDelete
import io.github.conchad.smartgarage.ui.components.WriteQueueBanner
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.forms.ActionButton
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.components.forms.PendingMark
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
import io.github.conchad.smartgarage.ui.glass.AuroraTint
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.GlassDropdown
import io.github.conchad.smartgarage.ui.glass.GlassGroupHeader
import io.github.conchad.smartgarage.ui.glass.LocalTilt
import io.github.conchad.smartgarage.ui.glass.MetallicSheenHost
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.NoSheen
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.orSnap
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import io.github.conchad.smartgarage.ui.glass.drawerSlide
import io.github.conchad.smartgarage.ui.glass.popOutFade
import io.github.conchad.smartgarage.ui.glass.rememberPopOut
import io.github.conchad.smartgarage.ui.glass.popIn
import io.github.conchad.smartgarage.ui.glass.rememberDrawerSlide
import io.github.conchad.smartgarage.ui.glass.rememberPopIn
import io.github.conchad.smartgarage.ui.glass.vehicleHero
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.rememberTilt
import kotlinx.coroutines.delay
import io.github.conchad.smartgarage.ui.glass.topFadeEdge

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
    /** True once this page has been popped by Back to Home, as opposed to covered by a pushed screen: its cards then pop out (D-A43, D-A46). */
    isPopped: () -> Boolean = { true },
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
    AuroraTint(detail?.vehicle?.id, detail?.vehicle?.color)

    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var spinnerDue by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SPINNER_HOLD_MS)
        spinnerDue = true
    }
    var confirmDelete by rememberSaveable { mutableIntStateOf(0) }

    // The photo card carries the vehicle's name (note 13); the bar takes the name over once that scrolls away.
    val headingGone by remember { derivedStateOf { listState.firstVisibleItemIndex >= 1 } }
    // D-A49: the bar's name fades in as the photo card leaves (and out as it returns) instead of popping in. The
    // threshold above flips once per crossing; the eased alpha is read only in the draw phase, and the title stays
    // composed only while it is visible at all, so neither the scroll nor the fade recomposes per frame.
    val titleAlpha = animateFloatAsState(
        if (headingGone) 1f else 0f,
        tween<Float>(Motion.SHORT_MS, easing = FastOutSlowInEasing).orSnap(rememberReducedMotion()),
        label = "barTitle",
    )
    val titleShown by remember { derivedStateOf { titleAlpha.value > 0f } }
    // One gyroscope pointer for the photo card and the figure tiles (D-A30).
    val tilt = rememberTilt()
    // Leaving by Back (D-A43): the cards bulge and shrink away together while the hero glides home; the bar fades with them.
    val popOut = rememberPopOut(isPopped)
    CompositionLocalProvider(LocalTilt provides tilt) {
        MetallicSheenHost {
            AppScaffold(
                transparent = true,
                topBarModifier = Modifier.popOutFade(popOut),
                title = {
                    val vehicle = detail?.vehicle
                    if (titleShown && vehicle != null) {
                        MetallicText(cardActions.titleOf(vehicle), Modifier.graphicsLayer { alpha = titleAlpha.value }, style = MaterialTheme.typography.titleLarge, animated = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                // D-A70: only the bar's title keeps the metallic sheen; the cards' titles and headers don't sweep.
                NoSheen { Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                    WriteQueueBanner(sync, viewModel, modifier = Modifier.popOutFade(popOut))
                    RefreshBox(sync, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                        // Arriving from a card (D-A35): the state is seeded from what Home or the Vehicles tab holds, so it is
                        // loaded in the first frame and LoadedContent opens straight on the content (no cross-fade), with the
                        // photo card ready to glide in as the hero. Only an unseeded open (a deep link, a cold cache) waits
                        // here (D-A33): empty while the cache answers, then the spinner after SPINNER_HOLD_MS.
                        if (!state.loaded && !sync.firstLoad && !spinnerDue) return@RefreshBox
                        LoadedContent(loaded = state.loaded, sync = sync, onRetry = viewModel::refresh) {
                            if (detail != null) {
                                VehicleDetailContent(
                                    detail, state, listState, PaddingValues(top = glass.contentFadeLift, bottom = padding.calculateBottomPadding() + 24.dp),
                                    pendingIds = pendingIds,
                                    actions = actions,
                                    cardActions = cardActions,
                                    snackbar = snackbar,
                                    viewModel = viewModel,
                                    popOut = popOut,
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
                } }
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
    popOut: State<Float>?,
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
    val sections = detailSections(vehicle, detail, state, fmt)
    // The photo card is the hero (D-A34): from Home it glides here as a shared element and the nav transition into
    // this route is `None`; each card beneath it pops in after the one above (D-A37): 100 ms apart (D-A40), top to
    // bottom, a small bounce each, the first as the hero starts rising (D-A43), each at its final spot, drawn above
    // the hero. Only on that path (D-A46): opened any other way, or returned to by Back, the page slides in whole
    // and nothing pops.
    val slide = rememberDrawerSlide(vehicle.id)
    val pop = rememberPopIn(staggered = true, startDelayMs = Motion.POP_WITH_HERO_MS, popOut = popOut, enabled = slide.fromHero)

    // Every item has a stable key and a content type (D-A42), so a row appearing above (the pending mark) keeps the
    // scroll anchored and only like composes into like. The order is unchanged: the pop-in stagger is claimed in
    // first-composition order, which is still top to bottom.
    // D-A49, D-A76: once scrolled, the cards fade out under the bar, row by row, instead of being cut on a line.
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize().topFadeEdge(listState, glass.contentFadeLift)) {
        // Notes 13–15: the name inside the photo, then owner, the four immediate figures, the quick actions,
        // and the rest of the car as its own drop-downs; service, papers, fuel and history follow.
        // The photo card is the card one tapped, so it never pops or fades (D-A33, D-A34); the cards beneath slide up under it.
        item(key = "photo", contentType = DetailItem.Photo) { PhotoCard(vehicle, cardActions, Modifier.vehicleHero(vehicle.id, page = true)) }
        item(key = "owner", contentType = DetailItem.Owner) {
            OwnerLine(
                cardActions.ownerNameOf(vehicle, state.ownerName),
                vehicle.access.isShared,
                detail,
                Modifier.drawerSlide(slide).popIn(pop),
                // D-A43: the owner's name as shown on this phone; clearing it falls back to the server's.
                onLongPress = { cardActions.renameOwner(vehicle, state.ownerName ?: "—") },
            )
        }
        item(key = "glance", contentType = DetailItem.Dropdown) {
            GlassDropdown(stringResource(R.string.vehicle_at_a_glance), expanded = glanceOpen, onToggle = { glanceOpen = !glanceOpen }, modifier = Modifier.padding(top = 12.dp).drawerSlide(slide).popIn(pop)) {
                ImmediateInfo(detail, fmt)
            }
        }
        item(key = "quick_actions", contentType = DetailItem.QuickActions) {
            // The things done at the car first (a fill-up, a reading), then the paperwork. Viewers see why there is nothing to tap.
            if (canEdit) {
                GlassDropdown(stringResource(R.string.vehicle_quick_actions), expanded = quickOpen, onToggle = { quickOpen = !quickOpen }, modifier = Modifier.padding(top = 12.dp).drawerSlide(slide).popIn(pop)) {
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
                ViewOnlyNote(vehicle.owner?.name, Modifier.drawerSlide(slide).popIn(pop).padding(start = 16.dp, end = 16.dp, top = 12.dp))
            }
        }
        if (vehicle.id in pendingIds) {
            item(key = "pending", contentType = DetailItem.Pending) { PendingMark(Modifier.drawerSlide(slide).popIn(pop).padding(start = 16.dp, top = 8.dp)) }
        }

        // "Vehicle details" (note 15): everything about the car itself and nothing about service, each group its own
        // drop-down (note 12) so the cards below slide as one opens. Groups with nothing in them aren't shown at all.
        item(key = "details_header", contentType = DetailItem.Header) { GlassGroupHeader(stringResource(R.string.vehicle_details_all), Modifier.drawerSlide(slide).popIn(pop)) }
        if (sections.isEmpty()) {
            item(key = "details_empty", contentType = DetailItem.Card) { EmptyGroup(stringResource(R.string.vehicle_no_details), Modifier.drawerSlide(slide).popIn(pop)) }
        } else {
            items(sections, key = { "section_" + it.key }, contentType = { DetailItem.Dropdown }) { section ->
                var expanded by rememberSaveable { mutableStateOf(false) }
                GlassDropdown(
                    section.title,
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    modifier = Modifier.padding(bottom = 12.dp).drawerSlide(slide).popIn(pop),
                ) { section.content() }
            }
        }

        item(key = "service_header", contentType = DetailItem.Header) {
            GlassGroupHeader(
                stringResource(R.string.vehicle_service),
                modifier = Modifier.drawerSlide(slide).popIn(pop),
                action = stringResource(R.string.action_add),
                onAction = { actions.onAddSchedule(vehicle.id) }.takeIf { canEdit },
            )
        }
        item(key = "service", contentType = DetailItem.Card) {
            if (detail.due.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_schedules), Modifier.drawerSlide(slide).popIn(pop))
            } else {
                GlassCard(Modifier.drawerSlide(slide).popIn(pop)) {
                    detail.due.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        // Swipe left to deactivate, behind the same question as the sheet's button; keyed so the
                        // swipe state stays with its schedule when one above goes.
                        key(item.schedule.id) {
                            SwipeToDelete(
                                enabled = canEdit,
                                title = stringResource(R.string.schedule_deactivate_title, item.name),
                                body = stringResource(R.string.schedule_deactivate_body),
                                confirmLabel = stringResource(R.string.schedule_deactivate),
                                onDelete = { viewModel.deleteSchedule(item.schedule.id) },
                            ) {
                                DueRow(item, fmt, onClick = { open = SheetTarget.Schedule(item.schedule.id) }, showVehicle = false)
                            }
                        }
                    }
                }
            }
        }

        if (detail.inspections.isNotEmpty() || detail.policies.isNotEmpty() || canEdit) {
            item(key = "papers_header", contentType = DetailItem.Header) { GlassGroupHeader(stringResource(R.string.vehicle_papers), Modifier.drawerSlide(slide).popIn(pop)) }
            item(key = "papers", contentType = DetailItem.Card) {
                GlassCard(Modifier.drawerSlide(slide).popIn(pop)) {
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

        item(key = "fuel_header", contentType = DetailItem.Header) {
            GlassGroupHeader(
                stringResource(R.string.nav_fuel),
                modifier = Modifier.drawerSlide(slide).popIn(pop),
                action = if (allFuel) stringResource(R.string.action_show_less) else stringResource(R.string.action_show_all, detail.fuel.size),
                onAction = { allFuel = !allFuel }.takeIf { detail.fuel.size > PREVIEW },
            )
        }
        item(key = "fuel", contentType = DetailItem.Card) {
            if (detail.fuel.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_fuel), Modifier.drawerSlide(slide).popIn(pop))
            } else {
                GlassCard(Modifier.drawerSlide(slide).popIn(pop)) {
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

        item(key = "history_header", contentType = DetailItem.Header) {
            GlassGroupHeader(
                stringResource(R.string.vehicle_history),
                modifier = Modifier.drawerSlide(slide).popIn(pop),
                action = if (allRecords) stringResource(R.string.action_show_less) else stringResource(R.string.action_show_all, detail.records.size),
                onAction = { allRecords = !allRecords }.takeIf { detail.records.size > PREVIEW },
            )
        }
        item(key = "history", contentType = DetailItem.Card) {
            if (detail.records.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_records), Modifier.drawerSlide(slide).popIn(pop))
            } else {
                GlassCard(Modifier.drawerSlide(slide).popIn(pop)) {
                    detail.records.take(if (allRecords) Int.MAX_VALUE else PREVIEW).forEachIndexed { index, record ->
                        if (index > 0) RowDivider()
                        RecordRow(record, state.recordName(record), vehicle = null, fmt, pending = record.id in pendingIds) { open = SheetTarget.Record(record.id) }
                    }
                }
            }
        }
    }

    VehicleDetailSheets(
        target = open,
        onClose = { open = null },
        detail = detail,
        state = state,
        pendingIds = pendingIds,
        actions = actions,
        snackbar = snackbar,
        viewModel = viewModel,
    )

    if (odometerOpen) {
        OdometerSheet(
            vehicleId = vehicle.id,
            onDismiss = { odometerOpen = false },
            snackbar = snackbar,
        )
    }
}

private const val PREVIEW = 3

/** How long an unseeded page (D-A35) stays empty waiting for the cached vehicle before it shows the loading spinner (D-A33). */
private const val SPINNER_HOLD_MS = 300L

/** Content types of the vehicle page's list items (D-A42): items of one type share a composition shape. */
private enum class DetailItem { Photo, Owner, Dropdown, QuickActions, Pending, Header, Card }

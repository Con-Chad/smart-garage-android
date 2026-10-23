package io.github.conchad.smartgarage.feature.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.conchad.smartgarage.feature.vehicles.OdometerSheet
import io.github.conchad.smartgarage.feature.vehicles.QuickInfoActions
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardActions
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardActionsViewModel
import io.github.conchad.smartgarage.feature.vehicles.rememberVehicleCardActions
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.LocalBottomBarInset
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.DueRow
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.FuelRow
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.WriteQueueBanner
import io.github.conchad.smartgarage.ui.components.intervalText
import io.github.conchad.smartgarage.ui.components.relativeTime
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
import io.github.conchad.smartgarage.ui.glass.AuroraTint
import io.github.conchad.smartgarage.ui.glass.DisconnectedPopup
import io.github.conchad.smartgarage.ui.glass.FanAction
import io.github.conchad.smartgarage.ui.glass.FanFab
import io.github.conchad.smartgarage.ui.glass.FanScrim
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.GlassGroupHeader
import io.github.conchad.smartgarage.ui.glass.LocalTilt
import io.github.conchad.smartgarage.ui.glass.launch
import io.github.conchad.smartgarage.ui.glass.MetallicSheenHost
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.onAuroraInk
import io.github.conchad.smartgarage.ui.glass.PagerDots
import io.github.conchad.smartgarage.ui.glass.RefreshIcon
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.rememberDisconnectedState
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import io.github.conchad.smartgarage.ui.glass.rememberTilt
import io.github.conchad.smartgarage.ui.links.dueCalendarEvent
import io.github.conchad.smartgarage.ui.links.rememberAddToCalendar
import io.github.conchad.smartgarage.ui.sync.SyncInfo
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * What the home card's Add / Update buttons and the fan FAB start (D-A31); the shell wires each to the existing
 * form route for the vehicle in the pager.
 */
class DashboardActions(
    val onLogFillUp: (vehicleId: String) -> Unit,
    val onLogService: (vehicleId: String) -> Unit,
    val onAddExpense: (vehicleId: String) -> Unit,
    /** The fan's fourth petal (D-A32): the fill-up form with the receipt scan started on arrival. */
    val onScanReceipt: (vehicleId: String) -> Unit,
    val onAddInsurance: (vehicleId: String) -> Unit,
    val onEditInsurance: (vehicleId: String, policyId: String) -> Unit,
    val onAddInspection: (vehicleId: String) -> Unit,
    val onEditInspection: (vehicleId: String, inspectionId: String) -> Unit,
) {
    /** The same four, in the shape the shared "Quick info" drop-down takes. */
    val quickInfo = QuickInfoActions(onAddInsurance, onEditInsurance, onAddInspection, onEditInspection)
}

/**
 * The home page (D-A24): the shell's aurora takes the current vehicle's tint; one full-width glass card per
 * vehicle in a pager with a tilting photo, a "View all" pill and a "Quick info" drop-down for insurance, mileage
 * and inspection (D-A31); "Needs attention" and "Recent fill-ups" follow the pager; the monthly odometer reminder
 * sits at the top of attention; the "can't reach" popup collapses into a refresh icon in the top bar; and a radial
 * fan FAB bottom-right logs a service, an expense or a fill-up, or scans a receipt, for the vehicle in view.
 */
@Composable
fun DashboardScreen(
    profile: ServerProfile,
    account: Account,
    onOpenSettings: () -> Unit,
    onOpenVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenFuel: () -> Unit,
    onLogService: (scheduleId: String) -> Unit,
    actions: DashboardActions,
    viewModel: DashboardViewModel = hiltViewModel(),
    cardActionsViewModel: VehicleCardActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val greeting = stringResource(greetingFor(remember { LocalTime.now() }), account.greetingName)
    val settingsLabel = stringResource(R.string.nav_settings)
    val snackbar = remember { SnackbarHostState() }
    DeniedSnackbar(viewModel, snackbar)
    val cardActions = rememberVehicleCardActions(cardActionsViewModel, snackbar)
    val tokens = glass

    val vehicles = state.dashboard?.vehicles.orEmpty()
    val pagerState = rememberPagerState(pageCount = { vehicles.size })
    val current = vehicles.getOrNull(pagerState.currentPage)
    AuroraTint(current?.vehicle?.id, current?.vehicle?.color)
    val disconnected = rememberDisconnectedState(state.sync)
    // One gyroscope pointer for the screen (D-A30); the settled page's photo frame follows it.
    val tilt = rememberTilt()
    var fanOpen by rememberSaveable { mutableStateOf(false) }

    CompositionLocalProvider(LocalTilt provides tilt) {
        MetallicSheenHost {
            AppScaffold(
                transparent = true,
                title = {
                    Column {
                        MetallicText(greeting, style = MaterialTheme.typography.titleLarge, animated = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            syncLine(profile.name, state.sync),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // On the aurora with the greeting: the adaptive ink (D-A46).
                            modifier = Modifier.onAuroraInk(),
                        )
                    }
                },
                actions = {
                    RefreshIcon(disconnected, onRetry = viewModel::refresh)
                    IconButton(onClick = onOpenSettings, modifier = Modifier.semantics { contentDescription = settingsLabel }) {
                        Avatar(account.greetingName)
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        // Rejected writes stay with the banner; "can't reach" is the popup's, so the banner skips the sync states.
                        WriteQueueBanner(state.sync, viewModel, syncStates = false)
                        DisconnectedPopup(disconnected, profile.name, onRetry = viewModel::refresh)
                        RefreshBox(state.sync, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                            LoadedContent(loaded = state.dashboard != null, sync = state.sync, onRetry = viewModel::refresh) {
                                val dashboard = state.dashboard ?: return@LoadedContent
                                if (dashboard.vehicles.isEmpty()) {
                                    EmptyState(
                                        modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                                        icon = Icons.Outlined.Garage,
                                        title = stringResource(R.string.dashboard_no_vehicles_title),
                                        body = stringResource(R.string.dashboard_no_vehicles_body),
                                    )
                                } else {
                                    DashboardContent(
                                        dashboard = dashboard,
                                        extras = state.extras,
                                        fmt = state.formatter,
                                        pendingIds = pendingIds,
                                        pagerState = pagerState,
                                        cardActions = cardActions,
                                        actions = actions,
                                        snackbar = snackbar,
                                        onOpenVehicle = onOpenVehicle,
                                        onAddVehicle = onAddVehicle,
                                        onOpenMaintenance = onOpenMaintenance,
                                        onOpenFuel = onOpenFuel,
                                        onLogService = onLogService,
                                        onDeleteFuel = viewModel::deleteFuelEntry,
                                        onSnoozeReminder = viewModel::snoozeOdometerReminder,
                                    )
                                }
                            }
                        }
                    }
                    // The fan FAB (D-A31): for the vehicle in view, only when it can be edited; gone while the pager moves.
                    val fanVehicle = current?.vehicle?.takeIf { it.access.canEdit }
                    FanScrim(visible = fanOpen && fanVehicle != null, onDismiss = { fanOpen = false })
                    if (fanVehicle != null) {
                        // Four petals (D-A32), each with a short visible caption; the TalkBack name stays the full verb.
                        val fanActions = listOf(
                            FanAction(Icons.Outlined.Build, stringResource(R.string.action_log_service), stringResource(R.string.fab_petal_service)) { actions.onLogService(fanVehicle.id) },
                            FanAction(Icons.AutoMirrored.Outlined.ReceiptLong, stringResource(R.string.fab_log_expense), stringResource(R.string.fab_petal_expense)) { actions.onAddExpense(fanVehicle.id) },
                            FanAction(Icons.Outlined.LocalGasStation, stringResource(R.string.action_log_fill_up), stringResource(R.string.fab_petal_fuel)) { actions.onLogFillUp(fanVehicle.id) },
                            FanAction(Icons.Outlined.DocumentScanner, stringResource(R.string.fuel_scan_receipt), stringResource(R.string.fab_petal_scan)) { actions.onScanReceipt(fanVehicle.id) },
                        )
                        FanFab(
                            actions = fanActions,
                            label = stringResource(R.string.fab_quick_log),
                            expanded = fanOpen,
                            onExpandedChange = { fanOpen = it },
                            visible = !pagerState.isScrollInProgress,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(
    dashboard: Dashboard,
    extras: Map<String, VehicleExtras>,
    fmt: Formatter,
    pendingIds: Set<String>,
    pagerState: PagerState,
    cardActions: VehicleCardActions,
    actions: DashboardActions,
    snackbar: SnackbarHostState,
    onOpenVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenFuel: () -> Unit,
    onLogService: (String) -> Unit,
    onDeleteFuel: (String) -> Unit,
    onSnoozeReminder: (String) -> Unit,
) {
    var open by rememberSheetTarget()
    var odometerFor by rememberSaveable { mutableStateOf<String?>(null) }
    val addToCalendar = rememberAddToCalendar(snackbar)
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val vehicles = dashboard.vehicles
    val page = pagerState.currentPage.coerceIn(0, vehicles.lastIndex)

    // Scrolls under the glass bottom bar (D-A40); the last card still clears the bar and the fan.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomBarInset.current + 96.dp)) {
        // D-A43: a small pill at the header's end says there is more to swipe to (and steps there), or, with one car, adds the next.
        GlassGroupHeader(stringResource(R.string.dashboard_vehicles)) {
            if (vehicles.size > 1) {
                VehiclesHeaderPill(
                    stringResource(R.string.dashboard_swipe_more),
                    Icons.Outlined.ChevronRight,
                    onClick = { scope.launch { pagerState.animateScrollToPage((page + 1) % vehicles.size) } },
                )
            } else {
                VehiclesHeaderPill(stringResource(R.string.action_add_vehicle_short), Icons.Outlined.Add, onClick = onAddVehicle)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // One card per page, no peeking: the page is the full width and the card keeps the list margin inside it.
            HorizontalPager(state = pagerState, beyondViewportPageCount = 1, verticalAlignment = Alignment.Top) { index ->
                val summary = vehicles.getOrNull(index) ?: return@HorizontalPager
                VehicleCard(
                    summary = summary,
                    extras = extras[summary.vehicle.id] ?: VehicleExtras(),
                    dashboard = dashboard,
                    fmt = fmt,
                    tiltEnabled = pagerState.settledPage == index && !pagerState.isScrollInProgress,
                    cardActions = cardActions,
                    actions = actions,
                    onOpen = { onOpenVehicle(summary.vehicle.id) },
                    onOpenOdometer = { odometerFor = summary.vehicle.id },
                )
            }
            if (page < vehicles.lastIndex) {
                // Centered on the photo: card margin 16 + frame inset 12 on each side, 16:9 inside.
                val photoHeight = (maxWidth - 56.dp) * 9f / 16f
                NextChevron(
                    onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp + photoHeight / 2 - 24.dp, end = 20.dp),
                )
            }
        }
        PagerDots(vehicles.size, page, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp).heightIn(min = 8.dp))

        // Attention and fill-ups belong to the vehicle in view and slide along with it.
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                if (reduced) {
                    fadeIn() togetherWith fadeOut()
                } else if (targetState > initialState) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
            },
            label = "perVehicle",
        ) { index ->
            val summary = vehicles.getOrNull(index) ?: return@AnimatedContent
            val vehicle = summary.vehicle
            val attention = dashboard.attention.filter { it.vehicle.id == vehicle.id }
            val fuel = dashboard.recentFuelByVehicle[vehicle.id].orEmpty()
            val reminder = extras[vehicle.id]?.reminder?.takeIf { vehicle.access.canEdit }
            Column {
                GlassGroupHeader(stringResource(R.string.dashboard_attention), onAction = onOpenMaintenance)
                if (reminder != null) {
                    OdometerReminderRow(
                        reminder,
                        onUpdate = { odometerFor = vehicle.id },
                        onLater = { onSnoozeReminder(vehicle.id) },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                if (attention.isEmpty()) {
                    AllCaughtUp()
                } else {
                    GlassCard {
                        attention.take(MAX_ATTENTION).forEachIndexed { i, item ->
                            if (i > 0) RowDivider()
                            DueRow(item, fmt, onClick = { open = SheetTarget.Schedule(item.schedule.id) }, showVehicle = false)
                        }
                    }
                }
                if (fuel.isNotEmpty()) {
                    GlassGroupHeader(stringResource(R.string.dashboard_recent_fuel), onAction = onOpenFuel)
                    GlassCard {
                        fuel.forEachIndexed { i, entry ->
                            if (i > 0) RowDivider()
                            FuelRow(
                                entry,
                                vehicle = null,
                                fmt = fmt,
                                onClick = { open = SheetTarget.Fuel(entry.id) },
                                economy = dashboard.fuelSegments[entry.id]?.let { fmt.economy(it.litersPer100Km) },
                                pending = entry.id in pendingIds,
                            )
                        }
                    }
                }
            }
        }
    }

    when (val target = open) {
        is SheetTarget.Schedule -> dashboard.attention.firstOrNull { it.schedule.id == target.id }?.let { item ->
            val event = item.dueAt?.let { due ->
                dueCalendarEvent(item.name, item.vehicle, fmt.localDate(due), intervalText(item.schedule.intervalKm, item.schedule.intervalMonths, fmt), item.schedule.notes)
            }
            ScheduleSheet(
                item,
                fmt,
                onDismiss = { open = null },
                onLogService = {
                    open = null
                    onLogService(item.schedule.id)
                },
                onAddToCalendar = event?.let { e -> { addToCalendar(e) } },
            )
        }
        is SheetTarget.Fuel -> dashboard.recentFuelByVehicle.values.flatten().firstOrNull { it.id == target.id }?.let { entry ->
            val vehicle = vehicles.firstOrNull { it.vehicle.id == entry.vehicleId }?.vehicle
            FuelSheet(
                entry,
                vehicle,
                dashboard.fuelSegments[entry.id],
                fmt,
                onDismiss = { open = null },
                pending = entry.id in pendingIds,
                onDelete = {
                    open = null
                    onDeleteFuel(entry.id)
                },
            )
        }
        else -> Unit
    }

    odometerFor?.let { vehicleId ->
        OdometerSheet(vehicleId = vehicleId, onDismiss = { odometerFor = null }, snackbar = snackbar)
    }
}

/** "Garage · updated 5 min ago" or "Garage · syncing…"; a failed sync is the popup's job, so it isn't repeated here. */
@Composable
private fun syncLine(profileName: String, sync: SyncInfo): String {
    val status = when {
        sync.status.running -> stringResource(R.string.sync_running)
        sync.lastSyncedAt != null -> stringResource(R.string.sync_updated, relativeTime(sync.lastSyncedAt, abbreviated = true))
        else -> null
    }
    return listOfNotNull(profileName, status).joinToString(" · ")
}

private fun greetingFor(time: LocalTime): Int = when (time.hour) {
    in 5..11 -> R.string.dashboard_greeting_morning
    in 12..17 -> R.string.dashboard_greeting_afternoon
    else -> R.string.dashboard_greeting_evening
}

private const val MAX_ATTENTION = 5

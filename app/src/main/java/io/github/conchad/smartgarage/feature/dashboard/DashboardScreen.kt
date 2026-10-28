package io.github.conchad.smartgarage.feature.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
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
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import io.github.conchad.smartgarage.ui.glass.GlassMenu
import io.github.conchad.smartgarage.ui.glass.GlassMenuItem
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
import io.github.conchad.smartgarage.ui.glass.NoSheen
import io.github.conchad.smartgarage.ui.glass.AuroraInkGroup
import io.github.conchad.smartgarage.ui.glass.LocalAuroraInkGroup
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
import io.github.conchad.smartgarage.ui.glass.topFadeEdge

/**
 * What the home card's Add / Update buttons and the fan FAB start (D-A31); the shell wires each to the existing
 * form route for the vehicle in the pager.
 */
class DashboardActions(
    val onLogFillUp: (vehicleId: String) -> Unit,
    val onLogService: (vehicleId: String) -> Unit,
    val onAddExpense: (vehicleId: String) -> Unit,
    /**
     * The fan's "Scan Receipt" petal (D-A32): the fill-up form with the receipt scan started on arrival, from the
     * camera or, with [gallery] (D-A61), from an existing photo through the Photo Picker.
     */
    val onScanReceipt: (vehicleId: String, gallery: Boolean) -> Unit,
    /** "Scan text" under the same petal (D-A64): the standalone on-device text scanner. */
    val onScanText: () -> Unit,
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
    // The vehicle whose "Theme color" sheet is open (D-A57), from the fan.
    var themeFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The vehicle whose "Scan receipt" source menu is open (D-A61), from the fan.
    var scanFor by rememberSaveable { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalTilt provides tilt) {
        MetallicSheenHost {
            AppScaffold(
                transparent = true,
                title = {
                    // D-A70: the greeting and the sync line take one ink between them.
                    val headerInk = remember { AuroraInkGroup() }
                    Column {
                        CompositionLocalProvider(LocalAuroraInkGroup provides headerInk) {
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
                // D-A70: only the greeting above keeps the metallic sheen; nothing below it sweeps.
                NoSheen { Box(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
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
                    // The fan FAB (D-A31): for the vehicle in view; gone while the pager moves. D-A57: on a view-only shared
                    // vehicle it still opens with just "Theme color" (a phone-only preference), the logging petals need edit rights.
                    val fanVehicle = current?.vehicle
                    FanScrim(visible = fanOpen && fanVehicle != null, onDismiss = { fanOpen = false })
                    if (fanVehicle != null) {
                        // Five petals (D-A32, D-A57), each with a short visible caption; the TalkBack name stays the full verb.
                        // "Theme color" is first, so it sits at the bottom of the arc.
                        val themePetal = FanAction(Icons.Outlined.Palette, stringResource(R.string.fab_theme_color), stringResource(R.string.fab_petal_theme)) { themeFor = fanVehicle.id }
                        val fanActions = if (!fanVehicle.access.canEdit) listOf(themePetal) else listOf(
                            themePetal,
                            FanAction(Icons.Outlined.Build, stringResource(R.string.action_log_service), stringResource(R.string.fab_petal_service)) { actions.onLogService(fanVehicle.id) },
                            FanAction(Icons.AutoMirrored.Outlined.ReceiptLong, stringResource(R.string.fab_log_expense), stringResource(R.string.fab_petal_expense)) { actions.onAddExpense(fanVehicle.id) },
                            FanAction(Icons.Outlined.LocalGasStation, stringResource(R.string.action_log_fill_up), stringResource(R.string.fab_petal_fuel)) { actions.onLogFillUp(fanVehicle.id) },
                            FanAction(Icons.Outlined.DocumentScanner, stringResource(R.string.fuel_scan_receipt), stringResource(R.string.fab_petal_scan)) { scanFor = fanVehicle.id },
                        )
                        FanFab(
                            actions = fanActions,
                            label = stringResource(if (fanVehicle.access.canEdit) R.string.fab_quick_log else R.string.fab_view_only_label),
                            expanded = fanOpen,
                            onExpandedChange = { fanOpen = it },
                            visible = !pagerState.isScrollInProgress,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                        )
                    }
                    // The fan's "Scan Receipt" petal (D-A61): a glass menu picks the source, camera or gallery; both go on
                    // to the fill-up form's scan, which downscales and uploads either photo the same way (D-A56).
                    GlassMenu(expanded = scanFor != null, onDismiss = { scanFor = null }, title = stringResource(R.string.fuel_scan_receipt)) {
                        GlassMenuItem(Icons.Outlined.PhotoCamera, stringResource(R.string.file_take_photo)) {
                            scanFor?.let { actions.onScanReceipt(it, false) }
                            scanFor = null
                        }
                        GlassMenuItem(Icons.Outlined.PhotoLibrary, stringResource(R.string.receipt_scan_pick_gallery)) {
                            scanFor?.let { actions.onScanReceipt(it, true) }
                            scanFor = null
                        }
                        // D-A64: any text on a photo, read on this phone, to copy or share; camera or gallery is picked there.
                        GlassMenuItem(Icons.Outlined.TextFields, stringResource(R.string.fab_scan_text)) {
                            scanFor = null
                            actions.onScanText()
                        }
                    }
                    // The fan's "Theme color" sheet (D-A57): the vehicle's aurora override, applied live.
                    val themeVehicle = themeFor?.let { id -> vehicles.firstOrNull { it.vehicle.id == id }?.vehicle }
                    if (themeVehicle != null) {
                        ThemeColorSheet(
                            vehicleId = themeVehicle.id,
                            vehicleName = cardActions.nicknameOf(themeVehicle) ?: themeVehicle.displayName,
                            onSave = { viewModel.setAuroraOverride(themeVehicle.id, it) },
                            onDismiss = { themeFor = null },
                        )
                    }
                } }
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
    // D-A77: one Quick info drawer at a time, held here so it closes once another vehicle settles in view.
    // D-A78: by then the swipe has already folded it (quickInfoReveal), so clearing it here shows no second animation.
    var quickInfoFor by rememberSaveable { mutableStateOf<String?>(null) }
    val addToCalendar = rememberAddToCalendar(snackbar)
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val vehicles = dashboard.vehicles
    val page = pagerState.currentPage.coerceIn(0, vehicles.lastIndex)
    val currentVehicles by rememberUpdatedState(vehicles)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { settled ->
            quickInfoFor = quickInfoAfterSettle(quickInfoFor, currentVehicles.getOrNull(settled)?.vehicle?.id)
        }
    }

    // Scrolls under the glass bottom bar (D-A40); the last card still clears the bar and the fan. D-A76: fades out at the top.
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().topFadeEdge(scroll, glass.contentFadeLift).verticalScroll(scroll).padding(top = glass.contentFadeLift, bottom = LocalBottomBarInset.current + 96.dp - SHADOW_ROOM)) {
        // D-A43: a small pill at the header's end says there is more to swipe to (and steps there), or, with one car, adds the next.
        // D-A54: on a glass bar, so the title reads as plain text (no aurora ink, no halo).
        GlassGroupHeader(stringResource(R.string.dashboard_vehicles), onGlassBar = true) {
            if (vehicles.size > 1) {
                // D-A83: on the last vehicle there is nothing more to swipe to, so the hint becomes "Add vehicle" (and
                // back once the user swipes off it). D-A86: one glass bubble that reshapes between the two labels.
                val atEnd = page == vehicles.lastIndex
                VehiclesHeaderMorphPill(
                    atEnd = atEnd,
                    swipeLabel = stringResource(R.string.dashboard_swipe_more),
                    addLabel = stringResource(R.string.dashboard_swipe_add_vehicle),
                    reduced = reduced,
                    onClick = {
                        if (atEnd) onAddVehicle()
                        else scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(vehicles.lastIndex)) }
                    },
                )
            } else {
                VehiclesHeaderPill(stringResource(R.string.action_add_vehicle_short), Icons.Outlined.Add, onClick = onAddVehicle, emphasized = true)
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
                    expanded = quickInfoFor == summary.vehicle.id,
                    onToggleExpanded = { quickInfoFor = if (quickInfoFor == summary.vehicle.id) null else summary.vehicle.id },
                    // D-A78: lambda, so the pager offset is read in the card's layout/draw phase, not here every frame.
                    quickInfoReveal = { quickInfoReveal(pagerState.currentPage - index + pagerState.currentPageOffsetFraction, reduced) },
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
                val transform = if (reduced) {
                    fadeIn() togetherWith fadeOut()
                } else if (targetState > initialState) {
                    (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                }
                transform using SizeTransform(clip = false)
            },
            // D-A55: no clip to the switcher's bounds, which cut the last card's soft shadow off in a straight line
            // (hard corners under it, during and after the slide); the content carries [SHADOW_ROOM] below itself so
            // the fade's offscreen layer (sized to the content) holds the whole shadow too.
            contentAlignment = Alignment.TopCenter,
            label = "perVehicle",
        ) { index ->
            val summary = vehicles.getOrNull(index) ?: return@AnimatedContent
            val vehicle = summary.vehicle
            val attention = dashboard.attention.filter { it.vehicle.id == vehicle.id }
            val fuel = dashboard.recentFuelByVehicle[vehicle.id].orEmpty()
            val reminder = extras[vehicle.id]?.reminder?.takeIf { vehicle.access.canEdit }
            Column(Modifier.padding(bottom = SHADOW_ROOM)) {
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

/**
 * Room below the per-vehicle cards for their soft shadow (D-A55): the glass shadow reaches about 15 dp below a card
 * (10 dp blur, 5 dp drop), so the switcher's content, and the layer its fade draws into, end below it, not on it.
 */
private val SHADOW_ROOM = 16.dp

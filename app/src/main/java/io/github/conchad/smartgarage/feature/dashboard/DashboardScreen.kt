package io.github.conchad.smartgarage.feature.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Garage
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import io.github.conchad.smartgarage.core.model.OdometerReminder
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.feature.vehicles.OdometerSheet
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardActions
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardActionsViewModel
import io.github.conchad.smartgarage.feature.vehicles.VehicleCardMenu
import io.github.conchad.smartgarage.feature.vehicles.rememberVehicleCardActions
import io.github.conchad.smartgarage.feature.vehicles.vehicleCardSemantics
import io.github.conchad.smartgarage.ui.components.AppScaffold
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
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.components.WriteQueueBanner
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.relativeTime
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
import io.github.conchad.smartgarage.ui.glass.AuroraTint
import io.github.conchad.smartgarage.ui.glass.DisconnectedPopup
import io.github.conchad.smartgarage.ui.glass.GlassCard
import io.github.conchad.smartgarage.ui.glass.GlassGroupHeader
import io.github.conchad.smartgarage.ui.glass.MetallicFrame
import io.github.conchad.smartgarage.ui.glass.MetallicSheenHost
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.PagerDots
import io.github.conchad.smartgarage.ui.glass.RefreshIcon
import io.github.conchad.smartgarage.ui.glass.VehicleTint
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.rememberDisconnectedState
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import io.github.conchad.smartgarage.ui.glass.rememberTilt
import io.github.conchad.smartgarage.ui.links.InsuranceApps
import io.github.conchad.smartgarage.ui.sync.SyncInfo
import io.github.conchad.smartgarage.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * The home page (D-A24): the shell's aurora takes the current vehicle's tint; one full-width holographic card per
 * vehicle in a pager, with a dropdown for insurance, mileage and inspection; "Needs attention" and "Recent
 * fill-ups" follow the pager; the monthly odometer reminder sits at the top of attention; and the "can't reach"
 * popup collapses into a refresh icon in the top bar.
 */
@Composable
fun DashboardScreen(
    profile: ServerProfile,
    account: Account,
    onOpenSettings: () -> Unit,
    onOpenVehicle: (String) -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenFuel: () -> Unit,
    onLogService: (scheduleId: String) -> Unit,
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
    AuroraTint(VehicleTint.color(current?.vehicle?.color, MaterialTheme.colorScheme.primary))
    val disconnected = rememberDisconnectedState(state.sync)

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
            Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                // Rejected writes stay with the banner; "can't reach" is the popup's, so the banner skips the sync states.
                WriteQueueBanner(state.sync, viewModel, syncStates = false)
                DisconnectedPopup(disconnected, profile.name, onRetry = viewModel::refresh)
                RefreshBox(state.sync, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                    LoadedContent(loaded = state.dashboard != null, sync = state.sync, onRetry = viewModel::refresh) {
                        val dashboard = state.dashboard ?: return@LoadedContent
                        if (dashboard.vehicles.isEmpty()) {
                            EmptyState(
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
                                snackbar = snackbar,
                                onOpenVehicle = onOpenVehicle,
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
    snackbar: SnackbarHostState,
    onOpenVehicle: (String) -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenFuel: () -> Unit,
    onLogService: (String) -> Unit,
    onDeleteFuel: (String) -> Unit,
    onSnoozeReminder: (String) -> Unit,
) {
    var open by rememberSheetTarget()
    var odometerFor by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    val vehicles = dashboard.vehicles
    val page = pagerState.currentPage.coerceIn(0, vehicles.lastIndex)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        GlassGroupHeader(stringResource(R.string.dashboard_vehicles))
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
            ScheduleSheet(
                item,
                fmt,
                onDismiss = { open = null },
                onLogService = {
                    open = null
                    onLogService(item.schedule.id)
                },
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

/**
 * One vehicle: the tilting chrome frame around the photo (name over a scrim inside it), plate · mileage and the
 * status chip, then a chevron that drops down insurance, mileage and inspection. Long-press on the photo opens the
 * photo / rename menu; TalkBack gets the same as custom actions.
 */
@Composable
private fun VehicleCard(
    summary: VehicleSummary,
    extras: VehicleExtras,
    dashboard: Dashboard,
    fmt: Formatter,
    tiltEnabled: Boolean,
    cardActions: VehicleCardActions,
    onOpen: () -> Unit,
    onOpenOdometer: () -> Unit,
) {
    val vehicle = summary.vehicle
    val tokens = glass
    val tilt = rememberTilt(enabled = tiltEnabled)
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val nickname = cardActions.nicknameOf(vehicle)
    val title = cardActions.titleOf(vehicle)
    val expandLabel = stringResource(if (expanded) R.string.card_details_hide else R.string.card_details_show)

    GlassCard(modifier = Modifier.animateContentSize()) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            MetallicFrame(
                tilt = tilt,
                cornerRadius = tokens.radiusMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .vehicleCardSemantics(vehicle, cardActions, onOpen),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
                ) {
                    VehiclePhoto(vehicle, Modifier.fillMaxSize(), iconSize = 64.dp)
                    // Name over a bottom scrim so it reads on any photo; white on ≥ 55 % black clears 4.5:1.
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xD9000000))))
                            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFFF4F6FB),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (nickname != null) {
                            Text(
                                vehicle.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC7CCDB),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                VehicleCardMenu(vehicle, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = cardActions, onOpen = onOpen)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetallicText(
                    listOfNotNull(vehicle.licensePlate, fmt.distance(vehicle.currentOdometerKm)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                VehicleStatusChip(summary)
            }
            val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = expandLabel,
                    tint = tokens.sheen,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
        }
        if (expanded) {
            RowDivider()
            CardDetails(summary, extras, dashboard, fmt, onOpenOdometer)
        }
    }
}

/** The dropdown: insurance plan (opens the insurer's app when installed), mileage (opens the odometer sheet), inspection due. */
@Composable
private fun CardDetails(summary: VehicleSummary, extras: VehicleExtras, dashboard: Dashboard, fmt: Formatter, onOpenOdometer: () -> Unit) {
    val vehicle = summary.vehicle
    val context = LocalContext.current
    val policy = dashboard.policyByVehicle[vehicle.id]
    val inspection = dashboard.inspectionByVehicle[vehicle.id]
    val insurerIntent = remember(policy?.provider) { InsuranceApps.launchIntent(context, policy?.provider) }

    Column(Modifier.padding(bottom = 8.dp)) {
        DetailLine(
            icon = Icons.Outlined.Shield,
            label = stringResource(R.string.dashboard_insurance),
            value = policy?.provider ?: stringResource(R.string.card_no_insurance),
            detail = policy?.endDate?.toInstantOrNull()?.let { stringResource(R.string.dashboard_insurance_until, fmt.date(it)) },
            trailing = if (insurerIntent != null) Icons.Outlined.OpenInNew else null,
            actionLabel = policy?.let { stringResource(R.string.card_insurance_open_app, it.provider) }.takeIf { insurerIntent != null },
            onClick = if (insurerIntent != null) ({ InsuranceApps.open(context, policy?.provider) }) else null,
        )
        RowDivider()
        DetailLine(
            icon = Icons.Outlined.Speed,
            label = stringResource(R.string.card_mileage),
            value = fmt.distance(vehicle.currentOdometerKm),
            detail = extras.lastOdometerAt?.let { stringResource(R.string.odometer_updated, relativeTime(it)) },
            trailing = null,
            actionLabel = stringResource(R.string.action_update_odometer).takeIf { vehicle.access.canEdit },
            onClick = onOpenOdometer.takeIf { vehicle.access.canEdit },
        )
        RowDivider()
        val due = inspection?.nextDueAt?.toInstantOrNull()
        DetailLine(
            icon = Icons.Outlined.EventAvailable,
            label = inspection?.let { inspectionLabel(it.type) } ?: stringResource(R.string.inspection_hu),
            value = due?.let { fmt.date(it) } ?: stringResource(R.string.card_no_inspection),
            detail = due?.let { dueDays(fmt.daysFromToday(it).toInt()) },
            trailing = null,
            actionLabel = null,
            onClick = null,
        )
    }
}

/** Icon, label, value and a detail line; one TalkBack item, with the tap action named when there is one. */
@Composable
private fun DetailLine(
    icon: ImageVector,
    label: String,
    value: String,
    detail: String?,
    trailing: ImageVector?,
    actionLabel: String?,
    onClick: (() -> Unit)?,
) {
    val tokens = glass
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { if (actionLabel != null) contentDescription = "$label, $value. $actionLabel" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tokens.textSecondary)
            MetallicText(value, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Icon(trailing, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(18.dp))
    }
}

/** The arrow on the right edge of the pager: there is another vehicle; tap to go to it. Always over a photo, so a fixed dark pill. */
@Composable
private fun NextChevron(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = stringResource(R.string.pager_next), tint = Color(0xFFF4F6FB))
        }
    }
}

/** "Odometer last updated 6 weeks ago" with Update and Later; shown at the top of Needs attention (D-A24). */
@Composable
private fun OdometerReminderRow(reminder: OdometerReminder.Reminder, onUpdate: () -> Unit, onLater: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    val days = reminder.daysSince.toInt()
    val ago = if (days >= 14) pluralStringResource(R.plurals.odometer_reminder_weeks_ago, days / 7, days / 7) else pluralStringResource(R.plurals.due_days_ago, days, days)
    GlassCard(modifier) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Speed, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                MetallicText(stringResource(R.string.odometer_reminder_title, ago), style = MaterialTheme.typography.titleSmall, animated = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.odometer_reminder_body), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onLater, colors = ButtonDefaults.textButtonColors(contentColor = tokens.textSecondary)) { Text(stringResource(R.string.action_later)) }
            TextButton(onClick = onUpdate, colors = ButtonDefaults.textButtonColors(contentColor = tokens.sheen)) { Text(stringResource(R.string.action_update)) }
        }
    }
}

@Composable
private fun AllCaughtUp() {
    val colors = LocalStatusColors.current
    GlassCard(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.ok)
            Column(Modifier.padding(start = 16.dp)) {
                MetallicText(stringResource(R.string.dashboard_all_caught_up), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.dashboard_all_caught_up_body), style = MaterialTheme.typography.bodySmall, color = glass.textSecondary)
            }
        }
    }
}

/** "Garage · updated 5 min ago" or "Garage · syncing…"; a failed sync is the popup's job, so it isn't repeated here. */
@Composable
private fun syncLine(profileName: String, sync: SyncInfo): String {
    val status = when {
        sync.status.running -> stringResource(R.string.sync_running)
        sync.lastSyncedAt != null -> stringResource(R.string.sync_updated, relativeTime(sync.lastSyncedAt))
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

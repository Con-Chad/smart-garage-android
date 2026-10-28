package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Inspection
import io.github.conchad.smartgarage.core.model.InsurancePolicy
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.feature.dashboard.DashboardViewModel
import io.github.conchad.smartgarage.feature.fuel.FAB_CLEARANCE
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.SyncBanner
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.glass.AuroraTint
import io.github.conchad.smartgarage.ui.glass.GlassEmphasisPill
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.LocalTilt
import io.github.conchad.smartgarage.ui.glass.MetallicSheenHost
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.TiltDepths
import io.github.conchad.smartgarage.ui.glass.TiltPhotoFrame
import io.github.conchad.smartgarage.ui.glass.VehicleTint
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.rememberTilt
import io.github.conchad.smartgarage.ui.glass.tiltLayer
import java.time.Instant
import androidx.compose.foundation.lazy.rememberLazyListState
import io.github.conchad.smartgarage.ui.glass.topFadeEdge

/**
 * The vehicles list on glass (D-A24, notes item 3): each card is a frosted panel with the chrome frame around the
 * photo, the same long-press menu and nickname as the home card, and since D-A32 the home card's "Quick info"
 * drop-down (insurance, mileage, inspection with Add / Update). The cards arrive with the tab's fade (D-A51). Add pill (D-A55),
 * sync banner and the shared-by line stay as they were.
 *
 * The drop-down's per-vehicle policy, inspection and last odometer reading come from the same [DashboardViewModel]
 * the home page uses (scoped to this tab's back-stack entry), so the two cards can never disagree.
 */
@Composable
fun VehiclesScreen(
    onOpenVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    quickInfoActions: QuickInfoActions,
    viewModel: VehiclesViewModel = hiltViewModel(),
    cardActionsViewModel: VehicleCardActionsViewModel = hiltViewModel(),
    dashboardViewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val dashboard by dashboardViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val cardActions = rememberVehicleCardActions(cardActionsViewModel, snackbar)
    AuroraTint(state.vehicles.firstOrNull()?.vehicle?.id, state.vehicles.firstOrNull()?.vehicle?.color)
    val tilt = rememberTilt()
    var odometerFor by rememberSaveable { mutableStateOf<String?>(null) }

    CompositionLocalProvider(LocalTilt provides tilt) {
        MetallicSheenHost {
            AppScaffold(
                title = { MetallicText(stringResource(R.string.nav_vehicles), style = MaterialTheme.typography.titleLarge, animated = true) },
                transparent = true,
                // D-A55: the app's glass call-to-action pill (as Home's "Add vehicle"), not a Material extended FAB.
                floatingActionButton = {
                    if (state.loaded) GlassEmphasisPill(stringResource(R.string.action_add_vehicle), onClick = onAddVehicle, icon = Icons.Outlined.Add, floating = true)
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                    SyncBanner(sync, onRetry = viewModel::refresh)
                    RefreshBox(sync, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                        LoadedContent(loaded = state.loaded, sync = sync, onRetry = viewModel::refresh) {
                            if (state.vehicles.isEmpty()) {
                                EmptyState(
                                    modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                                    icon = Icons.Outlined.DirectionsCar,
                                    title = stringResource(R.string.dashboard_no_vehicles_title),
                                    body = stringResource(R.string.vehicles_empty_body),
                                )
                            } else {
                                // D-A51: no bubble on arrival (D-A32's replayed on every tab switch); the tab's fade-through
                                // brings the cards in with the page.
                                val listState = rememberLazyListState()
                                LazyColumn(
                                    state = listState,
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp + glass.contentFadeLift, bottom = padding.calculateBottomPadding() + FAB_CLEARANCE),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize().topFadeEdge(listState, glass.contentFadeLift),
                                ) {
                                    items(state.vehicles, key = { it.vehicle.id }) { summary ->
                                        val id = summary.vehicle.id
                                        VehicleListCard(
                                            summary = summary,
                                            policy = dashboard.dashboard?.policyByVehicle?.get(id),
                                            inspection = dashboard.dashboard?.inspectionByVehicle?.get(id),
                                            lastOdometerAt = dashboard.extras[id]?.lastOdometerAt,
                                            fmt = state.formatter,
                                            cardActions = cardActions,
                                            quickInfoActions = quickInfoActions,
                                            onClick = { onOpenVehicle(id) },
                                            onOpenOdometer = { odometerFor = id },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    odometerFor?.let { vehicleId ->
        OdometerSheet(vehicleId = vehicleId, onDismiss = { odometerFor = null }, snackbar = snackbar)
    }
}

/**
 * One vehicle in the list on a glass card (D-A30, D-A31, D-A32): only the framed photo tilts, with the photo behind
 * the frame and the name above it; the card stays flat. Under the photo the plate · mileage line and the status
 * chip, then the "Quick info" toggle that drops the shared details down (`AnimatedVisibility` expand / shrink, D-A34; not the pop-in).
 */
@Composable
private fun VehicleListCard(
    summary: VehicleSummary,
    policy: InsurancePolicy?,
    inspection: Inspection?,
    lastOdometerAt: Instant?,
    fmt: Formatter,
    cardActions: VehicleCardActions,
    quickInfoActions: QuickInfoActions,
    onClick: () -> Unit,
    onOpenOdometer: () -> Unit,
) {
    val vehicle = summary.vehicle
    val tokens = glass
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val nickname = cardActions.nicknameOf(vehicle)
    GlassPanel(
        Modifier.fillMaxWidth(),
        glow = VehicleTint.color(vehicle.color, MaterialTheme.colorScheme.primary),
        onClick = onClick,
    ) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            TiltPhotoFrame(
                cornerRadius = tokens.radiusMedium,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f).vehicleCardSemantics(vehicle, cardActions, onClick),
            ) {
                Box(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })) {
                    VehiclePhoto(vehicle, Modifier.fillMaxSize().tiltLayer(TiltDepths.Photo), iconSize = 64.dp)
                    VehiclePhotoTitle(cardActions.titleOf(vehicle), if (nickname != null) vehicle.displayName else null, titleMaxLines = 2, Modifier.align(Alignment.BottomStart), plate = vehicle.licensePlate)
                }
                VehicleCardMenu(vehicle, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = cardActions, onOpen = onClick)
            }
        }
        // D-A52: the plate is a badge on the photo ([VehiclePhotoTitle]); only "Shared by" stays under it.
        if (vehicle.access.isShared) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SharedBy(vehicle.owner?.name)
            }
        }
        // The mileage over the status chip, the pills beside them, as on the home card (D-A41, user-set).
        VehicleCardFooter(
            expanded = expanded,
            onToggle = { expanded = !expanded },
            onViewAll = onClick,
            modifier = Modifier.padding(top = if (vehicle.access.isShared) 8.dp else 16.dp),
        ) {
            MetallicText(fmt.distance(vehicle.currentOdometerKm), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            VehicleStatusChip(summary)
        }
        // The drawer slides open and closed under the footer (D-A34); the card's height follows it frame by frame.
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                RowDivider()
                QuickInfoDetails(
                    vehicle = vehicle,
                    policy = policy,
                    inspection = inspection,
                    lastOdometerAt = lastOdometerAt,
                    fmt = fmt,
                    actions = quickInfoActions,
                    onOpenOdometer = onOpenOdometer,
                )
            }
        }
    }
}

@Composable
fun SharedBy(owner: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.People,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            if (owner != null) stringResource(R.string.vehicle_shared_by, owner) else stringResource(R.string.vehicle_shared),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

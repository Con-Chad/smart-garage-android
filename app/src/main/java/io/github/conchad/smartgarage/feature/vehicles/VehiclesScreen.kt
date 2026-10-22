package io.github.conchad.smartgarage.feature.vehicles

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.feature.fuel.FAB_CLEARANCE
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.SyncBanner
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.glass.AuroraTint
import io.github.conchad.smartgarage.ui.glass.LocalTilt
import io.github.conchad.smartgarage.ui.glass.MetallicFrame
import io.github.conchad.smartgarage.ui.glass.MetallicSheenHost
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.VehicleTint
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.TiltDepths
import io.github.conchad.smartgarage.ui.glass.TiltParallaxCard
import io.github.conchad.smartgarage.ui.glass.rememberTilt
import io.github.conchad.smartgarage.ui.glass.tiltLayer

/**
 * The vehicles list on glass (D-A24, notes item 3): each card is a frosted panel with the chrome frame around the
 * photo (static sheen, no tilt in a list), the same long-press menu and nickname as the home card. Add FAB, sync
 * banner and the shared-by line stay as they were.
 */
@Composable
fun VehiclesScreen(
    onOpenVehicle: (String) -> Unit,
    onAddVehicle: () -> Unit,
    viewModel: VehiclesViewModel = hiltViewModel(),
    cardActionsViewModel: VehicleCardActionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val cardActions = rememberVehicleCardActions(cardActionsViewModel, snackbar)
    AuroraTint(VehicleTint.color(state.vehicles.firstOrNull()?.vehicle?.color, MaterialTheme.colorScheme.primary))
    val tilt = rememberTilt()

    CompositionLocalProvider(LocalTilt provides tilt) {
        MetallicSheenHost {
            AppScaffold(
                title = { MetallicText(stringResource(R.string.nav_vehicles), style = MaterialTheme.typography.titleLarge, animated = true) },
                transparent = true,
                floatingActionButton = { if (state.loaded) AddFab(stringResource(R.string.action_add_vehicle), onClick = onAddVehicle) },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                    SyncBanner(sync, onRetry = viewModel::refresh)
                    RefreshBox(sync, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                        LoadedContent(loaded = state.loaded, sync = sync, onRetry = viewModel::refresh) {
                            if (state.vehicles.isEmpty()) {
                                EmptyState(
                                    icon = Icons.Outlined.DirectionsCar,
                                    title = stringResource(R.string.dashboard_no_vehicles_title),
                                    body = stringResource(R.string.vehicles_empty_body),
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = FAB_CLEARANCE),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize(),
                                ) {
                                    items(state.vehicles, key = { it.vehicle.id }) { summary ->
                                        VehicleListCard(summary, state.formatter, cardActions, onClick = { onOpenVehicle(summary.vehicle.id) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One vehicle in the list on a tilt-parallax card (D-A30), with the home card's layers: photo behind the plate, name and server name above it. */
@Composable
private fun VehicleListCard(summary: VehicleSummary, fmt: Formatter, cardActions: VehicleCardActions, onClick: () -> Unit) {
    val vehicle = summary.vehicle
    val tokens = glass
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val nickname = cardActions.nicknameOf(vehicle)
    TiltParallaxCard(Modifier.fillMaxWidth(), glow = VehicleTint.color(vehicle.color, MaterialTheme.colorScheme.primary), onClick = onClick) {
        Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
            MetallicFrame(
                cornerRadius = tokens.radiusMedium,
                modifier = Modifier.fillMaxWidth().aspectRatio(2f).vehicleCardSemantics(vehicle, cardActions, onClick),
            ) {
                Box(Modifier.fillMaxSize().combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })) {
                    VehiclePhoto(vehicle, Modifier.fillMaxSize().tiltLayer(TiltDepths.Photo), iconSize = 64.dp)
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(tokens.photoScrim))
                            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
                    ) {
                        Text(
                            cardActions.titleOf(vehicle),
                            style = MaterialTheme.typography.titleLarge,
                            color = tokens.onPhoto,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.tiltLayer(TiltDepths.Title),
                        )
                        if (nickname != null) {
                            Text(
                                vehicle.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.onPhotoSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.tiltLayer(TiltDepths.Subtitle),
                            )
                        }
                    }
                }
                VehicleCardMenu(vehicle, expanded = menuOpen, onDismiss = { menuOpen = false }, actions = cardActions, onOpen = onClick)
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MetallicText(
                listOfNotNull(vehicle.licensePlate, fmt.distance(vehicle.currentOdometerKm)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.tiltLayer(TiltDepths.Line),
            )
            if (vehicle.access.isShared) SharedBy(vehicle.owner?.name)
            Row(Modifier.padding(top = 8.dp)) { VehicleStatusChip(summary, Modifier.tiltLayer(TiltDepths.Chip)) }
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

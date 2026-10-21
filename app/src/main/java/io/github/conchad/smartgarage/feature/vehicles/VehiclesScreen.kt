package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.People
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip

@Composable
fun VehiclesScreen(onOpenVehicle: (String) -> Unit, viewModel: VehiclesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppScaffold(title = stringResource(R.string.nav_vehicles)) { padding ->
        if (state.loaded && state.vehicles.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.DirectionsCar,
                title = stringResource(R.string.dashboard_no_vehicles_title),
                body = stringResource(R.string.dashboard_no_vehicles_body),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.vehicles, key = { it.vehicle.id }) { summary ->
                    VehicleListCard(summary, state.formatter, onClick = { onOpenVehicle(summary.vehicle.id) })
                }
            }
        }
    }
}

@Composable
private fun VehicleListCard(summary: VehicleSummary, fmt: Formatter, onClick: () -> Unit) {
    val vehicle = summary.vehicle
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        VehiclePhoto(vehicle, Modifier.fillMaxWidth().aspectRatio(2f), iconSize = 64.dp)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(vehicle.displayName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(vehicle.licensePlate, fmt.distance(vehicle.currentOdometerKm)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (vehicle.access.isShared) SharedBy(vehicle.owner?.name)
            Row(Modifier.padding(top = 8.dp)) { VehicleStatusChip(summary) }
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

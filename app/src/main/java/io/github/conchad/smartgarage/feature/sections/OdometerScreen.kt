package io.github.conchad.smartgarage.feature.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.ChartPoint
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.charts.ChartPlaceholder
import io.github.conchad.smartgarage.feature.fuel.ValueChart
import io.github.conchad.smartgarage.ui.components.shortName

@Composable
internal fun OdometerScreen(onBack: () -> Unit, viewModel: OdometerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    SectionScaffold(
        title = stringResource(R.string.nav_odometer),
        onBack = onBack,
        viewModel = viewModel,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.logs.isEmpty(),
        emptyIcon = Icons.Outlined.Speed,
        emptyTitle = stringResource(R.string.odometer_empty_title),
    ) {
        item(key = "chart") { OdometerChart(state.data.series, state.vehicles.size, fmt) }
        byMonth(state.data.logs, fmt.zone) { it.recordedAt.toInstantOrNull() }.forEach { (month, logs) ->
            item(key = month.toString(), contentType = "header") { GroupHeader(monthLabel(month)) }
            item(key = "$month-list", contentType = "group") {
                GroupCard {
                    logs.forEachIndexed { index, log ->
                        if (index > 0) RowDivider()
                        SectionRow(
                            icon = Icons.Outlined.Speed,
                            title = fmt.distance(log.odometerKm),
                            detail = listOfNotNull(
                                log.recordedAt.toInstantOrNull()?.let(fmt::shortDate),
                                state.vehicleById[log.vehicleId]?.shortName?.takeIf { showVehicle },
                                log.note,
                            ).joinToString(" · "),
                            onClick = null,
                            trailing = state.data.deltas[log.id]?.let { delta ->
                                if (delta >= 0) "+${fmt.distance(delta)}" else "−${fmt.distance(-delta)}"
                            },
                            pending = log.id in pendingIds,
                        )
                    }
                }
            }
        }
    }
}

/** The web's "Odometer over time" (D-A23): the one vehicle's readings in the display unit; a line needs two of them. */
@Composable
private fun OdometerChart(series: List<ChartPoint>?, vehicleCount: Int, fmt: Formatter) {
    val title = stringResource(R.string.odometer_chart_readings)
    Column {
        GroupHeader(title)
        GroupCard {
            when {
                series == null -> ChartPlaceholder(stringResource(if (vehicleCount > 1) R.string.chart_pick_vehicle else R.string.chart_needs_readings))
                series.size < 2 -> ChartPlaceholder(stringResource(R.string.chart_needs_readings))
                else -> {
                    val points = series.map { it.copy(value = fmt.distanceValue(it.value)) }
                    ValueChart(points, fmt, title, format = fmt::displayDistance)
                }
            }
        }
    }
}

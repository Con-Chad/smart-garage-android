package io.github.conchad.smartgarage.feature.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.ChartPoint
import io.github.conchad.smartgarage.feature.vehicles.StatStrip
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.FuelRow
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.glass.MetallicSheenHost
import io.github.conchad.smartgarage.ui.components.LoadedContent
import io.github.conchad.smartgarage.ui.components.RefreshBox
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.VehicleFilterRow
import io.github.conchad.smartgarage.ui.components.WriteQueueBanner
import io.github.conchad.smartgarage.ui.components.charts.ChartPlaceholder
import io.github.conchad.smartgarage.ui.components.charts.ChartTitle
import io.github.conchad.smartgarage.ui.components.charts.LineChart
import io.github.conchad.smartgarage.ui.components.charts.lineSummary
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FuelScreen(onLogFillUp: (vehicleId: String?) -> Unit, viewModel: FuelViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    var open by rememberSheetTarget()
    val fmt = state.formatter
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    val snackbar = remember { SnackbarHostState() }
    var trendsOpen by rememberSaveable { mutableStateOf(true) }
    DeniedSnackbar(viewModel, snackbar)

    MetallicSheenHost {
        AppScaffold(
            title = stringResource(R.string.nav_fuel),
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (state.loaded && state.canAdd) {
                    AddFab(stringResource(R.string.action_log_fill_up), onClick = { onLogFillUp(state.selectedVehicleId) }, icon = Icons.Outlined.LocalGasStation)
                }
            },
        ) { padding ->
            Column(Modifier.padding(top = padding.calculateTopPadding()).fillMaxSize()) {
                WriteQueueBanner(sync, viewModel)
                VehicleFilterRow(state.vehicles, state.selectedVehicleId, viewModel::selectVehicle, Modifier.padding(bottom = 4.dp))
                RefreshBox(sync, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f)) {
                    LoadedContent(loaded = state.loaded, sync = sync, onRetry = viewModel::refresh) {
                        if (state.months.isEmpty()) {
                            EmptyState(
                                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
                                icon = Icons.Outlined.LocalGasStation,
                                title = stringResource(R.string.fuel_empty_title),
                                body = stringResource(R.string.fuel_empty_body),
                            )
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + FAB_CLEARANCE), modifier = Modifier.fillMaxSize()) {
                                state.summary?.let { summary -> item(key = "summary") { Summary(summary, fmt, showEconomy = state.trackFuelEconomy) } }
                                item(key = "trends-header", contentType = "header") {
                                    GroupHeader(
                                        stringResource(R.string.fuel_trends),
                                        action = stringResource(if (trendsOpen) R.string.action_hide else R.string.action_show),
                                        onAction = { trendsOpen = !trendsOpen },
                                    )
                                }
                                if (trendsOpen) item(key = "trends") { Trends(state.trends, state.vehicles.size, fmt, showEconomy = state.trackFuelEconomy) }
                                state.months.forEach { month ->
                                    item(key = month.month.toString(), contentType = "header") {
                                        GroupHeader(
                                            "${month.month.format(monthFormat).replaceFirstChar { it.uppercase() }} · " +
                                                fmt.money(month.totalCents, month.entries.first().currency),
                                        )
                                    }
                                    item(key = "${month.month}-list", contentType = "group") {
                                        GroupCard {
                                            month.entries.forEachIndexed { index, entry ->
                                                if (index > 0) RowDivider()
                                                FuelRow(
                                                    entry,
                                                    vehicle = state.vehicleById[entry.vehicleId].takeIf { showVehicle },
                                                    fmt = fmt,
                                                    onClick = { open = SheetTarget.Fuel(entry.id) },
                                                    economy = state.segments[entry.id]?.takeIf { state.trackFuelEconomy }?.let { fmt.economy(it.litersPer100Km) },
                                                    pending = entry.id in pendingIds,
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
        }
    }

    (open as? SheetTarget.Fuel)?.let { target ->
        state.entry(target.id)?.let { entry ->
            FuelSheet(
                entry,
                state.vehicleById[entry.vehicleId],
                state.segments[entry.id],
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
    }
}

/**
 * The web's fuel analytics charts (D-A23): consumption per fill-up and price per unit, for the one vehicle in view.
 * Under "All vehicles" the card says to pick one; a line needs at least two points.
 */
@Composable
private fun Trends(trends: FuelTrends?, vehicleCount: Int, fmt: Formatter, showEconomy: Boolean) {
    GroupCard {
        if (trends == null) {
            ChartPlaceholder(stringResource(if (vehicleCount > 1) R.string.chart_pick_vehicle else R.string.chart_needs_fill_ups))
            return@GroupCard
        }
        if (showEconomy) {
            val economyTitle = stringResource(R.string.fuel_chart_consumption, fmt.economyUnit)
            ChartTitle(economyTitle)
            val economy = trends.consumption.mapNotNull { point -> fmt.economyValue(point.value)?.let { point.copy(value = it) } }
            if (economy.size < 2) {
                ChartPlaceholder(stringResource(R.string.chart_needs_segments))
            } else {
                ValueChart(economy, fmt, economyTitle, format = { String.format(Locale.getDefault(), "%.1f", it) })
            }
        }
        val priceTitle = stringResource(R.string.fuel_chart_price, fmt.volumeUnit)
        ChartTitle(priceTitle)
        // Stored cents per liter → the display unit's price, the way the rows show it ($3.29/gal).
        val price = trends.price.map { point -> point.copy(value = fmt.displayUnitPrice(point.value)) }
        if (price.size < 2) {
            ChartPlaceholder(stringResource(R.string.chart_needs_fill_ups))
        } else {
            ValueChart(price, fmt, priceTitle, format = { fmt.money(it, null, fractionDigits = if (fmt.gallons) 2 else 3) })
        }
    }
}

/** A line of display-unit points with date labels at both ends and a spoken summary. */
@Composable
fun ValueChart(points: List<ChartPoint>, fmt: Formatter, title: String, format: (Double) -> String, zeroBased: Boolean = false) {
    LineChart(
        values = points.map { it.value },
        formatValue = format,
        summary = lineSummary(title, points, fmt, format),
        startLabel = fmt.shortDate(points.first().at),
        endLabel = fmt.shortDate(points.last().at),
        zeroBased = zeroBased,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    )
}

/** Room under the last row for the extended FAB, so it never covers a row's cost. */
val FAB_CLEARANCE = 96.dp

@Composable
private fun Summary(summary: FuelSummary, fmt: Formatter, showEconomy: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showEconomy) Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(R.string.fuel_avg_economy),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    val value = summary.averageConsumption?.let { fmt.economyValue(it) }
                    Text(
                        value?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        fmt.economyUnit,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                    )
                }
                if (summary.averageConsumption == null) {
                    Text(
                        stringResource(R.string.fuel_avg_needs_two),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        StatStrip(
            listOf(
                stringResource(R.string.fuel_spent) to fmt.money(summary.spentCents, summary.currency),
                stringResource(R.string.fuel_bought) to fmt.volume(summary.liters),
                stringResource(R.string.fuel_fill_ups) to summary.fillUps.toString(),
            ),
            title = stringResource(R.string.fuel_this_year),
        )
    }
}

package io.github.conchad.smartgarage.feature.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.TimelineEvent
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.RecordSheet
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.SearchField
import io.github.conchad.smartgarage.ui.components.expenseCategoryLabel
import io.github.conchad.smartgarage.ui.components.shortName
import io.github.conchad.smartgarage.ui.components.EmptyAction

@Composable
internal fun HistoryScreen(onBack: () -> Unit, actions: SectionActions, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    val searching = state.query.isNotBlank() && state.data.unfiltered > 0
    SectionScaffold(
        title = stringResource(R.string.nav_history),
        onBack = onBack,
        viewModel = viewModel,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.events.isEmpty(),
        emptyIcon = if (searching) Icons.Outlined.Search else Icons.Outlined.History,
        emptyTitle = stringResource(if (searching) R.string.search_no_matches_title else R.string.history_empty_title),
        emptyAction = if (searching) {
            { EmptyAction(stringResource(R.string.search_clear), Icons.Outlined.Close, onClick = { viewModel.search("") }) }
        } else {
            null
        },
        filters = {
            if (state.loaded && state.data.unfiltered > 0 || state.query.isNotEmpty()) {
                SearchField(state.query, viewModel::search, stringResource(R.string.search_history_hint), Modifier.padding(bottom = 8.dp))
            }
        },
    ) {
        byMonth(state.data.events, fmt.zone) { it.at }.forEach { (month, events) ->
            item(key = month.toString(), contentType = "header") { GroupHeader(monthLabel(month)) }
            item(key = "$month-list", contentType = "group") {
                GroupCard {
                    events.forEachIndexed { index, event ->
                        if (index > 0) RowDivider()
                        val vehicle = state.vehicleById[event.vehicleId]?.shortName?.takeIf { showVehicle }
                        val date = fmt.shortDate(event.at)
                        when (event) {
                            is TimelineEvent.Service -> SectionRow(
                                icon = Icons.Outlined.Build,
                                title = state.data.recordNames[event.record.id] ?: stringResource(R.string.record_untitled),
                                detail = listOfNotNull(date, vehicle, event.record.vendorName).joinToString(" · "),
                                onClick = { open = event.id },
                                trailing = fmt.money(event.record.costCents, event.record.currency),
                                pending = event.record.id in pendingIds,
                            )
                            is TimelineEvent.Fuel -> SectionRow(
                                icon = Icons.Outlined.LocalGasStation,
                                title = stringResource(R.string.history_fill_up),
                                detail = listOfNotNull(date, vehicle, event.entry.stationName).joinToString(" · "),
                                onClick = { open = event.id },
                                trailing = fmt.money(event.entry.totalCostCents, event.entry.currency),
                                trailingDetail = event.entry.liters?.let(fmt::volume),
                                pending = event.entry.id in pendingIds,
                            )
                            is TimelineEvent.Cost -> SectionRow(
                                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                                title = event.expense.description?.takeIf { it.isNotBlank() } ?: expenseCategoryLabel(event.expense.category),
                                detail = listOfNotNull(date, vehicle, expenseCategoryLabel(event.expense.category)).joinToString(" · "),
                                onClick = null,
                                trailing = fmt.money(event.expense.amountCents, event.expense.currency),
                                pending = event.expense.id in pendingIds,
                            )
                            is TimelineEvent.Odometer -> SectionRow(
                                icon = Icons.Outlined.Speed,
                                title = stringResource(R.string.history_odometer, fmt.distance(event.log.odometerKm)),
                                detail = listOfNotNull(date, vehicle, event.log.note).joinToString(" · "),
                                onClick = null,
                                pending = event.log.id in pendingIds,
                            )
                        }
                    }
                }
            }
        }
    }
    open?.let { id ->
        when (val event = state.data.events.firstOrNull { it.id == id }) {
            is TimelineEvent.Service -> RecordSheet(
                event.record,
                state.data.recordNames[event.record.id] ?: stringResource(R.string.record_untitled),
                state.vehicleById[event.vehicleId],
                fmt,
                onDismiss = { open = null },
                pending = event.record.id in pendingIds,
                onAttachReceipt = {
                    open = null
                    actions.onAttachReceipt(event.vehicleId, event.record.id)
                }.takeIf { state.vehicleById[event.vehicleId]?.access?.canEdit == true },
            )
            is TimelineEvent.Fuel -> FuelSheet(
                event.entry,
                state.vehicleById[event.vehicleId],
                state.data.segments[event.entry.id],
                fmt,
                onDismiss = { open = null },
                pending = event.entry.id in pendingIds,
            )
            else -> Unit
        }
    }
}

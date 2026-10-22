package io.github.conchad.smartgarage.feature.vehicles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.ui.navigation.VehicleDetailRoute
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.SyncedViewModel
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

data class VehiclesUiState(
    val vehicles: List<VehicleSummary> = emptyList(),
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
)

@HiltViewModel
class VehiclesViewModel @Inject constructor(garage: GarageRepository, control: SyncControl) : SyncedViewModel(control) {
    private val german = Locale.getDefault().language == "de"

    val state: StateFlow<VehiclesUiState> = combine(
        garage.vehicles,
        garage.schedules,
        garage.templates,
        garage.formatter,
    ) { vehicles, schedules, templates, fmt ->
        VehiclesUiState(GarageMath.vehicleSummaries(vehicles, GarageMath.dueItems(schedules, vehicles, templates, german)), fmt, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehiclesUiState())
}

data class VehicleDetailUiState(
    val detail: VehicleDetail? = null,
    val formatter: Formatter = Formatter(null),
    /** Display names of the vehicle's service records, by record id. */
    val recordNames: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
) {
    fun recordName(record: MaintenanceRecord): String? = recordNames[record.id]
}

@HiltViewModel
class VehicleDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val vehicleId = savedStateHandle.toRoute<VehicleDetailRoute>().vehicleId
    private val german = Locale.getDefault().language == "de"

    private val due = combine(garage.vehicles, garage.schedules, garage.templates) { vehicles, schedules, templates ->
        Triple(vehicles, GarageMath.dueItems(schedules, vehicles, templates, german), templates to schedules)
    }

    private val history = combine(garage.records, garage.fuelEntries, garage.expenses, ::Triple)
    private val papers = combine(garage.inspections, garage.insurancePolicies, ::Pair)

    val state: StateFlow<VehicleDetailUiState> = combine(due, history, papers, garage.formatter) { dueData, historyData, paperData, fmt ->
        val (vehicles, dueItems, templatesAndSchedules) = dueData
        val (records, fuel, expenses) = historyData
        val (inspections, policies) = paperData
        val vehicle = vehicles.firstOrNull { it.id == vehicleId }
            ?: return@combine VehicleDetailUiState(formatter = fmt, loaded = true)
        val yearStart = LocalDate.now(fmt.zone).withDayOfYear(1).atStartOfDay(fmt.zone).toInstant()
        val detail = GarageMath.vehicleDetail(vehicle, dueItems, records, fuel, expenses, inspections, policies, yearStart)
        val (templates, schedules) = templatesAndSchedules
        val scheduleById = schedules.associateBy { it.id }
        val templateById = templates.associateBy { it.id }
        val names = detail.records.mapNotNull { record ->
            GarageMath.recordName(record, scheduleById, templateById, german)?.let { record.id to it }
        }.toMap()
        VehicleDetailUiState(detail, fmt, names, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleDetailUiState())

    fun deleteFuelEntry(id: String) = perform { deleteFuelEntry(id) }

    fun deleteRecord(id: String) = perform { deleteRecord(id) }
}

package io.github.conchad.smartgarage.feature.maintenance

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.DueItem
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.MaintenanceTemplate
import io.github.conchad.smartgarage.core.model.Schedule
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

data class MaintenanceUiState(
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: String? = null,
    /** Overdue → due soon → ok, for the selected vehicle (or all). */
    val due: List<DueItem> = emptyList(),
    /** Newest first. */
    val records: List<MaintenanceRecord> = emptyList(),
    val recordNames: Map<String, String> = emptyMap(),
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
) {
    val vehicleById: Map<String, Vehicle> = vehicles.associateBy { it.id }

    /** A schedule on an editable vehicle exists: the FAB hides otherwise, since a record needs a schedule. */
    val canAdd: Boolean get() = due.any { it.vehicle.access.canEdit }

    /** A new schedule only needs an editable vehicle. */
    val canAddSchedule: Boolean get() = vehicles.any { it.access.canEdit }
}

@HiltViewModel
class MaintenanceViewModel @Inject constructor(garage: GarageRepository, control: SyncControl, writer: GarageWriter) : WritingViewModel(control, writer) {
    private val german = Locale.getDefault().language == "de"
    private val selected = MutableStateFlow<String?>(null)

    private val base = combine(garage.vehicles, garage.schedules, garage.templates, garage.records, ::Base)

    val state: StateFlow<MaintenanceUiState> = combine(base, garage.formatter, selected) { data, fmt, vehicleId ->
        val vehicles = data.vehicles.sortedWith(GarageMath.vehicleOrder)
        // A vehicle that disappeared (deleted or unshared) drops the filter rather than showing nothing.
        val filter = vehicleId?.takeIf { id -> vehicles.any { it.id == id } }
        val vehicleIds = vehicles.mapTo(HashSet()) { it.id }
        val due = GarageMath.dueItems(data.schedules, vehicles, data.templates, german)
            .filter { filter == null || it.vehicle.id == filter }
        val records = data.records
            .filter { (filter == null || it.vehicleId == filter) && it.vehicleId in vehicleIds }
            .sortedByDescending { it.performedAt.toInstantOrNull() ?: Instant.MIN }
        val schedules = data.schedules.associateBy { it.id }
        val templates = data.templates.associateBy { it.id }
        MaintenanceUiState(
            vehicles = vehicles,
            selectedVehicleId = filter,
            due = due,
            records = records,
            recordNames = records.mapNotNull { r -> GarageMath.recordName(r, schedules, templates, german)?.let { r.id to it } }.toMap(),
            formatter = fmt,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceUiState())

    fun selectVehicle(id: String?) {
        selected.value = id
    }

    fun deleteRecord(id: String) = perform { deleteRecord(id) }

    /** The web's "remove": the schedule stops reminding; its records stay. */
    fun deleteSchedule(id: String) = perform { deleteSchedule(id) }

    private data class Base(
        val vehicles: List<Vehicle>,
        val schedules: List<Schedule>,
        val templates: List<MaintenanceTemplate>,
        val records: List<MaintenanceRecord>,
    )
}

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
import io.github.conchad.smartgarage.core.model.SearchMatch
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
    /** The History tab's search text; [records] are already narrowed by it. */
    val query: String = "",
    /** Records in view before the search narrowed them. */
    val unfilteredRecords: Int = 0,
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
    private val query = MutableStateFlow("")

    /**
     * Everything that doesn't depend on the vehicle filter or the search, computed off the main thread once per cache
     * change: due items over all vehicles, every record newest first, and every record's display name. The filter and
     * the search (bound to the text field, so they stay on the main thread) only narrow these lists.
     */
    private val prepared = combine(garage.vehicles, garage.schedules, garage.templates, garage.records) { allVehicles, schedules, templates, allRecords ->
        val vehicles = allVehicles.sortedWith(GarageMath.vehicleOrder)
        val vehicleIds = vehicles.mapTo(HashSet()) { it.id }
        val records = allRecords
            .filter { it.vehicleId in vehicleIds }
            .sortedByDescending { it.performedAt.toInstantOrNull() ?: Instant.MIN }
        val scheduleById = schedules.associateBy { it.id }
        val templateById = templates.associateBy { it.id }
        Prepared(
            vehicles = vehicles,
            due = GarageMath.dueItems(schedules, vehicles, templates, german),
            records = records,
            names = records.mapNotNull { r -> GarageMath.recordName(r, scheduleById, templateById, german)?.let { r.id to it } }.toMap(),
        )
    }.flowOn(Dispatchers.Default)

    val state: StateFlow<MaintenanceUiState> = combine(prepared, garage.formatter, selected, query) { data, fmt, vehicleId, text ->
        val vehicles = data.vehicles
        // A vehicle that disappeared (deleted or unshared) drops the filter rather than showing nothing.
        val filter = vehicleId?.takeIf { id -> vehicles.any { it.id == id } }
        val due = data.due.filter { filter == null || it.vehicle.id == filter }
        // Filtering the stably sorted list keeps the same order as sorting the filtered one.
        val inView = if (filter == null) data.records else data.records.filter { it.vehicleId == filter }
        val names = if (filter == null) {
            data.names
        } else {
            inView.mapNotNull { r -> data.names[r.id]?.let { r.id to it } }.toMap()
        }
        // The search runs here, not in composition (D-A23): name, vendor, notes and parts.
        val terms = SearchMatch.terms(text)
        val records = inView.filter { SearchMatch.record(it, names[it.id], terms) }
        MaintenanceUiState(
            vehicles = vehicles,
            selectedVehicleId = filter,
            due = due,
            records = records,
            recordNames = names,
            formatter = fmt,
            loaded = true,
            query = text,
            unfilteredRecords = inView.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MaintenanceUiState())

    fun selectVehicle(id: String?) {
        selected.value = id
    }

    fun search(text: String) {
        query.value = text
    }

    fun deleteRecord(id: String) = perform { deleteRecord(id) }

    /** The web's "remove": the schedule stops reminding; its records stay. */
    fun deleteSchedule(id: String) = perform { deleteSchedule(id) }

    private class Prepared(
        val vehicles: List<Vehicle>,
        val due: List<DueItem>,
        val records: List<MaintenanceRecord>,
        val names: Map<String, String>,
    )
}

package io.github.conchad.smartgarage.feature.vehicles

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.ui.files.FileProblem
import io.github.conchad.smartgarage.ui.files.UploadKind
import io.github.conchad.smartgarage.ui.files.inspect
import io.github.conchad.smartgarage.ui.files.open
import io.github.conchad.smartgarage.ui.navigation.VehicleDetailRoute
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.SyncedViewModel
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

/** Why a picked photo was not sent, or null; the screen shows it once and clears it. */
sealed interface PhotoPick {
    data object Unreadable : PhotoPick
    data class Rejected(val problem: FileProblem) : PhotoPick
}

@HiltViewModel
class VehicleDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
    @ApplicationContext private val context: Context,
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

    private val closedState = MutableStateFlow(false)

    /** True once the vehicle's delete is queued: the screen has nothing left to show and goes back. */
    val closed: StateFlow<Boolean> = closedState.asStateFlow()

    private val photoPickState = MutableStateFlow<PhotoPick?>(null)
    val photoPick: StateFlow<PhotoPick?> = photoPickState.asStateFlow()

    fun clearPhotoPick() {
        photoPickState.value = null
    }

    fun deleteFuelEntry(id: String) = perform { deleteFuelEntry(id) }

    fun deleteRecord(id: String) = perform { deleteRecord(id) }

    /** The web's "remove": the schedule stops reminding; its records stay. */
    fun deleteSchedule(id: String) = perform { deleteSchedule(id) }

    fun deleteInspection(id: String) = perform { deleteInspection(id) }

    fun deleteInsurance(id: String) = perform { deleteInsurance(id) }

    /** Owner only; everything belonging to the vehicle goes with it. */
    fun deleteVehicle() {
        viewModelScope.launch {
            if (report(writer.deleteVehicle(vehicleId))) closedState.value = true
        }
    }

    /**
     * A photo from the system picker (D-A21): name, type and size come from the content provider and the file's
     * first bytes; a file the server would refuse (type, size) is reported here instead of queued.
     */
    fun setPhoto(uri: Uri) {
        viewModelScope.launch {
            val resolver = context.contentResolver
            val file = resolver.inspect(uri) ?: return@launch run { photoPickState.value = PhotoPick.Unreadable }
            file.problem(UploadKind.VehiclePhoto)?.let { photoPickState.value = PhotoPick.Rejected(it); return@launch }
            val stream = resolver.open(file) ?: return@launch run { photoPickState.value = PhotoPick.Unreadable }
            report(writer.setVehiclePhoto(vehicleId, file.name, file.mimeType ?: "application/octet-stream", stream))
        }
    }
}

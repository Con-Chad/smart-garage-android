package io.github.conchad.smartgarage.feature.vehicles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.AppSettings
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.CustomField
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.Modification
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.core.model.VehicleSummary
import io.github.conchad.smartgarage.ui.files.FileProblem
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
    /** The owner's custom fields that have a value on this vehicle, in the owner's order (D-A23). */
    val customFields: List<Pair<CustomField, String>> = emptyList(),
    /** Fitted modifications, most recently installed first. */
    val modifications: List<Modification> = emptyList(),
    /**
     * Who the car belongs to, for "Owner: …" (D-A25): the signed-in account's name for an own vehicle, the sharing
     * owner's for a shared one. Null when neither is known.
     */
    val ownerName: String? = null,
    /** Settings → Fuel → Track fuel economy (D-A25): off hides consumption on this page too. */
    val trackFuelEconomy: Boolean = true,
    /** The custom field about tyres, if the owner keeps one; shown as the "Tires" row and left out of the field list. */
    val tireField: Pair<CustomField, String>? = null,
    /** The most recent modification about tyres, when there is no such field. */
    val tireModification: Modification? = null,
    val loaded: Boolean = false,
) {
    fun recordName(record: MaintenanceRecord): String? = recordNames[record.id]

    /** The custom fields without the one surfaced as "Tires". */
    val otherCustomFields: List<Pair<CustomField, String>> get() = customFields.filterNot { it === tireField }
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
    settings: AppSettings,
    profiles: ProfileStore,
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
    private val extras = combine(garage.customFields, garage.customFieldValues, garage.modifications, ::Triple)
    // Device-side context: the fuel-economy switch and the signed-in account (named as the owner of own vehicles).
    private val device = combine(settings.fuel, profiles.state) { fuelSettings, p -> fuelSettings.trackFuelEconomy to p.active?.account?.greetingName }
    private val extrasAndDevice = combine(extras, device, ::Pair)

    val state: StateFlow<VehicleDetailUiState> = combine(due, history, papers, extrasAndDevice, garage.formatter) { dueData, historyData, paperData, (extraData, deviceData), fmt ->
        val (vehicles, dueItems, templatesAndSchedules) = dueData
        val (records, fuel, expenses) = historyData
        val (inspections, policies) = paperData
        val (fields, values, modifications) = extraData
        val (trackFuelEconomy, accountName) = deviceData
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
        // The web's order: the owner's field positions; only fields with a value on this vehicle are rows.
        val valueByField = values.filter { it.vehicleId == vehicleId }.associateBy { it.fieldId }
        val customFields = fields
            .sortedWith(compareBy<CustomField> { it.position }.thenBy { it.createdAt })
            .mapNotNull { field -> valueByField[field.id]?.value?.takeIf { it.isNotBlank() }?.let { field to it } }
        val fitted = modifications.filter { it.vehicleId == vehicleId }
            .sortedWith(compareByDescending<Modification> { it.installedAt ?: "" }.thenByDescending { it.createdAt })
        val tireField = customFields.firstOrNull { (field, _) -> GarageMath.isAboutTires(field.label) }
        val tireModification = if (tireField == null) fitted.firstOrNull { GarageMath.isAboutTires(it.name) } else null
        VehicleDetailUiState(
            detail, fmt, names, customFields, fitted,
            ownerName = if (vehicle.access.isShared) vehicle.owner?.name else accountName ?: vehicle.owner?.name,
            trackFuelEconomy = trackFuelEconomy,
            tireField = tireField,
            tireModification = tireModification,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VehicleDetailUiState())

    private val closedState = MutableStateFlow(false)

    /** True once the vehicle's delete is queued: the screen has nothing left to show and goes back. */
    val closed: StateFlow<Boolean> = closedState.asStateFlow()

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
}

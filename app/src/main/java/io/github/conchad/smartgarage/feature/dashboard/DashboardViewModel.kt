package io.github.conchad.smartgarage.feature.dashboard

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.AuroraChoice
import io.github.conchad.smartgarage.core.data.VehicleLocalPrefs
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Dashboard
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.OdometerReminder
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.feature.vehicles.VehicleDetailSource
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.SyncInfo
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

/** What the home card's dropdown and the odometer reminder know about one vehicle (D-A24). */
data class VehicleExtras(
    /** Newest odometer evidence: a log or a fill-up with a reading; null when the vehicle has neither. */
    val lastOdometerAt: Instant? = null,
    /** The monthly nudge to show, or null when the reading is recent, snoozed, or of unknown age. */
    val reminder: OdometerReminder.Reminder? = null,
)

data class DashboardUiState(
    val dashboard: Dashboard? = null,
    val formatter: Formatter = Formatter(null),
    val sync: SyncInfo = SyncInfo(),
    val extras: Map<String, VehicleExtras> = emptyMap(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
    private val prefs: VehicleLocalPrefs,
    detailSource: VehicleDetailSource,
) : WritingViewModel(control, writer) {

    private val german = Locale.getDefault().language == "de"

    private val dashboard = combine(
        combine(garage.vehicles, garage.schedules, garage.templates, ::Triple),
        combine(garage.inspections, garage.insurancePolicies, garage.fuelEntries, ::Triple),
    ) { (vehicles, schedules, templates), (inspections, policies, fuel) ->
        GarageMath.dashboard(vehicles, schedules, templates, inspections, policies, fuel, german)
    }
        // The due/attention math and its timestamp parsing run off the main thread; only the result hops back.
        .flowOn(Dispatchers.Default)

    private val extras = combine(garage.vehicles, garage.odometerLogs, garage.fuelEntries, prefs.odometerReminderSnoozedAt) { vehicles, logs, fuel, snoozed ->
        val now = Instant.now()
        vehicles.associate { vehicle ->
            vehicle.id to VehicleExtras(
                lastOdometerAt = OdometerReminder.lastUpdate(vehicle.id, logs, fuel),
                reminder = OdometerReminder.evaluate(vehicle.id, logs, fuel, vehicle.updatedAt.toInstantOrNull(), now, snoozed[vehicle.id]),
            )
        }
    }.flowOn(Dispatchers.Default)

    // The vehicle page's inputs stay in memory while Home is on screen, so a tapped card opens on its content (D-A35).
    val state: StateFlow<DashboardUiState> = combine(dashboard, garage.formatter, syncInfo, extras, detailSource.keepWarm) { d, fmt, sync, ex, _ ->
        DashboardUiState(d, fmt, sync, ex)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun deleteFuelEntry(id: String) = perform { deleteFuelEntry(id) }

    /** "Later" on the odometer reminder: quiet for another 30 days. */
    fun snoozeOdometerReminder(vehicleId: String) {
        viewModelScope.launch { prefs.snoozeOdometerReminder(vehicleId) }
    }

    /** The fan's "Theme color" sheet (D-A57): this vehicle's aurora override; null follows Settings again. */
    fun setAuroraOverride(vehicleId: String, choice: AuroraChoice?) {
        viewModelScope.launch { prefs.setAuroraOverride(vehicleId, choice) }
    }
}

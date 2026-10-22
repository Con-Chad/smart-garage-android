package io.github.conchad.smartgarage.feature.dashboard

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Dashboard
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.SyncInfo
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

data class DashboardUiState(
    val dashboard: Dashboard? = null,
    val formatter: Formatter = Formatter(null),
    val sync: SyncInfo = SyncInfo(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    garage: GarageRepository,
    profiles: ProfileStore,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {

    private val german = Locale.getDefault().language == "de"

    private val dashboard = combine(
        combine(garage.vehicles, garage.schedules, garage.templates, ::Triple),
        combine(garage.inspections, garage.insurancePolicies, garage.fuelEntries, ::Triple),
    ) { (vehicles, schedules, templates), (inspections, policies, fuel) ->
        GarageMath.dashboard(vehicles, schedules, templates, inspections, policies, fuel, german)
    }

    private val formatter = profiles.state.map { Formatter(it.active?.preferences) }

    val state: StateFlow<DashboardUiState> = combine(dashboard, formatter, syncInfo, ::DashboardUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun deleteFuelEntry(id: String) = perform { deleteFuelEntry(id) }
}

package io.github.conchad.smartgarage.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Dashboard
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.sync.SyncEngine
import io.github.conchad.smartgarage.core.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

data class SyncInfo(val status: SyncStatus = SyncStatus(), val lastSyncedAt: Instant? = null)

data class DashboardUiState(
    val dashboard: Dashboard? = null,
    val formatter: Formatter = Formatter(null),
    val sync: SyncInfo = SyncInfo(),
) {
    /** Nothing cached yet and the first sync hasn't finished: show a loading state, not "no vehicles". */
    val firstLoad: Boolean get() = sync.lastSyncedAt == null && sync.status.lastProblem == null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val garage: GarageRepository,
    private val sync: SyncEngine,
    profiles: ProfileStore,
) : ViewModel() {

    private val german = Locale.getDefault().language == "de"

    private val dashboard = combine(
        combine(garage.vehicles, garage.schedules, garage.templates, ::Triple),
        combine(garage.inspections, garage.insurancePolicies, garage.fuelEntries, ::Triple),
    ) { (vehicles, schedules, templates), (inspections, policies, fuel) ->
        GarageMath.dashboard(vehicles, schedules, templates, inspections, policies, fuel, german)
    }

    private val formatter = profiles.state.map { Formatter(it.active?.preferences) }

    val syncInfo = combine(garage.activeProfileId, sync.status, garage.lastSyncedAt) { id, statuses, last ->
        SyncInfo(statuses[id] ?: SyncStatus(), last)
    }

    val state: StateFlow<DashboardUiState> = combine(dashboard, formatter, syncInfo, ::DashboardUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun refresh() {
        viewModelScope.launch { sync.sync(garage.activeProfileId.first()) }
    }
}

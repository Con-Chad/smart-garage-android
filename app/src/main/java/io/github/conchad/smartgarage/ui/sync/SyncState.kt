package io.github.conchad.smartgarage.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.core.sync.SyncEngine
import io.github.conchad.smartgarage.core.sync.SyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** What every cache-backed screen knows about syncing: the engine's status for the active profile and the last success. */
data class SyncInfo(val status: SyncStatus = SyncStatus(), val lastSyncedAt: Instant? = null) {
    /** Nothing cached yet and nothing has failed: the first sync is running (or about to). Show loading, not "empty". */
    val firstLoad: Boolean get() = lastSyncedAt == null && status.lastProblem == null

    /** Nothing cached yet and the last attempt failed: there is nothing to show at all. */
    val neverSynced: Boolean get() = lastSyncedAt == null && status.lastProblem != null

    /** The last attempt failed after an earlier success, so what is on screen may be out of date. */
    val stale: Boolean get() = lastSyncedAt != null && status.lastProblem != null

    /** Stale because the server could not be reached at all (as opposed to answering with an error). */
    val offline: Boolean get() = stale && status.lastProblem is ServerProblem.Unreachable
}

/** The sync plumbing a screen needs (D-A13): status to show and a way to pull on demand. */
class SyncControl @Inject constructor(private val garage: GarageRepository, private val sync: SyncEngine) {
    val info: Flow<SyncInfo> = combine(garage.activeProfileId, sync.status, garage.lastSyncedAt) { id, statuses, last ->
        SyncInfo(statuses[id] ?: SyncStatus(), last)
    }

    suspend fun refresh() {
        sync.sync(garage.activeProfileId.first())
    }
}

/** Base for the ViewModels behind cache-backed screens: [syncInfo] for the banner and spinner, [refresh] for pull-to-refresh. */
abstract class SyncedViewModel(private val control: SyncControl) : ViewModel() {
    val syncInfo: StateFlow<SyncInfo> = control.info.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncInfo())

    fun refresh() {
        viewModelScope.launch { control.refresh() }
    }
}

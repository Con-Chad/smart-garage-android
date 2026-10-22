package io.github.conchad.smartgarage.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.conchad.smartgarage.core.cache.OutboxState
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.core.sync.SyncEngine
import io.github.conchad.smartgarage.core.sync.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What every cache-backed screen knows about syncing: the engine's status for the active profile, the last success,
 * and the state of the write queue (D-A16).
 */
data class SyncInfo(
    val status: SyncStatus = SyncStatus(),
    val lastSyncedAt: Instant? = null,
    /** Changes made on this phone that the server hasn't taken yet. */
    val pending: Int = 0,
    /** Changes the server rejected; see [GarageWriter.failed] for the details. */
    val failed: Int = 0,
) {
    /** Nothing cached yet and nothing has failed: the first sync is running (or about to). Show loading, not "empty". */
    val firstLoad: Boolean get() = lastSyncedAt == null && status.lastProblem == null

    /** Nothing cached yet and the last attempt failed: there is nothing to show at all. */
    val neverSynced: Boolean get() = lastSyncedAt == null && status.lastProblem != null

    /** The last attempt failed after an earlier success, so what is on screen may be out of date. */
    val stale: Boolean get() = lastSyncedAt != null && status.lastProblem != null

    /** Stale because the server could not be reached at all (as opposed to answering with an error). */
    val offline: Boolean get() = stale && status.lastProblem is ServerProblem.Unreachable

    val hasPending: Boolean get() = pending > 0

    val hasFailed: Boolean get() = failed > 0
}

/**
 * The sync plumbing a screen needs (D-A13): status to show and a way to pull on demand. One instance for the app:
 * [info] is combined once and shared (D-A22), so every open screen reads the same outbox and meta observers instead
 * of registering its own set with Room.
 */
@Singleton
class SyncControl @Inject constructor(
    private val garage: GarageRepository,
    private val sync: SyncEngine,
    writer: GarageWriter,
    scope: CoroutineScope,
) {
    val info: Flow<SyncInfo> = combine(garage.activeProfileId, sync.status, garage.lastSyncedAt, writer.entries) { id, statuses, last, outbox ->
        SyncInfo(
            status = statuses[id] ?: SyncStatus(),
            lastSyncedAt = last,
            pending = outbox.count { it.state != OutboxState.FAILED },
            failed = outbox.count { it.state == OutboxState.FAILED },
        )
    }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = GarageRepository.STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

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

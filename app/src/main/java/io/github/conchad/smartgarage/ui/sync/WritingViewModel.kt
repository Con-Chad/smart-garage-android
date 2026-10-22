package io.github.conchad.smartgarage.ui.sync

import androidx.lifecycle.viewModelScope
import io.github.conchad.smartgarage.core.data.FailedWrite
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.WriteDenied
import io.github.conchad.smartgarage.core.data.WriteResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * [SyncedViewModel] for screens that also write (CP11): the ids of rows still waiting to sync, the writes the
 * server rejected, and a one-shot [denied] reason for a snackbar when [GarageWriter] refuses a call locally.
 */
abstract class WritingViewModel(control: SyncControl, protected val writer: GarageWriter) : SyncedViewModel(control) {

    /** Ids of cached rows with a change the server hasn't taken yet; rows show a [io.github.conchad.smartgarage.ui.components.forms.PendingMark]. */
    val pendingIds: StateFlow<Set<String>> = writer.pendingIds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Writes the server rejected, for the "couldn't be saved" list. */
    val failedWrites: StateFlow<List<FailedWrite>> = writer.failed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val deniedState = MutableStateFlow<WriteDenied?>(null)

    /** Why the last write was refused locally; the screen shows it and calls [clearDenied]. */
    val denied: StateFlow<WriteDenied?> = deniedState.asStateFlow()

    fun clearDenied() {
        deniedState.value = null
    }

    fun retryWrite(key: String) {
        viewModelScope.launch { writer.retry(key) }
    }

    fun dismissWrite(key: String) {
        viewModelScope.launch { writer.dismiss(key) }
    }

    /** Runs a write off the main thread and surfaces a local refusal through [denied]. */
    protected fun perform(block: suspend GarageWriter.() -> WriteResult) {
        viewModelScope.launch { report(writer.block()) }
    }

    /** Records a [WriteResult.Denied] in [denied]; returns true when the write was queued. */
    protected fun report(result: WriteResult): Boolean = when (result) {
        is WriteResult.Queued -> true
        is WriteResult.Denied -> {
            deniedState.value = result.reason
            false
        }
    }
}

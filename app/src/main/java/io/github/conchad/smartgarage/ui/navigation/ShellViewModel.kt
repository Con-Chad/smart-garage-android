package io.github.conchad.smartgarage.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.PhotoStore
import io.github.conchad.smartgarage.core.push.PushTapRouter
import io.github.conchad.smartgarage.core.push.PushTarget
import io.github.conchad.smartgarage.core.sync.SyncEngine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val sync: SyncEngine,
    val photos: PhotoStore,
    private val pushTaps: PushTapRouter,
) : ViewModel() {
    /** A tapped notification waiting to be opened (D-A29); the shell for its profile navigates and consumes it. */
    val pendingPush: StateFlow<PushTarget?> = pushTaps.pending

    fun pushOpened(target: PushTarget) = pushTaps.consumed(target)

    /** Called each time the app comes to the foreground: cheap when nothing changed (D-028 incremental sync). */
    fun syncNow(profileId: String) {
        viewModelScope.launch { sync.sync(profileId) }
    }
}

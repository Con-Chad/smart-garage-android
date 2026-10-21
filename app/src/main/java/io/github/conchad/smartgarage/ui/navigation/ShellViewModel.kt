package io.github.conchad.smartgarage.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.PhotoStore
import io.github.conchad.smartgarage.core.sync.SyncEngine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val sync: SyncEngine,
    val photos: PhotoStore,
) : ViewModel() {
    /** Called each time the app comes to the foreground: cheap when nothing changed (D-028 incremental sync). */
    fun syncNow(profileId: String) {
        viewModelScope.launch { sync.sync(profileId) }
    }
}

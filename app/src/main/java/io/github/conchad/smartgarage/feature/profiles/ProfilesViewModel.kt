package io.github.conchad.smartgarage.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.AuthRepository
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.data.ProfilesState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profiles: ProfileStore,
    private val auth: AuthRepository,
) : ViewModel() {

    val state: StateFlow<ProfilesState?> = profiles.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun activate(id: String) = viewModelScope.launch { profiles.setActive(id) }

    fun signOut(id: String) = viewModelScope.launch { auth.signOut(id) }

    fun remove(id: String) = viewModelScope.launch { auth.removeProfile(id) }
}

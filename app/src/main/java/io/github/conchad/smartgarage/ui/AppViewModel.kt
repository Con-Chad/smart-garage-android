package io.github.conchad.smartgarage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.cache.CacheProvider
import io.github.conchad.smartgarage.core.data.Account
import io.github.conchad.smartgarage.core.data.AppSettings
import io.github.conchad.smartgarage.core.data.AppearanceSettings
import io.github.conchad.smartgarage.core.data.AuthRepository
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.data.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface RootState {
    data object Loading : RootState

    /** No server yet: run the first-run setup. */
    data object Onboarding : RootState

    /** The active profile has no valid session (signed out, or its token was revoked). */
    data class SignedOut(val profile: ServerProfile, val otherProfiles: List<ServerProfile>) : RootState

    data class Ready(val profile: ServerProfile, val account: Account) : RootState
}

data class RootUiState(val root: RootState = RootState.Loading, val appearance: AppearanceSettings = AppearanceSettings())

@HiltViewModel
class AppViewModel @Inject constructor(
    profileStore: ProfileStore,
    private val auth: AuthRepository,
    settings: AppSettings,
    cache: CacheProvider,
) : ViewModel() {

    val state: StateFlow<RootUiState> = combine(profileStore.state, settings.appearance) { profiles, appearance ->
        val active = profiles.active
        val root = when {
            active == null -> RootState.Onboarding
            profiles.isSignedIn(active.id) && active.account != null -> RootState.Ready(active, active.account)
            else -> RootState.SignedOut(active, profiles.profiles.filter { it.id != active.id })
        }
        RootUiState(root, appearance)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootUiState())

    init {
        // Both run off Main: listing and deleting database files is disk I/O, and refresh() decrypts the token
        // through the Keystore before its network call.
        viewModelScope.launch(Dispatchers.IO) { cache.deleteOrphans(profileStore.current().profiles.mapTo(mutableSetOf()) { it.id }) }
        // Check the session once per active profile: picks up revoked tokens and changed display preferences.
        viewModelScope.launch(Dispatchers.Default) {
            state.map { (it.root as? RootState.Ready)?.profile?.id }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { auth.refresh(it) }
        }
    }
}

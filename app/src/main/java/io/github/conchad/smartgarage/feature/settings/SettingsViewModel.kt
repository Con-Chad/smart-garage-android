package io.github.conchad.smartgarage.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.AppSettings
import io.github.conchad.smartgarage.core.data.AppearanceSettings
import io.github.conchad.smartgarage.core.data.AuthRepository
import io.github.conchad.smartgarage.core.data.FuelSettings
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.core.data.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val profile: ServerProfile? = null,
    val profileCount: Int = 0,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val fuel: FuelSettings = FuelSettings(),
    val signingOut: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileStore,
    private val settings: AppSettings,
    private val auth: AuthRepository,
) : ViewModel() {

    private val signingOut = MutableStateFlow(false)

    val state: StateFlow<SettingsUiState> =
        combine(profiles.state, settings.appearance, settings.fuel, signingOut) { p, appearance, fuel, busy ->
            SettingsUiState(p.active, p.profiles.size, appearance, fuel, busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settings.setDynamicColor(enabled) }

    fun setTrackFuelEconomy(enabled: Boolean) = viewModelScope.launch { settings.setTrackFuelEconomy(enabled) }

    /** Only offered for plain-http profiles (D-A28). */
    fun setBackgroundSyncOnCleartext(enabled: Boolean) {
        val id = state.value.profile?.id ?: return
        viewModelScope.launch { profiles.update(id) { it.copy(backgroundSyncOnCleartext = enabled) } }
    }

    fun signOut() {
        val id = state.value.profile?.id ?: return
        viewModelScope.launch {
            signingOut.value = true
            try {
                auth.signOut(id)
            } finally {
                signingOut.value = false
            }
        }
    }
}

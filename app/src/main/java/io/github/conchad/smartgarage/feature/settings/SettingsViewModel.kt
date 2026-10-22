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
import io.github.conchad.smartgarage.core.push.Distributor
import io.github.conchad.smartgarage.core.push.PushEnableOutcome
import io.github.conchad.smartgarage.core.push.PushNotifier
import io.github.conchad.smartgarage.core.push.PushRegistrar
import io.github.conchad.smartgarage.core.push.PushTestOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the push rows are doing right now; the persisted state is on the profile ([ServerProfile.push]). */
data class PushUiState(
    /** Human name of the distributor in use (ntfy, …), null when none is saved. */
    val distributorLabel: String? = null,
    /** Installed distributors to choose from; shown as a picker when the switch is turned on and several exist. */
    val choices: List<Distributor>? = null,
    val busy: Boolean = false,
    val sendingTest: Boolean = false,
    /** Result of the last test push, cleared by [SettingsViewModel.pushTestShown]. */
    val testOutcome: PushTestOutcome? = null,
    /** Push is on but notifications are blocked for the app (permission denied or channel off). */
    val notificationsBlocked: Boolean = false,
    /** Bumped after the permission dialog so [notificationsBlocked] is re-evaluated. */
    val permissionChecks: Int = 0,
)

data class SettingsUiState(
    val profile: ServerProfile? = null,
    val profileCount: Int = 0,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val fuel: FuelSettings = FuelSettings(),
    val signingOut: Boolean = false,
    val push: PushUiState = PushUiState(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileStore,
    private val settings: AppSettings,
    private val auth: AuthRepository,
    private val push: PushRegistrar,
    private val notifier: PushNotifier,
) : ViewModel() {

    private val signingOut = MutableStateFlow(false)
    private val pushUi = MutableStateFlow(PushUiState())

    val state: StateFlow<SettingsUiState> =
        combine(profiles.state, settings.appearance, settings.fuel, signingOut, pushUi) { p, appearance, fuel, busy, pushState ->
            val active = p.active
            SettingsUiState(
                profile = active,
                profileCount = p.profiles.size,
                appearance = appearance,
                fuel = fuel,
                signingOut = busy,
                push = pushState.copy(
                    distributorLabel = active?.push?.distributor?.let(push::label),
                    notificationsBlocked = active?.push?.enabled == true && !notifier.canNotify(),
                ),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settings.setDynamicColor(enabled) }

    fun setTrackFuelEconomy(enabled: Boolean) = viewModelScope.launch { settings.setTrackFuelEconomy(enabled) }

    /** Only offered for plain-http profiles (D-A28). */
    fun setBackgroundSyncOnCleartext(enabled: Boolean) {
        val id = state.value.profile?.id ?: return
        viewModelScope.launch { profiles.update(id) { it.copy(backgroundSyncOnCleartext = enabled) } }
    }

    /**
     * The push switch (D-A29). On: register with the saved or only distributor, or ask the screen to show the picker
     * ([PushUiState.choices]) when there are several or none. Off: DELETE on the server and unregister.
     */
    fun setPushEnabled(enabled: Boolean) {
        val id = state.value.profile?.id ?: return
        viewModelScope.launch {
            pushUi.value = pushUi.value.copy(busy = true)
            try {
                if (enabled) {
                    when (val outcome = push.enable(id)) {
                        is PushEnableOutcome.NeedsDistributor -> pushUi.value = pushUi.value.copy(choices = outcome.installed)
                        else -> Unit
                    }
                } else {
                    push.disable(id)
                }
            } finally {
                pushUi.value = pushUi.value.copy(busy = false)
            }
        }
    }

    /** The user picked a distributor from the list the switch brought up. */
    fun choosePushDistributor(packageName: String) {
        val id = state.value.profile?.id ?: return
        pushUi.value = pushUi.value.copy(choices = null, busy = true)
        viewModelScope.launch {
            try {
                push.enable(id, packageName)
            } finally {
                pushUi.value = pushUi.value.copy(busy = false)
            }
        }
    }

    /** The picker was dismissed without a choice: push stays off. */
    fun dismissPushDistributors() {
        val id = state.value.profile?.id ?: return
        pushUi.value = pushUi.value.copy(choices = null)
        viewModelScope.launch { push.disable(id) }
    }

    fun sendTestPush() {
        val id = state.value.profile?.id ?: return
        viewModelScope.launch {
            pushUi.value = pushUi.value.copy(sendingTest = true)
            val outcome = try {
                push.sendTest(id)
            } finally {
                pushUi.value = pushUi.value.copy(sendingTest = false)
            }
            pushUi.value = pushUi.value.copy(testOutcome = outcome)
        }
    }

    fun pushTestShown() {
        pushUi.value = pushUi.value.copy(testOutcome = null)
    }

    /** After the permission dialog closed: re-evaluate whether notifications would show. */
    fun notificationPermissionChanged() {
        pushUi.value = pushUi.value.copy(permissionChecks = pushUi.value.permissionChecks + 1)
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

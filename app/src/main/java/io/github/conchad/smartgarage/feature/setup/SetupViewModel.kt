package io.github.conchad.smartgarage.feature.setup

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.AuthRepository
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.data.ServerDraft
import io.github.conchad.smartgarage.core.data.ServerProfile
import io.github.conchad.smartgarage.core.data.SignInResult
import io.github.conchad.smartgarage.core.network.CfAccessCredentials
import io.github.conchad.smartgarage.core.network.HealthResult
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.core.network.ServerUrl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SetupMode {
    val key: String

    /** No server configured yet. */
    data object FirstRun : SetupMode { override val key = "first-run" }

    /** Adding another server from Settings → Servers. */
    data object AddProfile : SetupMode { override val key = "add" }

    /** The profile exists but its session ended; start at sign-in with the server prefilled. */
    data class Reauth(val profileId: String) : SetupMode { override val key = "reauth-$profileId" }
}

enum class SetupStep { Welcome, Server, SignIn }

data class ServerForm(
    val url: String = "",
    val name: String = "",
    val useCfAccess: Boolean = false,
    val cfClientId: String = "",
    val cfClientSecret: String = "",
)

data class ConnectionCheck(val form: ServerForm, val result: HealthResult)

sealed interface FormError {
    data class Url(val reason: ServerUrl.Reason) : FormError
    data object CfCredentialsMissing : FormError
}

sealed interface SignInError {
    data object MissingFields : SignInError
    data object InvalidCredentials : SignInError
    data class RateLimited(val retryAfterSeconds: Long?) : SignInError
    data class Problem(val problem: ServerProblem) : SignInError
}

data class SetupUiState(
    val mode: SetupMode? = null,
    /** True while a reauth profile is being read; the form isn't the saved server yet. */
    val loading: Boolean = false,
    val step: SetupStep = SetupStep.Welcome,
    val profileId: String? = null,
    val form: ServerForm = ServerForm(),
    val formError: FormError? = null,
    val testing: Boolean = false,
    val check: ConnectionCheck? = null,
    val username: String = "",
    val password: String = "",
    val deviceName: String = defaultDeviceName(),
    val signingIn: Boolean = false,
    val signInError: SignInError? = null,
    /** Other saved servers, offered on the sign-in screen when a session ended. */
    val otherProfiles: List<ServerProfile> = emptyList(),
) {
    /** The last successful test still matches what's in the form. */
    val verified: Boolean get() = check?.form == form && check.result is HealthResult.Ok

    val serverHost: String
        get() = (ServerUrl.parse(form.url) as? ServerUrl.Result.Valid)?.host ?: form.url

    val serverName: String get() = form.name.ifBlank { serverHost }

    /** Normalized URL without the scheme, e.g. `localhost:3111` or `garage.example.com`. */
    val serverAddress: String
        get() = (ServerUrl.parse(form.url) as? ServerUrl.Result.Valid)?.url?.substringAfter("://") ?: form.url

    val canGoBack: Boolean
        get() = when (step) {
            SetupStep.Welcome -> false
            SetupStep.Server -> mode == SetupMode.FirstRun || mode is SetupMode.Reauth
            SetupStep.SignIn -> mode !is SetupMode.Reauth
        }
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    fun start(mode: SetupMode) {
        if (_state.value.mode != null) return
        when (mode) {
            SetupMode.FirstRun -> _state.update { it.copy(mode = mode, step = SetupStep.Welcome) }
            SetupMode.AddProfile -> _state.update {
                it.copy(mode = mode, step = SetupStep.Server, form = ServerForm())
            }
            is SetupMode.Reauth -> {
                _state.update { it.copy(mode = mode, loading = true, step = SetupStep.SignIn, profileId = mode.profileId) }
                viewModelScope.launch { loadForReauth(mode.profileId) }
            }
        }
    }

    private suspend fun loadForReauth(profileId: String) {
        val all = profiles.current()
        val profile = all.profiles.firstOrNull { it.id == profileId } ?: return
        val cf = profiles.cfAccess(profileId)
        val form = ServerForm(
            url = profile.baseUrl,
            name = profile.name,
            useCfAccess = cf != null,
            cfClientId = cf?.clientId.orEmpty(),
            cfClientSecret = cf?.clientSecret.orEmpty(),
        )
        _state.update {
            it.copy(
                loading = false,
                form = form,
                // It worked before; sign-in reports any problem with the server itself.
                check = ConnectionCheck(form, HealthResult.Ok(profile.serverVersion, behindCloudflare = cf != null)),
                username = profile.lastUsername.orEmpty(),
                otherProfiles = all.profiles.filter { other -> other.id != profileId },
            )
        }
    }

    fun getStarted() = _state.update { it.copy(step = SetupStep.Server) }

    fun editServer() = _state.update { it.copy(step = SetupStep.Server) }

    /** Returns false when there's no earlier step, so the caller can leave the flow. */
    fun back(): Boolean {
        val current = _state.value
        if (!current.canGoBack) return false
        _state.update {
            it.copy(
                step = when (it.step) {
                    SetupStep.SignIn -> SetupStep.Server
                    SetupStep.Server -> if (it.mode is SetupMode.Reauth) SetupStep.SignIn else SetupStep.Welcome
                    SetupStep.Welcome -> SetupStep.Welcome
                },
                signInError = null,
            )
        }
        return true
    }

    fun updateForm(transform: (ServerForm) -> ServerForm) =
        _state.update { it.copy(form = transform(it.form), formError = null) }

    fun testConnection() {
        val current = _state.value
        val draft = draftOrError(current) ?: return
        _state.update { it.copy(testing = true, check = null) }
        viewModelScope.launch {
            val result = auth.testConnection(draft)
            _state.update { it.copy(testing = false, check = ConnectionCheck(current.form, result)) }
        }
    }

    fun continueToSignIn() {
        if (!_state.value.verified) return
        _state.update { it.copy(step = SetupStep.SignIn, signInError = null) }
    }

    fun updateUsername(value: String) = _state.update { it.copy(username = value, signInError = null) }

    fun updatePassword(value: String) = _state.update { it.copy(password = value, signInError = null) }

    fun updateDeviceName(value: String) = _state.update { it.copy(deviceName = value.take(80)) }

    fun signIn() {
        val current = _state.value
        if (current.signingIn || current.loading) return
        if (current.username.isBlank() || current.password.isEmpty()) {
            _state.update { it.copy(signInError = SignInError.MissingFields) }
            return
        }
        val draft = draftOrError(current) ?: run {
            _state.update { it.copy(step = SetupStep.Server) }
            return
        }
        _state.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch {
            val result = auth.signIn(
                draft = draft,
                username = current.username,
                password = current.password,
                deviceName = current.deviceName.ifBlank { defaultDeviceName() },
            )
            // On success the new session becomes active and AppRoot replaces this flow.
            _state.update {
                it.copy(
                    signingIn = false,
                    password = if (result is SignInResult.Success) "" else it.password,
                    signInError = when (result) {
                        is SignInResult.Success -> null
                        SignInResult.InvalidCredentials -> SignInError.InvalidCredentials
                        is SignInResult.RateLimited -> SignInError.RateLimited(result.retryAfterSeconds)
                        is SignInResult.Problem -> SignInError.Problem(result.problem)
                    },
                )
            }
        }
    }

    fun switchTo(profileId: String) = viewModelScope.launch { profiles.setActive(profileId) }

    fun removeThisServer() {
        val id = _state.value.profileId ?: return
        viewModelScope.launch { auth.removeProfile(id) }
    }

    private fun draftOrError(state: SetupUiState): ServerDraft? {
        val form = state.form
        val parsed = ServerUrl.parse(form.url)
        if (parsed is ServerUrl.Result.Invalid) {
            _state.update { it.copy(formError = FormError.Url(parsed.reason)) }
            return null
        }
        parsed as ServerUrl.Result.Valid
        val cf = if (form.useCfAccess) {
            if (form.cfClientId.isBlank() || form.cfClientSecret.isBlank()) {
                _state.update { it.copy(formError = FormError.CfCredentialsMissing) }
                return null
            }
            CfAccessCredentials(form.cfClientId.trim(), form.cfClientSecret.trim())
        } else {
            null
        }
        return ServerDraft(
            profileId = state.profileId,
            name = form.name.trim().ifBlank { parsed.host },
            baseUrl = parsed.url,
            cfAccess = cf,
        )
    }
}

private fun defaultDeviceName(): String {
    val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
    val model = Build.MODEL.orEmpty()
    val name = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    return name.trim().ifBlank { "Android" }.take(80)
}

package io.github.conchad.smartgarage.core.data

import io.github.conchad.smartgarage.core.network.ApiClient
import io.github.conchad.smartgarage.core.network.ApiResult
import io.github.conchad.smartgarage.core.network.HealthResult
import io.github.conchad.smartgarage.core.network.LoginRequest
import io.github.conchad.smartgarage.core.network.MeResponse
import io.github.conchad.smartgarage.core.network.ServerConnection
import io.github.conchad.smartgarage.core.network.ServerProblem
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SignInResult {
    data class Success(val profileId: String) : SignInResult
    data object InvalidCredentials : SignInResult
    data class RateLimited(val retryAfterSeconds: Long?) : SignInResult
    data class Problem(val problem: ServerProblem) : SignInResult
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiClient,
    private val profiles: ProfileStore,
) {
    suspend fun testConnection(draft: ServerDraft): HealthResult =
        api.health(ServerConnection(baseUrl = draft.baseUrl, cfAccess = draft.cfAccess))

    /**
     * Exchanges credentials for a device token, then saves the profile and makes it active. A profile is only
     * written once sign-in works, so an abandoned setup leaves nothing behind.
     */
    suspend fun signIn(draft: ServerDraft, username: String, password: String, deviceName: String): SignInResult {
        val connection = ServerConnection(baseUrl = draft.baseUrl, cfAccess = draft.cfAccess)
        val login = when (val result = api.login(connection, LoginRequest(username.trim(), password, deviceName.trim()))) {
            is ApiResult.Ok -> result.value
            is ApiResult.HttpError -> return when (result.status) {
                401 -> SignInResult.InvalidCredentials
                429 -> SignInResult.RateLimited(result.retryAfterSeconds)
                // 400 means our request was malformed; 404 means a Smart Garage without the mobile API.
                404 -> SignInResult.Problem(ServerProblem.UnsupportedApi(null))
                else -> SignInResult.Problem(ServerProblem.Unexpected(result.status, result.message))
            }
            is ApiResult.Failed -> return SignInResult.Problem(result.problem)
        }

        val id = draft.profileId ?: UUID.randomUUID().toString()
        // Replacing a still-valid token (e.g. signing in as someone else): revoke the old device server-side.
        draft.profileId?.let { revokeQuietly(it) }

        val me = (api.me(connection.copy(token = login.token)) as? ApiResult.Ok)?.value
        profiles.saveSignedIn(
            draft = draft,
            id = id,
            token = login.token,
            account = Account(
                userId = login.user.id,
                username = login.user.username,
                displayName = login.user.displayName,
                role = login.user.role,
                deviceId = login.device.id,
                deviceName = login.device.name,
            ),
            serverVersion = me?.server?.version,
        )
        me?.let { applyMe(id, it) }
        return SignInResult.Success(id)
    }

    /**
     * Refreshes the active profile's user and display preferences. A 401 means the token was revoked (for example
     * under Settings → Mobile devices on the web), so the session is cleared and the app asks to sign in again.
     * Being offline changes nothing.
     */
    suspend fun refresh(profileId: String) {
        val connection = profiles.connection(profileId) ?: return
        if (connection.token == null) {
            // Account recorded but the token can't be decrypted (Keystore key lost): same as signed out.
            profiles.clearSession(profileId)
            return
        }
        when (val result = api.me(connection)) {
            is ApiResult.Ok -> applyMe(profileId, result.value)
            is ApiResult.HttpError -> if (result.status == 401) profiles.clearSession(profileId)
            is ApiResult.Failed -> Unit
        }
    }

    /** Revokes this device's token on the server when reachable; the local session is cleared either way. */
    suspend fun signOut(profileId: String) {
        revokeQuietly(profileId)
        profiles.clearSession(profileId)
    }

    suspend fun removeProfile(profileId: String) {
        revokeQuietly(profileId)
        profiles.remove(profileId)
    }

    private suspend fun revokeQuietly(profileId: String) {
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return
        withTimeoutOrNull(REVOKE_TIMEOUT_MS) { api.logout(connection) }
    }

    private suspend fun applyMe(profileId: String, me: MeResponse) {
        profiles.update(profileId) { profile ->
            profile.copy(
                account = profile.account?.copy(
                    username = me.user.username,
                    displayName = me.user.displayName,
                    role = me.user.role,
                ),
                preferences = me.preferences?.let {
                    DisplayPreferences(
                        locale = it.locale,
                        timezone = it.timezone,
                        currency = it.currency,
                        distanceUnit = it.distanceUnit,
                        volumeUnit = it.volumeUnit,
                    )
                } ?: profile.preferences,
                serverVersion = me.server?.version ?: profile.serverVersion,
            )
        }
    }

    private companion object {
        const val REVOKE_TIMEOUT_MS = 5_000L
    }
}

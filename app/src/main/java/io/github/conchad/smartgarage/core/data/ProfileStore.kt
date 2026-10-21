package io.github.conchad.smartgarage.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.network.CfAccessCredentials
import io.github.conchad.smartgarage.core.network.ServerConnection
import io.github.conchad.smartgarage.core.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

/** Values the setup screen collects for a server before signing in. */
data class ServerDraft(
    val profileId: String?,
    val name: String,
    val baseUrl: String,
    val cfAccess: CfAccessCredentials?,
)

/**
 * Server profiles in DataStore. The profile list is plain JSON; the device token and Cloudflare credentials are
 * stored per profile under separate keys, encrypted with [SecretCipher].
 */
@Singleton
class ProfileStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    val state: Flow<ProfilesState> = dataStore.data
        .map { prefs ->
            val profiles = prefs.profiles()
            ProfilesState(
                profiles = profiles,
                activeId = prefs[ACTIVE_ID],
                signedInIds = profiles
                    .filter { it.account != null && prefs[secretKey(it.id, Secret.Token)] != null }
                    .mapTo(mutableSetOf()) { it.id },
            )
        }
        .distinctUntilChanged()

    suspend fun current(): ProfilesState = state.first()

    suspend fun profile(id: String): ServerProfile? = current().profiles.firstOrNull { it.id == id }

    /** Decrypts what's needed to call the server as this profile; the token is null when signed out. */
    suspend fun connection(id: String): ServerConnection? {
        val prefs = dataStore.data.first()
        val profile = prefs.profiles().firstOrNull { it.id == id } ?: return null
        return ServerConnection(
            baseUrl = profile.baseUrl,
            cfAccess = cfAccess(prefs, id),
            token = prefs[secretKey(id, Secret.Token)]?.let(cipher::decrypt),
        )
    }

    suspend fun cfAccess(id: String): CfAccessCredentials? = cfAccess(dataStore.data.first(), id)

    /** Creates or updates the profile, stores its secrets, and makes it the active profile. */
    suspend fun saveSignedIn(draft: ServerDraft, id: String, token: String, account: Account, serverVersion: String?) {
        dataStore.edit { prefs ->
            val profiles = prefs.profiles()
            val existing = profiles.firstOrNull { it.id == id }
            val updated = (existing ?: ServerProfile(id = id, name = draft.name, baseUrl = draft.baseUrl)).copy(
                name = draft.name,
                baseUrl = draft.baseUrl,
                usesCloudflareAccess = draft.cfAccess != null,
                account = account,
                lastUsername = account.username,
                serverVersion = serverVersion ?: existing?.serverVersion,
            )
            prefs.setProfiles(if (existing == null) profiles + updated else profiles.map { if (it.id == id) updated else it })
            prefs.putSecret(id, Secret.Token, token)
            if (draft.cfAccess != null) {
                prefs.putSecret(id, Secret.CfClientId, draft.cfAccess.clientId)
                prefs.putSecret(id, Secret.CfClientSecret, draft.cfAccess.clientSecret)
            } else {
                prefs.remove(secretKey(id, Secret.CfClientId))
                prefs.remove(secretKey(id, Secret.CfClientSecret))
            }
            prefs[ACTIVE_ID] = id
        }
    }

    suspend fun update(id: String, transform: (ServerProfile) -> ServerProfile) {
        dataStore.edit { prefs ->
            prefs.setProfiles(prefs.profiles().map { if (it.id == id) transform(it) else it })
        }
    }

    /** Forgets the device token and account but keeps the server settings, so signing in again is quick. */
    suspend fun clearSession(id: String) {
        dataStore.edit { prefs ->
            prefs.setProfiles(prefs.profiles().map { if (it.id == id) it.copy(account = null) else it })
            prefs.remove(secretKey(id, Secret.Token))
        }
    }

    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            val remaining = prefs.profiles().filterNot { it.id == id }
            prefs.setProfiles(remaining)
            Secret.entries.forEach { prefs.remove(secretKey(id, it)) }
            if (prefs[ACTIVE_ID] == id) {
                remaining.firstOrNull()?.let { prefs[ACTIVE_ID] = it.id } ?: prefs.remove(ACTIVE_ID)
            }
        }
    }

    suspend fun setActive(id: String) {
        dataStore.edit { prefs -> if (prefs.profiles().any { it.id == id }) prefs[ACTIVE_ID] = id }
    }

    private fun cfAccess(prefs: Preferences, id: String): CfAccessCredentials? {
        val clientId = prefs[secretKey(id, Secret.CfClientId)]?.let(cipher::decrypt) ?: return null
        val clientSecret = prefs[secretKey(id, Secret.CfClientSecret)]?.let(cipher::decrypt) ?: return null
        return CfAccessCredentials(clientId, clientSecret)
    }

    private fun Preferences.profiles(): List<ServerProfile> =
        this[PROFILES]?.let { runCatching { ApiJson.decodeFromString(PROFILE_LIST, it) }.getOrNull() }.orEmpty()

    private fun MutablePreferences.setProfiles(profiles: List<ServerProfile>) {
        this[PROFILES] = ApiJson.encodeToString(PROFILE_LIST, profiles)
    }

    private fun MutablePreferences.putSecret(id: String, secret: Secret, value: String) {
        this[secretKey(id, secret)] = cipher.encrypt(value)
    }

    private enum class Secret(val key: String) {
        Token("token"),
        CfClientId("cf_client_id"),
        CfClientSecret("cf_client_secret"),
    }

    private companion object {
        val PROFILES = stringPreferencesKey("profiles")
        val ACTIVE_ID = stringPreferencesKey("active_profile_id")
        val PROFILE_LIST = ListSerializer(ServerProfile.serializer())

        fun secretKey(id: String, secret: Secret) = stringPreferencesKey("secret.$id.${secret.key}")
    }
}

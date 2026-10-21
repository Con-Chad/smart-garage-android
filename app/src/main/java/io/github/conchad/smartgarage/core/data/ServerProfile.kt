package io.github.conchad.smartgarage.core.data

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * One server the app can talk to (D-A02). Profiles are independent: a LAN profile and a tunnel profile for the same
 * server each sign in and get their own device token. Secrets live encrypted next to this, never in it.
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val usesCloudflareAccess: Boolean = false,
    val account: Account? = null,
    /** Prefilled on the sign-in screen after a sign-out or a revoked token. */
    val lastUsername: String? = null,
    val preferences: DisplayPreferences? = null,
    val serverVersion: String? = null,
) {
    /** Host (and port) for compact display. */
    val hostLabel: String
        get() = runCatching { URI(baseUrl).let { if (it.port == -1) it.host else "${it.host}:${it.port}" } }
            .getOrNull() ?: baseUrl
}

@Serializable
data class Account(
    val userId: String,
    val username: String,
    val displayName: String? = null,
    val role: String? = null,
    val deviceId: String,
    val deviceName: String,
) {
    val greetingName: String get() = displayName?.takeIf { it.isNotBlank() } ?: username
}

/** How the server wants values shown. Stored values are always km, liters and cents. */
@Serializable
data class DisplayPreferences(
    val locale: String? = null,
    val timezone: String? = null,
    val currency: String? = null,
    val distanceUnit: String? = null,
    val volumeUnit: String? = null,
)

data class ProfilesState(
    val profiles: List<ServerProfile>,
    val activeId: String?,
    /** Profiles with both an account and a stored device token. */
    val signedInIds: Set<String>,
) {
    val active: ServerProfile? get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()

    fun isSignedIn(id: String) = id in signedInIds
}

package io.github.conchad.smartgarage.core.data

import io.github.conchad.smartgarage.core.network.RECEIPT_KIND_FUEL
import io.github.conchad.smartgarage.core.network.RECEIPT_KIND_SERVICE
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
    /** What `/me` said this server can do; refreshed with every `/me` call (D-A27). */
    val features: ServerFeatures = ServerFeatures(),
    /**
     * Lets the hourly worker sync a plain-http profile (D-A28). Off by default: an http address names a box on the
     * home network, and on any other network that name or IP may answer as someone else, who would then receive the
     * device token in clear text. Only meaningful when [isCleartext]; foreground syncs are never affected.
     */
    val backgroundSyncOnCleartext: Boolean = false,
    /** Push notification state for this profile (D-A29); the subscription keys live encrypted next to the token. */
    val push: PushSettings = PushSettings(),
    /**
     * The name the Home greeting and the avatar use on this phone (D-A44), set in Settings. Device-local, never sent
     * to the server; blank or null falls back to the account's display name, then its username.
     */
    val localName: String? = null,
) {
    /** Whether requests to this server travel unencrypted (`http://`, only allowed for private hosts, D-A04). */
    val isCleartext: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    /** Whether the hourly background sync may touch this profile (D-A28). */
    val syncsInBackground: Boolean get() = !isCleartext || backgroundSyncOnCleartext

    /**
     * Whether push may be turned on (D-A29): a push wakes the app to sync while the user isn't looking, which is
     * exactly what D-A28 keeps plain-http profiles from doing unless the user opted in.
     */
    val pushAllowed: Boolean get() = syncsInBackground

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

/** This account as the UI shows it (D-A44): [name] (the profile's [ServerProfile.localName]) as its display name when set. */
fun Account.withLocalName(name: String?): Account = name?.trim()?.takeIf { it.isNotEmpty() }?.let { copy(displayName = it) } ?: this

/** Optional features of a server, from `/me.features`; false for servers that don't report them (D-A27). */
@Serializable
data class ServerFeatures(
    /** The server has a receipt scanner at all. */
    val receiptScanner: Boolean = false,
    /**
     * Which scans it offers (D-A53): `fuel`, `service`. Null for profiles stored before D-A53, which knew only the
     * fuel scan; [scansFuel] treats that as fuel-only.
     */
    val receiptScannerKinds: List<String>? = null,
) {
    /** Shows "Scan receipt" on the fill-up form. */
    val scansFuel: Boolean get() = receiptScanner && (receiptScannerKinds?.contains(RECEIPT_KIND_FUEL) ?: true)

    /** Shows "Scan receipt" on the service-record form. */
    val scansService: Boolean get() = receiptScanner && receiptScannerKinds?.contains(RECEIPT_KIND_SERVICE) == true
}

/**
 * Push state of one profile (D-A29). Every field has a default so profiles stored before CP12 keep parsing.
 * The UnifiedPush `instance` is the profile id; the distributor is shared by all profiles (the connector keeps one).
 */
@Serializable
data class PushSettings(
    /** The user turned push on; registration with the distributor and the server follows asynchronously. */
    val enabled: Boolean = false,
    /** When the server last accepted this device's subscription (ISO instant); null until the first PUT succeeded. */
    val registeredAt: String? = null,
    /** Package name of the UnifiedPush distributor that was saved when push was turned on. */
    val distributor: String? = null,
    /** The server's VAPID public key, fetched once and compared on refresh (PUSH.md §1). */
    val vapidKey: String? = null,
    /** Why the last step didn't complete, for Settings; null while everything is fine. */
    val problem: PushProblem? = null,
    /** The server's message for [PushProblem.EndpointRejected]; nothing sensitive, shown as-is. */
    val problemDetail: String? = null,
) {
    /** Registered and healthy: the server has a current subscription. */
    val registered: Boolean get() = enabled && registeredAt != null && problem == null
}

/** Where push registration got stuck. Stored as names, so adding a case later never breaks parsing of old ones. */
@Serializable
enum class PushProblem {
    /** No UnifiedPush distributor is installed, or the saved one was removed. */
    NoDistributor,
    /** The distributor answered the registration with a failure (internal, or it needs a VAPID key we sent). */
    DistributorFailed,
    /** The distributor had no network; it retries when the network is back. */
    NetworkRequired,
    /** The distributor wants the user to do something in its own app first (e.g. log in). */
    ActionRequired,
    /** The distributor delivered an endpoint without Web Push keys; the server can't encrypt for it. */
    NoKeys,
    /** The server refused the endpoint URL (`endpoint_rejected`): see [PushSettings.problemDetail]. */
    EndpointRejected,
    /** The server answered the registration with another error. */
    ServerRejected,
    /** The server couldn't be reached; the registration is retried on the next app start. */
    Unreachable,
    /** The distributor dropped the registration (`onUnregistered`); push is re-requested on the next start. */
    Unregistered,
}

/** What the server needs to encrypt for this device; stored encrypted (ProfileStore), never logged. */
@Serializable
data class PushSubscription(val endpoint: String, val p256dh: String, val auth: String) {
    override fun toString() = "PushSubscription(***)"
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

package io.github.conchad.smartgarage.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The only `/api/v1` major version this app understands (the server's `API_VERSION`). */
const val SUPPORTED_API_VERSION = 1

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class HealthResponse(
    val service: String? = null,
    val status: String? = null,
    val version: String? = null,
    val apiVersion: Int? = null,
    val database: String? = null,
)

@Serializable
data class ApiErrorBody(val error: ApiErrorDetail? = null)

@Serializable
data class ApiErrorDetail(val code: String? = null, val message: String? = null)

@Serializable
data class LoginRequest(val username: String, val password: String, val deviceName: String)

@Serializable
data class LoginResponse(
    val token: String,
    val device: DeviceDto,
    val user: UserDto,
    val apiVersion: Int? = null,
)

@Serializable
data class DeviceDto(val id: String, val name: String)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String? = null,
    val role: String? = null,
)

@Serializable
data class MeResponse(
    val user: UserDto,
    val device: DeviceDto? = null,
    val preferences: PreferencesDto? = null,
    val server: ServerInfoDto? = null,
    /** Absent on servers older than the receipt scanner (D-A27); every flag then reads false. */
    val features: MeFeatures = MeFeatures(),
)

/** Optional server features `/me` announces; a missing key means off. */
@Serializable
data class MeFeatures(
    /** `POST /api/v1/fuel/receipt-scan` is enabled and the server has a model key (docs/mobile/RECEIPT-SCANNER.md). */
    val receiptScanner: Boolean = false,
)

/** Display preferences; every value the API returns stays metric (km, liters, cents). */
@Serializable
data class PreferencesDto(
    val locale: String? = null,
    val timezone: String? = null,
    val currency: String? = null,
    val distanceUnit: String? = null,
    val volumeUnit: String? = null,
)

@Serializable
data class ServerInfoDto(val version: String? = null, val apiVersion: Int? = null)

/** A `/sync` page (server `lib/api/v1/sync.ts`). `changes` stay raw JSON; the cache stores them as-is. */
@Serializable
data class SyncPageDto(
    val serverTime: String,
    val fullResync: Boolean = false,
    val hasMore: Boolean = false,
    val cursor: String? = null,
    val nextSince: String? = null,
    val changes: Map<String, List<JsonObject>> = emptyMap(),
    val deletions: Map<String, List<String>> = emptyMap(),
    /** First page only. */
    val preferences: PreferencesDto? = null,
    val noteTags: JsonElement? = null,
)

/** `GET /api/v1/push/vapid`: the server's VAPID public key (docs/mobile/PUSH.md §1). */
@Serializable
data class VapidKeyResponse(val publicKey: String, val algorithm: String? = null, val curve: String? = null)

/** Body of `PUT /api/v1/devices/{id}/push`: the Web Push endpoint and the subscription's public keys. */
@Serializable
data class PushSubscriptionRequest(val endpoint: String, val keys: PushKeysDto) {
    override fun toString() = "PushSubscriptionRequest(endpoint=***, keys=***)"
}

/** `p256dh` is the uncompressed P-256 point, `auth` the 16-byte secret; both base64url without padding. */
@Serializable
data class PushKeysDto(val p256dh: String, val auth: String) {
    override fun toString() = "PushKeysDto(***)"
}

@Serializable
data class PushRegistrationResponse(val push: PushInfoDto? = null)

@Serializable
data class PushInfoDto(
    val deviceId: String? = null,
    val createdAt: String? = null,
    val failedAt: String? = null,
    val failureCount: Int? = null,
)

@Serializable
data class PushTestResponse(val ok: Boolean = false, val deviceId: String? = null)

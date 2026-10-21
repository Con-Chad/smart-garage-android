package io.github.conchad.smartgarage.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

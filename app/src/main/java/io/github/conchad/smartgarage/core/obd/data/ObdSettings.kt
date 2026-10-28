package io.github.conchad.smartgarage.core.obd.data

import io.github.conchad.smartgarage.core.obd.CodesClearedAnchor
import io.github.conchad.smartgarage.core.obd.OdometerProfile
import io.github.conchad.smartgarage.core.obd.OdometerProfiles
import io.github.conchad.smartgarage.core.obd.UploadPolicy
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** The debug simulator's car (D-A80): how fast it drives and how much faster than real time its clock runs. */
@Serializable
data class SimulatorSettings(
    val speedKmh: Double = 60.0,
    /** 60 = one real minute is one simulated hour, so 60 km/h covers a km a second. */
    val timeScale: Double = 60.0,
    val startOdometerKm: Double = 84_210.0,
)

/**
 * Everything the OBD feature keeps for one server profile (D-A80), stored as one JSON value in the "obd" DataStore.
 * Unknown keys are ignored and missing ones take these defaults, so adding a field never needs a migration.
 */
@Serializable
data class ObdSettings(
    val adapter: ObdAdapter? = null,
    val vehicleId: String? = null,
    /** A built-in is stored by kind and re-resolved ([OdometerProfile.resolved]); a custom one as written. */
    val profile: OdometerProfile = OdometerProfiles.DEFAULT,
    val pollMinutes: Int = DEFAULT_POLL_MINUTES,
    /** In the user's display unit (mi or km, from the server's preferences). */
    val minUploadDistance: Double = UploadPolicy.DEFAULT_MIN_DISTANCE,
    val uploadOnTripEnd: Boolean = true,
    /** Off: readings only go to the server on an unmetered network (Wi-Fi), D-A80. */
    val cellularUploads: Boolean = true,
    /** The codes-cleared profile's chain (CODES_CLEARED only); reset with the vehicle. */
    val anchor: CodesClearedAnchor? = null,
    /** Epoch ms of the last sync that carried an adapter reading to the server. */
    val lastSyncAt: Long? = null,
    /** The last km chosen for upload for [vehicleId] (queued for the server); null = nothing yet. */
    val lastSentKm: Int? = null,
    val simulator: SimulatorSettings = SimulatorSettings(),
) {
    val pollInterval: Duration get() = pollMinutes.coerceIn(MIN_POLL_MINUTES, MAX_POLL_MINUTES).minutes

    fun policy(displayMiles: Boolean) = UploadPolicy(minUploadDistance, displayMiles, uploadOnTripEnd)

    /** Another vehicle starts from scratch: what was sent and the codes-cleared chain belonged to the old one. */
    fun withVehicle(id: String?): ObdSettings =
        if (id == vehicleId) this else copy(vehicleId = id, lastSentKm = null, anchor = null)

    /** A different profile kind invalidates the codes-cleared chain. */
    fun withProfile(profile: OdometerProfile): ObdSettings =
        copy(profile = profile, anchor = if (profile.kind == this.profile.kind) anchor else null)

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_POLL_MINUTES = 5
        const val MIN_POLL_MINUTES = 1
        const val MAX_POLL_MINUTES = 60

        private val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        /** Unreadable text (a downgrade, a corrupt file) falls back to the defaults rather than crashing. */
        fun decode(text: String?): ObdSettings =
            text?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() } ?: ObdSettings()
    }
}

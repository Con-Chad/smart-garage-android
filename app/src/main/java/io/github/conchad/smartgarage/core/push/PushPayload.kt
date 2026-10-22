package io.github.conchad.smartgarage.core.push

import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.network.ServerIds
import kotlinx.serialization.Serializable

/**
 * What one push message says (docs/mobile/PUSH.md §4), after the connector decrypted it. The server promises the
 * payload never carries tokens or credentials; the app still treats it as untrusted input: ids go through
 * [ServerIds] before they are used for navigation and `url` must be a plain site-relative path.
 */
@Serializable
data class PushPayload(
    val type: String,
    val title: String,
    val body: String,
    /** Only when every item is about one vehicle; dropped when it isn't a usable server id. */
    val vehicleId: String? = null,
    /** Only when there is exactly one item; dropped when it isn't a usable server id. */
    val scheduleId: String? = null,
    /** The web app's path for this alert (`/maintenance`, `/odometer`, …); null when it isn't a plain path. */
    val url: String? = null,
) {
    companion object {
        /** Site-relative paths only: no scheme, no host, no query, nothing a browser could be tricked with. */
        private val webPath = Regex("/[A-Za-z0-9/_-]{0,128}")

        /**
         * Parses the decrypted bytes. Null when they aren't the contract's JSON or lack title/body, in which case the
         * caller still syncs (something changed server-side) but shows nothing. Unknown `type` values are kept: the
         * contract says to display them as a generic alert.
         */
        fun parse(bytes: ByteArray): PushPayload? {
            val raw = runCatching { ApiJson.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return null
            if (raw.title.isBlank() || raw.body.isBlank()) return null
            return raw.copy(
                type = raw.type.ifBlank { "alert" },
                vehicleId = raw.vehicleId?.takeIf(ServerIds::isValid),
                scheduleId = raw.scheduleId?.takeIf(ServerIds::isValid),
                url = raw.url?.takeIf(webPath::matches),
            )
        }
    }
}

/**
 * Where a tapped notification takes the user: the profile the message was for, then the vehicle or schedule when the
 * payload named one, else the section behind `url`, else the dashboard. Ids are already validated by [PushPayload].
 */
data class PushTarget(
    val profileId: String,
    val vehicleId: String? = null,
    val scheduleId: String? = null,
    val url: String? = null,
) {
    companion object {
        fun of(profileId: String, payload: PushPayload) = PushTarget(profileId, payload.vehicleId, payload.scheduleId, payload.url)

        /** Rebuilds a target from loose strings (an Intent's extras); anything that fails validation is dropped. */
        fun validated(profileId: String?, vehicleId: String?, scheduleId: String?, url: String?): PushTarget? {
            if (profileId == null || !ServerIds.isValid(profileId)) return null
            return PushTarget(
                profileId = profileId,
                vehicleId = vehicleId?.takeIf(ServerIds::isValid),
                scheduleId = scheduleId?.takeIf(ServerIds::isValid),
                url = url?.takeIf { Regex("/[A-Za-z0-9/_-]{0,128}").matches(it) },
            )
        }
    }
}

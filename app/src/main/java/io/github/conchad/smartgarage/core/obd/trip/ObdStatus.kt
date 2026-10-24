package io.github.conchad.smartgarage.core.obd.trip

import io.github.conchad.smartgarage.core.obd.link.CloseReason

/** What the OBD feature is doing, for the settings screen and the notification. */
sealed interface ObdStatus {
    data object Disconnected : ObdStatus

    /** The BLE scanner for picking an adapter is running. */
    data object Scanning : ObdStatus

    /** [waiting]: standing by until the adapter shows up (car started), however long that takes. */
    data class Connecting(val adapterName: String?, val waiting: Boolean) : ObdStatus

    /** Android's pairing dialog: WiCAN wants its passkey (123456 unless changed in its settings). */
    data class Pairing(val adapterName: String?) : ObdStatus

    data class Connected(
        val adapterName: String?,
        /** What ATI answered, e.g. "ELM327 v2.3". */
        val identity: String?,
        val lastKm: Int? = null,
        val lastReadAt: Long? = null,
    ) : ObdStatus

    data class Reading(val adapterName: String?, val lastKm: Int?) : ObdStatus

    data class Error(val reason: ObdError, val detail: String? = null) : ObdStatus
}

enum class ObdError {
    /** No adapter chosen for this profile. */
    NO_ADAPTER,

    /** No vehicle chosen to upload readings to. */
    NO_VEHICLE,
    BLUETOOTH_OFF,
    BLUETOOTH_UNAVAILABLE,
    PERMISSION_MISSING,
    PAIRING_FAILED,
    CONNECT_FAILED,

    /** Connected, but it has no ELM327 serial service. */
    NO_UART,

    /** Something answered, but not like an ELM327 / STN. */
    NOT_ELM,

    /** The adapter didn't answer ATZ / ATI. */
    NO_REPLY,

    /** The "Simulated adapter" in a release build (never offered there). */
    TRANSPORT_UNAVAILABLE,

    /** Codes-cleared profile without a manual odometer reading to start from. */
    NO_MANUAL_READING,
    ;

    companion object {
        fun of(reason: CloseReason): ObdError? = when (reason) {
            CloseReason.PAIRING_FAILED -> PAIRING_FAILED
            CloseReason.NO_UART -> NO_UART
            CloseReason.CONNECT_FAILED -> CONNECT_FAILED
            CloseReason.BLUETOOTH_OFF -> BLUETOOTH_OFF
            CloseReason.PERMISSION -> PERMISSION_MISSING
            CloseReason.DISCONNECTED, CloseReason.CLOSED -> null
        }
    }
}

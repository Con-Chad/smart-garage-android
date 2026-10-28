package io.github.conchad.smartgarage.core.obd.link

import io.github.conchad.smartgarage.core.obd.ElmLink
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ObdTransportKind {
    @SerialName("ble") BLE,

    /** Debug builds only (D-A79/D-A80): [io.github.conchad.smartgarage.core.obd.SimulatedElmLink], no car needed. */
    @SerialName("simulated") SIMULATED,
}

/** The adapter the user chose, as stored per server profile. */
@Serializable
data class ObdAdapter(
    /** Bluetooth MAC ("AA:BB:CC:DD:EE:FF"); [SIMULATED_ADDRESS] for the simulator. */
    val address: String,
    val name: String? = null,
    /** Associated through CompanionDeviceManager; false = the autoConnect fallback (D-A80). */
    val associated: Boolean = false,
    /** The association's id (API 33+; Android 12 only reports the MAC). */
    val associationId: Int? = null,
    val transport: ObdTransportKind = ObdTransportKind.BLE,
) {
    /** The system tells us when it is near (CompanionDeviceService); otherwise a service waits for it. */
    val usesPresence: Boolean get() = associated && transport == ObdTransportKind.BLE

    companion object {
        const val SIMULATED_ADDRESS = "SIMULATED"
        fun simulated(name: String = "Simulated adapter") = ObdAdapter(SIMULATED_ADDRESS, name, transport = ObdTransportKind.SIMULATED)
    }
}

sealed interface LinkState {
    data object Connecting : LinkState

    /** Android's pairing dialog is (or should be) up: WiCAN asks for its passkey, 123456 unless changed. */
    data object Pairing : LinkState

    /** Notifications on, ready for [ElmLink.write]. */
    data object Ready : LinkState

    data class Closed(val reason: CloseReason, val detail: String? = null) : LinkState
}

enum class CloseReason {
    /** The adapter went away (car off, out of range) or dropped the connection. */
    DISCONNECTED,

    /** Bonding was refused or the passkey was wrong. */
    PAIRING_FAILED,

    /** Connected, but no service with a notify and a write characteristic: not an ELM327 serial adapter. */
    NO_UART,

    /** Couldn't connect (device not found in time, GATT error). */
    CONNECT_FAILED,
    BLUETOOTH_OFF,

    /** BLUETOOTH_CONNECT isn't granted. */
    PERMISSION,

    /** [AdapterConnection.close] was called. */
    CLOSED,
}

/**
 * One connection attempt to an adapter. [link]'s `incoming` flow completes once [state] is [LinkState.Closed], so an
 * [io.github.conchad.smartgarage.core.obd.ElmSession] on it answers DISCONNECTED from then on. Not reusable: a new
 * attempt is a new connection.
 */
interface AdapterConnection : AutoCloseable {
    val state: StateFlow<LinkState>
    val link: ElmLink
}

fun interface AdapterTransport {
    /**
     * Starts connecting at once. [waitForDevice] = wait however long it takes for the adapter to show up (BLE
     * autoConnect) instead of failing after a short timeout.
     */
    fun open(adapter: ObdAdapter, waitForDevice: Boolean): AdapterConnection
}

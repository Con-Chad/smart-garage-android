package io.github.conchad.smartgarage.core.obd

import kotlinx.coroutines.flow.Flow

/**
 * The byte pipe to an ELM327-compatible adapter that a transport (BLE GATT, or the debug simulator) provides.
 * [ElmSession] owns the protocol on top: commands, prompts, retries. Pure Kotlin, so the session unit-tests on the JVM.
 */
interface ElmLink {
    /** Bytes as the adapter sends them; a reply usually arrives split over many notifications. */
    val incoming: Flow<ByteArray>

    /** The most bytes one [write] may carry; the session chunks commands to this. */
    val maxWriteBytes: Int get() = DEFAULT_MAX_WRITE_BYTES

    suspend fun write(bytes: ByteArray)

    companion object {
        /** The WiCAN Pro's write characteristic takes at most 20 bytes per write (the BLE 4 default ATT payload). */
        const val DEFAULT_MAX_WRITE_BYTES = 20
    }
}

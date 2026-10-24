package io.github.conchad.smartgarage.core.obd.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.conchad.smartgarage.core.obd.ElmLink
import io.github.conchad.smartgarage.core.obd.link.AdapterConnection
import io.github.conchad.smartgarage.core.obd.link.AdapterTransport
import io.github.conchad.smartgarage.core.obd.link.CloseReason
import io.github.conchad.smartgarage.core.obd.link.LinkState
import io.github.conchad.smartgarage.core.obd.link.ObdAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** Opens [BleElmLink]s by MAC address. Bluetooth off or missing gives a connection that is closed from the start. */
class BleTransport(private val context: Context, private val log: (String) -> Unit) : AdapterTransport {
    override fun open(adapter: ObdAdapter, waitForDevice: Boolean): AdapterConnection {
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return ClosedConnection(CloseReason.BLUETOOTH_OFF, "no Bluetooth on this device")
        if (!bluetooth.isEnabled) return ClosedConnection(CloseReason.BLUETOOTH_OFF, null)
        if (!BluetoothAdapter.checkBluetoothAddress(adapter.address)) {
            return ClosedConnection(CloseReason.CONNECT_FAILED, "not a Bluetooth address: ${adapter.address}")
        }
        return BleElmLink.open(context, bluetooth.getRemoteDevice(adapter.address), autoConnect = waitForDevice, log)
    }
}

/** A connection that never was. */
internal class ClosedConnection(reason: CloseReason, detail: String?) : AdapterConnection, ElmLink {
    override val state: StateFlow<LinkState> = MutableStateFlow(LinkState.Closed(reason, detail))
    override val link: ElmLink get() = this
    override val incoming = emptyFlow<ByteArray>()
    override suspend fun write(bytes: ByteArray) = Unit
    override fun close() = Unit
}

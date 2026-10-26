package io.github.conchad.smartgarage.core.obd.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.conchad.smartgarage.core.obd.ElmLink
import io.github.conchad.smartgarage.core.obd.link.AdapterConnection
import io.github.conchad.smartgarage.core.obd.link.CloseReason
import io.github.conchad.smartgarage.core.obd.link.LinkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An ELM327 over BLE GATT (D-A73): connects, asks for a large MTU, discovers services, picks the serial pipe
 * ([ElmGattProfiles]), turns its notifications on and then carries bytes both ways. Android's GATT client allows one
 * operation at a time, so every operation goes through [op] (a mutex plus the callback's answer).
 *
 * Bonding: WiCAN wants an authenticated (passkey) bond, OBDLink CX only accepts one within five minutes of power-on.
 * When an operation is refused for missing authentication or encryption the bond is started (Android shows its own
 * pairing dialog; WiCAN's passkey defaults to 123456) and the state reads [LinkState.Pairing] until it is done; a bond
 * that ends in "none" closes the link with [CloseReason.PAIRING_FAILED].
 *
 * Writes are at most [maxWriteBytes]: the negotiated MTU minus 3, capped at [MAX_WRITE_BYTES]. WiCAN Pro's write
 * characteristic takes 20 bytes whatever the MTU, and an ELM command is shorter than that anyway.
 */
@SuppressLint("MissingPermission") // Checked in open(): without BLUETOOTH_CONNECT the link closes with PERMISSION.
class BleElmLink private constructor(
    private val context: Context,
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : AdapterConnection, ElmLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<LinkState>(LinkState.Connecting)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    override val link: ElmLink get() = this

    private val received = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = received.receiveAsFlow()

    @Volatile private var mtu = DEFAULT_MTU
    override val maxWriteBytes: Int get() = (mtu - ATT_HEADER).coerceIn(1, MAX_WRITE_BYTES)

    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    private val connected = CompletableDeferred<Unit>()
    private val bondState = MutableStateFlow(BluetoothDevice.BOND_NONE)

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var uart: ElmUart? = null
    @Volatile private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var writeChar: BluetoothGattCharacteristic? = null
    @Volatile private var everReady = false

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val changed = intent.bluetoothDevice() ?: return
            if (changed.address != device.address) return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            bondState.value = state
            when (state) {
                BluetoothDevice.BOND_BONDING -> if (!everReady) _state.value = LinkState.Pairing
                BluetoothDevice.BOND_BONDED -> log("Bonded with ${device.address}")
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> connected.complete(Unit)
                BluetoothProfile.STATE_DISCONNECTED ->
                    close(if (everReady) CloseReason.DISCONNECTED else CloseReason.CONNECT_FAILED, "GATT status $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@BleElmLink.mtu = mtu
            pending?.complete(status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == uart?.notify) received.trySend(value.copyOf())
        }

        @Deprecated("Android 12 path; 13+ calls the overload with the value")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && characteristic.uuid == uart?.notify) {
                characteristic.value?.let { received.trySend(it.copyOf()) }
            }
        }
    }

    private fun start(autoConnect: Boolean) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            close(CloseReason.PERMISSION, "BLUETOOTH_CONNECT not granted")
            return
        }
        bondState.value = device.bondState
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        // A system broadcast, so it must be exported.
        ContextCompat.registerReceiver(context, bondReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            close(CloseReason.CONNECT_FAILED, "connectGatt returned null")
            return
        }
        scope.launch { setUp() }
    }

    private suspend fun setUp() {
        connected.await()
        // Larger MTU where the stack and adapter agree; failure is fine (writes stay at 20 bytes either way).
        op(SHORT_OP) { it.requestMtu(REQUESTED_MTU) }
        val discovered = op(DISCOVERY_TIMEOUT) { it.discoverServices() }
        val g = gatt ?: return
        if (discovered != BluetoothGatt.GATT_SUCCESS) {
            close(CloseReason.CONNECT_FAILED, "service discovery: ${discovered ?: "timeout"}")
            return
        }
        val services = g.services.map { s ->
            GattServiceInfo(s.uuid, s.characteristics.map { GattCharInfo(it.uuid, it.properties) })
        }
        val chosen = ElmGattProfiles.choose(services)
        if (chosen == null) {
            close(CloseReason.NO_UART, services.joinToString { it.uuid.toString() })
            return
        }
        val service = g.getService(chosen.service)
        notifyChar = service?.getCharacteristic(chosen.notify)
        writeChar = service?.getCharacteristic(chosen.write)
        uart = chosen
        log("Serial service ${chosen.service}: notify ${chosen.notify}, write ${chosen.write}, MTU $mtu")
        val listen = notifyChar ?: return close(CloseReason.NO_UART, "characteristic vanished")
        if (!g.setCharacteristicNotification(listen, true)) return close(CloseReason.CONNECT_FAILED, "setCharacteristicNotification")
        val cccd = listen.getDescriptor(ElmGattProfiles.CCCD)
        if (cccd != null) {
            val value = if (chosen.indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (!withPairing { op(CCCD_TIMEOUT) { gatt -> writeDescriptor(gatt, cccd, value) } }) return
        }
        everReady = true
        _state.value = LinkState.Ready
    }

    /**
     * Runs [attempt]; when the adapter refuses it for want of a bond, bonds (Android's dialog) and tries once more.
     * False when the link was closed.
     */
    private suspend fun withPairing(attempt: suspend () -> Int?): Boolean {
        var status = attempt()
        if (status in AUTH_FAILURES) {
            _state.value = LinkState.Pairing
            log("Adapter needs pairing (GATT status $status)")
            if (bondState.value == BluetoothDevice.BOND_NONE && !device.createBond()) {
                close(CloseReason.PAIRING_FAILED, "createBond refused")
                return false
            }
            val bonded = withTimeoutOrNull(PAIRING_TIMEOUT) {
                // Skip the NONE we may still see before BONDING starts.
                bondState.first { it == BluetoothDevice.BOND_BONDING || it == BluetoothDevice.BOND_BONDED }
                bondState.first { it != BluetoothDevice.BOND_BONDING }
            }
            if (bonded != BluetoothDevice.BOND_BONDED) {
                close(CloseReason.PAIRING_FAILED, if (bonded == null) "timed out" else "bond refused")
                return false
            }
            _state.value = LinkState.Connecting
            status = attempt()
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (_state.value !is LinkState.Closed) close(CloseReason.CONNECT_FAILED, "GATT status ${status ?: "timeout"}")
            return false
        }
        return true
    }

    override suspend fun write(bytes: ByteArray) {
        val characteristic = writeChar ?: return
        val type = if (uart?.writeWithResponse == true) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        repeat(BUSY_RETRIES) {
            val status = op(WRITE_TIMEOUT) { gatt -> writeCharacteristic(gatt, characteristic, bytes, type) }
            when {
                status == BluetoothGatt.GATT_SUCCESS -> return
                status in AUTH_FAILURES -> {
                    // A write characteristic behind encryption: bond, then the next attempt goes through.
                    withPairing { BluetoothGatt.GATT_SUCCESS.takeIf { bondState.value == BluetoothDevice.BOND_BONDED } ?: status }
                }
                status == START_FAILED -> delay(BUSY_DELAY)
                else -> return // A timeout or error: the session's own timeout and retry take over.
            }
        }
    }

    /** One GATT operation: started by [start] (false = the stack refused it), finished by its callback. */
    private suspend fun op(timeout: Duration, start: (BluetoothGatt) -> Boolean): Int? = opLock.withLock {
        val g = gatt ?: return@withLock null
        if (_state.value is LinkState.Closed) return@withLock null
        val done = CompletableDeferred<Int>()
        pending = done
        try {
            if (!start(g)) return@withLock START_FAILED
            withTimeoutOrNull(timeout) { done.await() }
        } finally {
            pending = null
        }
    }

    private fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.setValue(value) && gatt.writeDescriptor(descriptor)
        }

    private fun writeCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            characteristic.setValue(value) && gatt.writeCharacteristic(characteristic)
        }

    @Synchronized
    private fun close(reason: CloseReason, detail: String?) {
        if (_state.value is LinkState.Closed) return
        _state.value = LinkState.Closed(reason, detail)
        if (reason != CloseReason.CLOSED) log("Link closed: $reason${detail?.let { " ($it)" } ?: ""}")
        received.close()
        pending?.complete(CLOSED_STATUS)
        connected.cancel()
        runCatching { context.unregisterReceiver(bondReceiver) }
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        scope.cancel()
    }

    override fun close() = close(CloseReason.CLOSED, null)

    companion object {
        /** Asked for at connect; Android 14+ asks for 517 by itself anyway. */
        const val REQUESTED_MTU = 517

        /** The most one write carries, whatever the MTU: WiCAN Pro's write characteristic takes 20 bytes. */
        const val MAX_WRITE_BYTES = ElmLink.DEFAULT_MAX_WRITE_BYTES
        private const val DEFAULT_MTU = 23
        private const val ATT_HEADER = 3

        private const val START_FAILED = -1
        private const val CLOSED_STATUS = -2
        private const val BUSY_RETRIES = 3
        private val BUSY_DELAY = 50.milliseconds

        /** GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION, GATT_INSUFFICIENT_AUTHORIZATION (8), GATT_AUTH_FAIL (137). */
        private val AUTH_FAILURES = setOf(
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
            8, // GATT_INSUFFICIENT_AUTHORIZATION, named only from API 33
            137,
        )

        private val SHORT_OP = 5.seconds
        private val DISCOVERY_TIMEOUT = 15.seconds
        private val WRITE_TIMEOUT = 3.seconds

        /** Android holds a descriptor write while it bonds, so this covers typing a passkey. */
        private val CCCD_TIMEOUT = 60.seconds
        private val PAIRING_TIMEOUT = 90.seconds

        /** Connects (or, with [autoConnect], waits for the adapter to appear) and returns at once. */
        fun open(context: Context, device: BluetoothDevice, autoConnect: Boolean, log: (String) -> Unit): BleElmLink =
            BleElmLink(context.applicationContext, device, log).also { it.start(autoConnect) }

        @Suppress("DEPRECATION")
        private fun Intent.bluetoothDevice(): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
    }
}

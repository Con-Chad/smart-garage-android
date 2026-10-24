package io.github.conchad.smartgarage.core.obd.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import io.github.conchad.smartgarage.core.obd.trip.ObdError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One device the scan saw, for the adapter picker. [likelyObd]: name or advertised service looks like an adapter. */
data class ScannedAdapter(
    val address: String,
    val name: String?,
    val rssi: Int,
    val likelyObd: Boolean,
    val lastSeenMillis: Long,
)

object ObdScanMatch {
    /** Names BLE OBD adapters advertise: WiCAN, OBDLink CX, Vgate/vLinker, Veepeak, Konnwei, generic clones. */
    private val NAME = Regex("wican|obd|elm|vlink|v-link|vgate|veepeak|konnwei|carista|kiwi|stn", RegexOption.IGNORE_CASE)

    fun likelyObd(name: String?, serviceUuids: Collection<UUID>): Boolean =
        (name != null && NAME.containsMatchIn(name)) || serviceUuids.any { it in ElmGattProfiles.ADVERTISED }

    /** Adapters first, then by signal; the same device once (newest sighting). */
    fun merge(current: List<ScannedAdapter>, seen: ScannedAdapter): List<ScannedAdapter> =
        (current.filterNot { it.address == seen.address } + seen.copy(name = seen.name ?: current.firstOrNull { it.address == seen.address }?.name))
            .sortedWith(compareByDescending<ScannedAdapter> { it.likelyObd }.thenByDescending { it.rssi })
}

/**
 * BLE scan for the adapter picker. BLUETOOTH_SCAN is declared `neverForLocation`, so no location permission is
 * needed (and Android strips beacon-like results, which adapters aren't). Stops by itself after [SCAN_MILLIS].
 */
@SuppressLint("MissingPermission") // start() checks BLUETOOTH_SCAN; stop() only runs after a successful start.
class BleScanner(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val _results = MutableStateFlow<List<ScannedAdapter>>(emptyList())
    val results: StateFlow<List<ScannedAdapter>> = _results.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stop() }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = add(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::add)
        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    /** Null when the scan started, else why it couldn't. Clears the previous results. */
    @Synchronized
    fun start(): ObdError? {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return ObdError.PERMISSION_MISSING
        }
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return ObdError.BLUETOOTH_UNAVAILABLE
        if (!bluetooth.isEnabled) return ObdError.BLUETOOTH_OFF
        val scanner = bluetooth.bluetoothLeScanner ?: return ObdError.BLUETOOTH_OFF
        if (_scanning.value) scanner.stopScan(callback)
        _results.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        _scanning.value = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, SCAN_MILLIS)
        return null
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(timeout)
        if (!_scanning.value) return
        _scanning.value = false
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (bluetooth.isEnabled) bluetooth.bluetoothLeScanner?.stopScan(callback)
    }

    private fun add(result: ScanResult) {
        val record = result.scanRecord
        val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
        val uuids = record?.serviceUuids?.map { it.uuid }.orEmpty()
        val seen = ScannedAdapter(result.device.address, name, result.rssi, ObdScanMatch.likelyObd(name, uuids), clock())
        synchronized(this) { _results.value = ObdScanMatch.merge(_results.value, seen) }
    }

    companion object {
        const val SCAN_MILLIS = 30_000L
    }
}

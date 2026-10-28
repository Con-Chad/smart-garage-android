package io.github.conchad.smartgarage.feature.settings.obd

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.VehicleLocalPrefs
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.obd.ElmTranscriptEntry
import io.github.conchad.smartgarage.core.obd.ObdController
import io.github.conchad.smartgarage.core.obd.ObdPermissionState
import io.github.conchad.smartgarage.core.obd.OdometerKind
import io.github.conchad.smartgarage.core.obd.OdometerProfile
import io.github.conchad.smartgarage.core.obd.OdometerProfiles
import io.github.conchad.smartgarage.core.obd.OdometerUnits
import io.github.conchad.smartgarage.core.obd.WicanImportException
import io.github.conchad.smartgarage.core.obd.ble.ScannedAdapter
import io.github.conchad.smartgarage.core.obd.data.ConnectionEvent
import io.github.conchad.smartgarage.core.obd.data.ObdReading
import io.github.conchad.smartgarage.core.obd.data.ObdSettings
import io.github.conchad.smartgarage.core.obd.device.AssociationStart
import io.github.conchad.smartgarage.core.obd.link.ObdTransportKind
import io.github.conchad.smartgarage.core.obd.trip.ObdError
import io.github.conchad.smartgarage.core.obd.trip.ObdStatus
import io.github.conchad.smartgarage.core.obd.trip.RawResult
import io.github.conchad.smartgarage.core.obd.trip.ReadNowResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A vehicle the readings can go to: the phone's nickname when set, else the server's name. */
data class VehicleOption(val id: String, val label: String)

/** The persisted side of the screen, from the controller's flows. */
data class ObdUiState(
    val loaded: Boolean = false,
    val status: ObdStatus = ObdStatus.Disconnected,
    val running: Boolean = false,
    val settings: ObdSettings = ObdSettings(),
    val fmt: Formatter = Formatter(null),
    val vehicles: List<VehicleOption> = emptyList(),
    val queued: Int = 0,
    val recent: List<ObdReading> = emptyList(),
    val scanResults: List<ScannedAdapter> = emptyList(),
    val transcript: List<ElmTranscriptEntry> = emptyList(),
    val connectionLog: List<ConnectionEvent> = emptyList(),
)

/** "Read now" and the dash check under it. */
data class ReadUiState(
    val reading: Boolean = false,
    val result: ReadNowResult? = null,
    val calibration: Calibration? = null,
)

/** One-off things for the screen to do: dialogs, the system's association dialog, a share sheet, a message. */
sealed interface ObdEvent {
    data class LaunchAssociation(val sender: IntentSender) : ObdEvent

    /** CompanionDeviceManager missing, declined or failed: offer to use [adapter] without it. */
    data class OfferDirect(val adapter: ScannedAdapter, val why: DirectReason, val detail: String? = null) : ObdEvent

    /** [bondRemoved] false for a Bluetooth adapter: tell the user to forget it in Android's Bluetooth settings. */
    data class Forgot(val name: String?, val bondRemoved: Boolean, val bluetooth: Boolean) : ObdEvent

    data class Share(val intent: Intent) : ObdEvent
    data class ScanFailed(val error: ObdError) : ObdEvent
    data class ImportFailed(val reason: WicanImportException.Reason, val detail: String?) : ObdEvent
    data object Imported : ObdEvent
    data object CalibrationApplied : ObdEvent
}

enum class DirectReason { UNAVAILABLE, DECLINED, FAILED }

/**
 * Settings → OBD adapter (D-A81). Everything is the controller's ([ObdController], D-A80); this only shapes it for
 * the screen and keeps the transient bits (the read, the dash check, the raw console).
 */
@HiltViewModel
class ObdAdapterViewModel @Inject constructor(
    private val obd: ObdController,
    garage: GarageRepository,
    vehiclePrefs: VehicleLocalPrefs,
) : ViewModel() {

    val simulatorAvailable: Boolean get() = obd.simulatorAvailable
    val companionAvailable: Boolean get() = obd.companionAvailable

    private val vehicleOptions: Flow<List<VehicleOption>> = combine(garage.vehicles, vehiclePrefs.nicknames) { vehicles, nicknames ->
        vehicles.filter { it.access.canEdit }
            .map { VehicleOption(it.id, nicknames[it.id] ?: it.displayName) }
            .sortedBy { it.label.lowercase() }
    }

    private val connection = combine(obd.status, obd.running, obd.scanResults, ::Triple)
    private val stored = combine(obd.settings, garage.formatter, vehicleOptions, ::Triple)
    private val records = combine(obd.queuedReadings, obd.recentReadings(RECENT_LIMIT), ::Pair)
    private val logs = combine(obd.transcript, obd.connectionLog, ::Pair)

    val state: StateFlow<ObdUiState> =
        combine(connection, stored, records, logs) { (status, running, scan), (settings, fmt, vehicles), (queued, recent), (transcript, log) ->
            ObdUiState(
                loaded = true,
                status = status,
                running = running,
                settings = settings,
                fmt = fmt,
                vehicles = vehicles,
                queued = queued,
                recent = recent,
                scanResults = scan,
                transcript = transcript,
                connectionLog = log,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ObdUiState())

    private val _permissions = MutableStateFlow(obd.permissions())
    val permissions: StateFlow<ObdPermissionState> = _permissions

    private val _read = MutableStateFlow(ReadUiState())
    val read: StateFlow<ReadUiState> = _read

    private val _console = MutableStateFlow<List<RawResult>>(emptyList())
    /** The raw console's replies this visit, newest last. */
    val console: StateFlow<List<RawResult>> = _console

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    private val _events = Channel<ObdEvent>(Channel.BUFFERED)
    val events: Flow<ObdEvent> = _events.receiveAsFlow()

    /** The adapter the system's association dialog is about, for its result. */
    private var pending: ScannedAdapter? = null

    /** After the permission dialog and on every resume (the user may have changed them in system settings). */
    fun refreshPermissions() {
        _permissions.value = obd.permissions()
    }

    // ── Adapter ──────────────────────────────────────────────────────────────────────────────────────────────

    fun startScan() {
        obd.startScan()?.let { _events.trySend(ObdEvent.ScanFailed(it)) }
    }

    fun stopScan() = obd.stopScan()

    fun choose(adapter: ScannedAdapter) {
        viewModelScope.launch {
            when (val start = obd.associate(adapter)) {
                is AssociationStart.Launch -> {
                    pending = adapter
                    _events.send(ObdEvent.LaunchAssociation(start.intentSender))
                }
                is AssociationStart.Created -> Unit
                AssociationStart.Unavailable -> _events.send(ObdEvent.OfferDirect(adapter, DirectReason.UNAVAILABLE))
                is AssociationStart.Failed -> _events.send(ObdEvent.OfferDirect(adapter, DirectReason.FAILED, start.message))
            }
        }
    }

    fun onAssociationResult(resultCode: Int, data: Intent?) {
        val adapter = pending ?: return
        pending = null
        viewModelScope.launch {
            if (obd.onAssociationResult(resultCode, data, adapter.name) == null) {
                _events.send(ObdEvent.OfferDirect(adapter, DirectReason.DECLINED))
            }
        }
    }

    fun useDirect(adapter: ScannedAdapter) {
        viewModelScope.launch { obd.useWithoutAssociation(adapter) }
    }

    fun useSimulator() {
        viewModelScope.launch {
            obd.stopScan()
            obd.useSimulator()
        }
    }

    /** Debug simulator: the pretend car's starting odometer, in the display unit. */
    fun setSimulatorStart(text: String) {
        val value = ObdInputs.distance(text) ?: return
        val km = OdometerUnits.displayToKm(value, state.value.fmt.miles)
        val current = state.value.settings.simulator
        if (km == current.startOdometerKm) return
        viewModelScope.launch { obd.setSimulator(current.copy(startOdometerKm = km)) }
    }

    /** Debug simulator: the pretend car's speed, in the display unit per hour. */
    fun setSimulatorSpeed(text: String) {
        val value = ObdInputs.distance(text)?.takeIf { it > 0 } ?: return
        val kmh = OdometerUnits.displayToKm(value, state.value.fmt.miles)
        val current = state.value.settings.simulator
        if (kmh == current.speedKmh) return
        viewModelScope.launch { obd.setSimulator(current.copy(speedKmh = kmh)) }
    }

    fun forget() {
        val adapter = state.value.settings.adapter ?: return
        viewModelScope.launch {
            val removed = obd.forget()
            _read.value = ReadUiState()
            _events.send(ObdEvent.Forgot(adapter.name, removed, adapter.transport == ObdTransportKind.BLE))
        }
    }

    fun connect() {
        viewModelScope.launch { obd.connect() }
    }

    fun disconnect() {
        viewModelScope.launch { obd.disconnect() }
    }

    // ── Vehicle and profile ──────────────────────────────────────────────────────────────────────────────────

    fun setVehicle(id: String) {
        viewModelScope.launch { obd.setVehicle(id) }
    }

    fun setBuiltIn(kind: OdometerKind) {
        val profile = OdometerProfiles.builtIn(kind) ?: return
        // A read made with the old profile would read as this one's.
        _read.value = ReadUiState()
        viewModelScope.launch { obd.setProfile(profile) }
    }

    /** A pasted WiCAN vehicle profile (the whole file, one car or one pid entry). */
    fun importWican(text: String) {
        val profile = try {
            OdometerProfiles.fromWicanJson(text)
        } catch (e: WicanImportException) {
            _events.trySend(ObdEvent.ImportFailed(e.reason, e.message))
            return
        }
        _read.value = ReadUiState()
        viewModelScope.launch {
            obd.setProfile(profile)
            _events.send(ObdEvent.Imported)
        }
    }

    // ── Read now and the dash check ──────────────────────────────────────────────────────────────────────────

    fun readNow() {
        if (_read.value.reading) return
        _read.value = _read.value.copy(reading = true, calibration = null)
        viewModelScope.launch {
            val result = try {
                obd.readNow()
            } finally {
                _read.update { it.copy(reading = false) }
            }
            _read.update { it.copy(result = result) }
        }
    }

    /**
     * [dashText] in the display unit. False when there is no successful read or the text isn't a number (the field
     * shows the error); otherwise the outcome is in [read].
     */
    fun checkDash(dashText: String, customName: String): Boolean {
        val km = _read.value.result?.takeIf { it.error == null }?.km ?: return false
        val dash = ObdInputs.odometer(dashText) ?: return false
        val dashKm = OdometerUnits.displayToKm(dash, state.value.fmt.miles)
        val calibration = ObdCalibration.check(km, dashKm, state.value.settings.profile, customName)
        _read.update { it.copy(calibration = calibration) }
        return true
    }

    fun applyCalibration() {
        val fix = _read.value.calibration as? Calibration.Fix ?: return
        viewModelScope.launch {
            obd.setProfile(fix.profile)
            // The shown read was made with the old profile: read again to see the fix.
            _read.value = ReadUiState()
            _events.send(ObdEvent.CalibrationApplied)
        }
    }

    fun clearCalibration() = _read.update { it.copy(calibration = null) }

    // ── Uploads ──────────────────────────────────────────────────────────────────────────────────────────────

    fun setPollMinutes(text: String) {
        val minutes = ObdInputs.minutes(text) ?: return
        if (minutes == state.value.settings.pollMinutes) return
        viewModelScope.launch { obd.setPollMinutes(minutes) }
    }

    fun setMinUploadDistance(text: String) {
        val distance = ObdInputs.distance(text) ?: return
        if (distance == state.value.settings.minUploadDistance) return
        viewModelScope.launch { obd.setMinUploadDistance(distance) }
    }

    fun setUploadOnTripEnd(enabled: Boolean) {
        viewModelScope.launch { obd.setUploadOnTripEnd(enabled) }
    }

    fun setCellularUploads(enabled: Boolean) {
        viewModelScope.launch { obd.setCellularUploads(enabled) }
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────────────────────────────────────

    fun sendRaw(command: String) {
        val text = command.trim()
        if (text.isEmpty() || _sending.value) return
        _sending.value = true
        viewModelScope.launch {
            try {
                val result = obd.sendRaw(text)
                _console.update { (it + result).takeLast(CONSOLE_LIMIT) }
            } finally {
                _sending.value = false
            }
        }
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    fun exportDiagnostics() {
        viewModelScope.launch { _events.send(ObdEvent.Share(obd.exportDiagnostics())) }
    }

    companion object {
        const val RECENT_LIMIT = 50
        const val CONSOLE_LIMIT = 20

        /** Built-ins in picker order, then the stored custom one (if any) as its own entry. */
        fun profileChoices(current: OdometerProfile): List<OdometerProfile> =
            OdometerProfiles.builtIns + listOfNotNull(current.takeIf { it.kind == OdometerKind.CUSTOM })
    }
}

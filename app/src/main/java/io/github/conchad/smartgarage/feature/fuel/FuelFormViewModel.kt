package io.github.conchad.smartgarage.feature.fuel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.data.AppSettings
import io.github.conchad.smartgarage.core.data.FuelEntryInput
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.FuelEntry
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.core.network.ReceiptScanError
import io.github.conchad.smartgarage.core.network.ReceiptScanResult
import io.github.conchad.smartgarage.core.network.ReceiptScanner
import io.github.conchad.smartgarage.ui.files.CameraCapture
import io.github.conchad.smartgarage.ui.files.PickedFile
import io.github.conchad.smartgarage.ui.navigation.FuelEntryRoute
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** Which money field the user touched last decides which one is recomputed when the volume changes. */
enum class MoneyField { Price, Total }

/** One-shot outcome of a receipt scan for the snackbar (D-A27); the screen shows it and calls [FuelFormViewModel.clearScanMessage]. */
enum class ScanMessage {
    /** The stub, or a server with the feature off (404). */
    Unavailable,
    ViewOnly,
    NotFuelReceipt,
    RateLimited,
    FileRejected,
    Unreachable,
    Failed,
    NoCamera,
}

/**
 * The quick fill-up form (D-A20). Text fields hold what the user typed, in display units; [input] converts to
 * stored units at save time. Prefilled from the vehicle: the odometer from its current reading, the price per
 * unit from its most recent fill-up.
 */
data class FuelFormState(
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val odometer: String = "",
    val volume: String = "",
    val price: String = "",
    val total: String = "",
    val station: String = "",
    val notes: String = "",
    val lastMoney: MoneyField = MoneyField.Price,
    /** The selected vehicle's current reading, in km, for the regression check. */
    val currentOdometerKm: Int? = null,
    /** The selected vehicle's most recent fill-up, shown under the price as context. */
    val lastEntry: FuelEntry? = null,
    /** Settings → Fuel → Track fuel economy (D-A25): off hides the odometer field and sends no reading. */
    val trackOdometer: Boolean = true,
    /** `/me.features.receiptScanner` of the active server (D-A27): false hides "Scan receipt" altogether. */
    val scannerAvailable: Boolean = false,
    /** The photo is being downscaled or is at the server. */
    val scanning: Boolean = false,
    /** The last receipt photo taken, kept in `cache/captures/` like the document form's (D-A23) for a later attach. */
    val receiptPhoto: PickedFile? = null,
    /** `documentId` from a successful scan, sent as `receiptDocumentId`; the stub never sets it. */
    val receiptDocumentId: String? = null,
    val scanMessage: ScanMessage? = null,
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    /** Set once the write is queued; the screen closes. */
    val done: Boolean = false,
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
    val canEdit: Boolean get() = vehicle?.access?.canEdit == true

    val odometerKm: Int? get() = formatter.parseNumber(odometer)?.let(formatter::kmFromDisplay)
    val liters: Double? get() = formatter.parseNumber(volume)?.let(formatter::litersFromDisplay)
    val totalCents: Long? get() = formatter.parseNumber(total)?.let(formatter::centsFromDisplay)

    val odometerInvalid: Boolean get() = trackOdometer && odometer.isNotBlank() && (odometerKm == null || odometerKm!! < 0)
    val volumeInvalid: Boolean get() = volume.isNotBlank() && (liters == null || liters!! < 0 || liters!! > 500)
    val priceInvalid: Boolean get() = price.isNotBlank() && (formatter.parseNumber(price) ?: -1.0) < 0
    val totalInvalid: Boolean get() = total.isNotBlank() && (totalCents == null || totalCents!! < 0)

    /** Below the vehicle's current reading: allowed (a late entry), but only after a confirmation. */
    val odometerBelowCurrent: Boolean
        get() = trackOdometer && odometerKm?.let { km -> currentOdometerKm?.let { km < it } } == true

    val canSave: Boolean
        get() = canEdit && totalCents != null && !odometerInvalid && !volumeInvalid && !priceInvalid && !totalInvalid && !saving && !scanning

    /** The button shows only when the server offers the scan and this user may write to the vehicle. */
    val canScan: Boolean get() = scannerAvailable && canEdit && !saving

    fun input(now: Instant = Instant.now()): FuelEntryInput = FuelEntryInput(
        filledAt = formatter.instantFor(date, now).toString(),
        totalCostCents = totalCents ?: 0,
        odometerKm = odometerKm.takeIf { trackOdometer },
        liters = liters,
        currency = formatter.currencyCode,
        stationName = station.trim().ifEmpty { null },
        note = notes.trim().ifEmpty { null },
        receiptDocumentId = receiptDocumentId,
    )
}

@HiltViewModel
class FuelFormViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    settings: AppSettings,
    profiles: ProfileStore,
    control: SyncControl,
    writer: GarageWriter,
    private val scanner: ReceiptScanner,
    @ApplicationContext private val context: Context,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<FuelEntryRoute>()
    private val form = MutableStateFlow(FuelFormState())
    val state: StateFlow<FuelFormState> = form.asStateFlow()

    private var entries: List<FuelEntry> = emptyList()

    /** The camera's target survives the process being killed behind the camera app (as on the document form). */
    private var capturePath: String?
        get() = savedStateHandle[CAPTURE_PATH]
        set(value) = savedStateHandle.set(CAPTURE_PATH, value)

    init {
        viewModelScope.launch {
            profiles.state.map { it.active?.features?.receiptScanner == true }.distinctUntilChanged()
                .collect { on -> form.update { it.copy(scannerAvailable = on) } }
        }
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicles = garage.vehicles.first().filter { it.access.canEdit }.sortedWith(GarageMath.vehicleOrder)
            entries = garage.fuelEntries.first()
            val track = settings.fuel.first().trackFuelEconomy
            // The vehicle from context, else the one filled up most recently, else the first.
            val preselected = route.vehicleId?.takeIf { id -> vehicles.any { it.id == id } }
                ?: entries.maxByOrNull { it.filledAt.toInstantOrNull() ?: Instant.MIN }?.vehicleId?.takeIf { id -> vehicles.any { it.id == id } }
                ?: vehicles.firstOrNull()?.id
            form.update { it.copy(vehicles = vehicles, date = fmt.today(), trackOdometer = track, formatter = fmt, loaded = true) }
            preselected?.let { selectVehicle(it, initial = true) }
        }
    }

    fun selectVehicle(id: String, initial: Boolean = false) {
        form.update { s ->
            val vehicle = s.vehicles.firstOrNull { it.id == id } ?: return@update s
            val last = entries.filter { it.vehicleId == id }.maxByOrNull { it.filledAt.toInstantOrNull() ?: Instant.MIN }
            val fmt = s.formatter
            val prefillPrice = last?.liters?.let { fmt.unitPriceValue(last.totalCostCents, it) }?.let { fmt.inputNumber(it, 3) }.orEmpty()
            s.copy(
                vehicleId = id,
                currentOdometerKm = vehicle.currentOdometerKm,
                lastEntry = last,
                odometer = fmt.distanceInput(vehicle.currentOdometerKm),
                // A price already on the form survives a vehicle switch; an empty one takes the vehicle's last price.
                price = if (initial || s.price.isBlank()) prefillPrice else s.price,
                dirty = !initial,
            ).recompute(changed = null)
        }
    }

    fun setDate(date: LocalDate) = form.update { it.copy(date = date, dirty = true) }
    fun setOdometer(text: String) = form.update { it.copy(odometer = text, dirty = true) }
    fun setStation(text: String) = form.update { it.copy(station = text, dirty = true) }
    fun setNotes(text: String) = form.update { it.copy(notes = text, dirty = true) }

    fun setVolume(text: String) = form.update { s ->
        s.copy(volume = text, dirty = true).recompute(changed = null)
    }

    fun setPrice(text: String) = form.update { s ->
        s.copy(price = text, lastMoney = MoneyField.Price, dirty = true).recompute(changed = MoneyField.Price)
    }

    fun setTotal(text: String) = form.update { s ->
        s.copy(total = text, lastMoney = MoneyField.Total, dirty = true).recompute(changed = MoneyField.Total)
    }

    /**
     * Keeps volume × price = total. After a money field changes, the other money field follows when the volume is
     * known; otherwise the volume follows (the web's quick add: amount ÷ price). After a volume change, the money
     * field the user touched last stays and the other is recomputed.
     */
    private fun FuelFormState.recompute(changed: MoneyField?): FuelFormState {
        val fmt = formatter
        val v = fmt.parseNumber(volume)
        val p = fmt.parseNumber(price)
        val t = fmt.parseNumber(total)
        val keep = changed ?: lastMoney
        return when {
            keep == MoneyField.Price && v != null && p != null -> copy(total = fmt.inputNumber(v * p, 2, minFraction = 2))
            keep == MoneyField.Total && v != null && t != null && v > 0 -> copy(price = fmt.inputNumber(t / v, 3))
            changed != null && v == null && p != null && t != null && p > 0 && volume.isBlank() -> copy(volume = fmt.inputNumber(t / p, 2))
            else -> this
        }
    }

    /** Queues the fill-up; the screen confirms an odometer regression first (see [FuelFormState.odometerBelowCurrent]). */
    fun save() {
        val s = form.value
        if (!s.canSave) return
        val vehicleId = s.vehicleId ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val queued = report(writer.addFuelEntry(vehicleId, s.input()))
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }

    // ── Receipt scan (D-A27) ─────────────────────────────────────────────────────────────────────────────────

    /** A fresh target for `TakePicture`; a photo still on the form is discarded. */
    fun startScan(): Uri {
        capturePath?.let { File(it).delete() }
        CameraCapture.sweep(context)
        val target = CameraCapture.newTarget(context)
        capturePath = target.file.absolutePath
        return target.uri
    }

    /** The camera came back: downscale the photo, send it to the [ReceiptScanner] and put the answer into words. */
    fun captured(success: Boolean) {
        val path = capturePath ?: return
        capturePath = null
        if (!success) {
            File(path).delete()
            return
        }
        val vehicleId = form.value.vehicleId ?: return
        form.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val file = File(path)
            val photo = CameraCapture.finish(context, file, "receipt-${System.currentTimeMillis()}.jpg")
            if (photo == null) {
                form.update { it.copy(scanning = false, scanMessage = ScanMessage.FileRejected) }
                return@launch
            }
            // The previous photo makes way for this one; the form keeps at most one.
            form.value.receiptPhoto?.let { captureFileOf(it)?.takeIf { f -> f != file }?.delete() }
            val result = scanner.scan(vehicleId, file)
            form.update { s ->
                s.copy(
                    scanning = false,
                    receiptPhoto = photo,
                    receiptDocumentId = result.documentIdOrNull(),
                    scanMessage = result.message(),
                )
            }
        }
    }

    /** Drops the kept photo (and its file) from the form. */
    fun removeReceiptPhoto() {
        form.value.receiptPhoto?.let { captureFileOf(it)?.delete() }
        form.update { it.copy(receiptPhoto = null, receiptDocumentId = null) }
    }

    fun cameraUnavailable() = form.update { it.copy(scanMessage = ScanMessage.NoCamera) }
    fun clearScanMessage() = form.update { it.copy(scanMessage = null) }

    private fun captureFileOf(file: PickedFile): File? =
        file.uri.path?.takeIf { it.contains("/captures/") }?.let { File(context.cacheDir, "captures/${it.substringAfterLast('/')}") }

    /** Prefill from a draft is a later step (see [ReceiptScanner]); tonight a success only keeps the document id. */
    private fun ReceiptScanResult.documentIdOrNull(): String? = when (this) {
        is ReceiptScanResult.Success -> response.documentId
        is ReceiptScanResult.Error -> (error as? ReceiptScanError.ExtractionFailed)?.documentId
        ReceiptScanResult.Unavailable -> null
    }

    private fun ReceiptScanResult.message(): ScanMessage? = when (this) {
        ReceiptScanResult.Unavailable -> ScanMessage.Unavailable
        is ReceiptScanResult.Success -> if (response.isFuelReceipt) null else ScanMessage.NotFuelReceipt
        is ReceiptScanResult.Error -> when (error) {
            ReceiptScanError.NotFound -> ScanMessage.Unavailable
            ReceiptScanError.Forbidden -> ScanMessage.ViewOnly
            is ReceiptScanError.RateLimited -> ScanMessage.RateLimited
            ReceiptScanError.FileTooLarge, ReceiptScanError.UnsupportedFile -> ScanMessage.FileRejected
            is ReceiptScanError.Unreachable -> ScanMessage.Unreachable
            ReceiptScanError.Unauthorized, is ReceiptScanError.Invalid, is ReceiptScanError.ExtractionFailed -> ScanMessage.Failed
        }
    }

    private companion object {
        const val CAPTURE_PATH = "receiptCapturePath"
    }
}

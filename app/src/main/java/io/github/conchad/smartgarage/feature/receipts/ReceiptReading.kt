package io.github.conchad.smartgarage.feature.receipts

import io.github.conchad.smartgarage.core.network.ReceiptScanError
import io.github.conchad.smartgarage.core.network.ReceiptScanResult
import io.github.conchad.smartgarage.core.network.documentIdOrNull
import io.github.conchad.smartgarage.feature.receipts.ocr.DeviceRead
import io.github.conchad.smartgarage.feature.receipts.ocr.DeviceReadFailure
import io.github.conchad.smartgarage.feature.receipts.ocr.ParseWarning
import io.github.conchad.smartgarage.feature.receipts.ocr.ParsedReceipt
import io.github.conchad.smartgarage.feature.receipts.ocr.ReceiptTextReader
import java.io.File
import javax.inject.Inject

/**
 * Server first, then the phone (D-A63). The scan buttons show on every fuel, service and expense form whatever the
 * server offers; a photo goes to the server's AI scanner when `/me` lists it, and is read on the phone instead when
 * the scanner is off, can't be reached or fails, from the same photo (no second capture).
 */
sealed interface ReceiptRead<out T> {
    /** The server's scanner answered; the forms handle it as before (D-A53, D-A56). */
    data class Server<T>(val result: ReceiptScanResult<T>) : ReceiptRead<T>

    /**
     * Read on the phone. [serverTried]: the server was asked first and failed. [serverDocumentId]: it still saved the
     * photo (a 502), so the entry keeps it as its receipt.
     */
    data class OnDevice(val read: DeviceRead, val serverTried: Boolean, val serverDocumentId: String? = null) : ReceiptRead<Nothing>
}

/**
 * Whether a server answer is retried on the phone: every failure except 403 (view-only: saving would be refused
 * too). A successful answer is final, also "not a receipt": the model reads better than the phone.
 */
fun fallsBackToDevice(result: ReceiptScanResult<*>): Boolean = when (result) {
    is ReceiptScanResult.Success -> false
    ReceiptScanResult.Unavailable -> true
    is ReceiptScanResult.Error -> result.error != ReceiptScanError.Forbidden
}

class ReceiptReading @Inject constructor(private val device: ReceiptTextReader) {
    /**
     * Reads [photo] with the server when [serverOn], falling back to the phone as [fallsBackToDevice] says.
     * [onDevice] runs just before the phone starts (the progress line changes).
     */
    suspend fun <T> read(
        serverOn: Boolean,
        photo: File,
        onDevice: () -> Unit = {},
        server: suspend (File) -> ReceiptScanResult<T>,
    ): ReceiptRead<T> {
        if (!serverOn) {
            onDevice()
            return ReceiptRead.OnDevice(device.read(photo), serverTried = false)
        }
        val result = server(photo)
        if (!fallsBackToDevice(result)) return ReceiptRead.Server(result)
        onDevice()
        return ReceiptRead.OnDevice(device.read(photo), serverTried = true, serverDocumentId = result.documentIdOrNull())
    }
}

/** Things worth saying about a phone read, in words the user can act on. */
enum class DeviceWarning {
    /** No "Total"/"Summe" label: the largest amount was taken. */
    TotalUnlabelled,
    AmountsMismatch,
    PriceDerived,
    FromGallons,
    DateAmbiguous,
    HardToRead,
    /** A fuel receipt read on the service or expense form. */
    FuelReceipt,
}

/**
 * The note after a phone read (D-A63): which fields it [filled] (all of them to be checked), its warnings, and the
 * recognised [lines] for the "Text read from the photo" sheet. [serverTried]: the server's scanner failed first.
 */
data class DeviceReview(
    val filled: List<ScanField> = emptyList(),
    val warnings: List<DeviceWarning> = emptyList(),
    val lines: List<String> = emptyList(),
    val serverTried: Boolean = false,
) {
    /** Text was found, but nothing the form could use: the sheet with the text opens by itself. */
    val nothingFilled: Boolean get() = filled.isEmpty()
}

/**
 * The parser's warnings that matter for what was filled; [fuelForm] keeps the volume/price ones, elsewhere a fuel
 * receipt is pointed out instead.
 */
fun deviceWarnings(receipt: ParsedReceipt, filled: Collection<ScanField>, fuelForm: Boolean): List<DeviceWarning> = buildList {
    val w = receipt.warnings
    if (ParseWarning.TOTAL_UNLABELLED in w && ScanField.Total in filled) add(DeviceWarning.TotalUnlabelled)
    if (fuelForm) {
        if (ParseWarning.TOTAL_INCONSISTENT in w) add(DeviceWarning.AmountsMismatch)
        if (ParseWarning.PRICE_DERIVED in w && ScanField.Price in filled) add(DeviceWarning.PriceDerived)
        if (ParseWarning.VOLUME_CONVERTED_FROM_GALLONS in w || ParseWarning.PRICE_CONVERTED_FROM_GALLONS in w) add(DeviceWarning.FromGallons)
    } else if (receipt.isFuelReceipt) {
        add(DeviceWarning.FuelReceipt)
    }
    if (ParseWarning.DATE_AMBIGUOUS in w && ScanField.Date in filled) add(DeviceWarning.DateAmbiguous)
    if (ParseWarning.LOW_OCR_CONFIDENCE in w) add(DeviceWarning.HardToRead)
}

/** The snackbar line for a phone read: failures and "no text"; null when the note under the buttons says it all. */
fun DeviceRead.message(): ScanMessage? = when (this) {
    is DeviceRead.Text -> if (noText) ScanMessage.NoText else null
    is DeviceRead.Failed -> when (reason) {
        DeviceReadFailure.Unreadable -> ScanMessage.FileRejected
        DeviceReadFailure.OutOfMemory -> ScanMessage.DeviceOutOfMemory
        DeviceReadFailure.EngineFailed -> ScanMessage.DeviceFailed
        DeviceReadFailure.Cancelled -> null
    }
}

/**
 * One text field filled from a phone read: [value] as text when the receipt had it and the field is empty or the
 * user hasn't typed in it ([typed]); a prefill (last price, current reading) counts as untyped. Records the field
 * in [filled] when it fills.
 */
internal fun <T : Any> fillField(
    field: ScanField,
    current: String,
    value: T?,
    typed: Set<ScanField>,
    filled: MutableList<ScanField>,
    text: (T) -> String,
): String {
    if (value == null || (current.isNotBlank() && field in typed)) return current
    filled += field
    return text(value)
}

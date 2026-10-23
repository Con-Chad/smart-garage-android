package io.github.conchad.smartgarage.feature.receipts

import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.network.ReceiptScanError
import io.github.conchad.smartgarage.core.network.ReceiptScanResponse
import io.github.conchad.smartgarage.core.network.ReceiptScanResult
import io.github.conchad.smartgarage.core.network.ServiceReceiptScanResponse
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * What the forms make of a receipt scan (D-A53), kept free of Android so it is unit-tested: the one-shot snackbar
 * message, and the short "please check" note under the scan button (fields the model was unsure of plus the
 * contract's warnings, in words).
 */

/** One-shot outcome of a scan for the snackbar; the screen shows it and clears it. */
enum class ScanMessage {
    /** A server with the feature off (404), or nobody signed in. */
    Unavailable,
    ViewOnly,
    NotFuelReceipt,
    NotServiceReceipt,
    /** A fuel receipt scanned on the service form: use the fill-up form's scan instead. */
    FuelReceiptOnServiceForm,
    RateLimited,
    FileRejected,
    Unreachable,
    Failed,
    /** 502: nothing was read, but the photo is saved and stays attached to the manual entry. */
    FailedPhotoKept,
    NoCamera,
    /** Neither the Photo Picker nor a document picker is available (D-A56). */
    NoGallery,
}

/** A form field the model read with confidence below [LOW_CONFIDENCE], so the user should look at it. */
enum class ScanField { Date, Volume, Total, Station, Odometer, Vendor, ServiceType }

/** The contract's warning codes, folded into what the user can act on. */
enum class ScanWarning {
    AmountsMismatch,
    CurrencyAssumed,
    CurrencyConflict,
    DateInFuture,
    DateUnreadable,
    VolumeUnreadable,
    TotalUnreadable,
    OdometerImplausible,
    PickSchedule,
    ItemsTruncated,
    /** The bill looks like an expense (insurance, tax, …) rather than a service; offer the expense form. */
    SuggestExpense,
    /** On the expense form: the bill looks like a workshop service; offer the service record form (D-A56). */
    SuggestRecord,
}

/** The note under the scan button after a successful read; empty when there is nothing to check. */
data class ScanReview(
    val check: List<ScanField> = emptyList(),
    val warnings: List<ScanWarning> = emptyList(),
) {
    val isEmpty: Boolean get() = check.isEmpty() && warnings.isEmpty()
}

const val LOW_CONFIDENCE = 0.8

fun fuelReview(response: ReceiptScanResponse): ScanReview {
    val draft = response.draft ?: return ScanReview()
    val c = draft.confidence
    val check = buildList {
        if (draft.filledAtLocal != null && c.low("filledAtLocal")) add(ScanField.Date)
        if (draft.liters != null && c.low("liters")) add(ScanField.Volume)
        if (draft.totalCostCents != null && c.low("totalCostCents")) add(ScanField.Total)
        if (draft.stationName != null && c.low("stationName")) add(ScanField.Station)
    }
    return ScanReview(check, warningsOf(response.warnings))
}

fun serviceReview(response: ServiceReceiptScanResponse): ScanReview {
    val draft = response.draft ?: return ScanReview()
    val c = draft.confidence
    val check = buildList {
        if (draft.performedAtLocal != null && c.low("performedAtLocal")) add(ScanField.Date)
        if (draft.odometerKm != null && c.low("odometerKm")) add(ScanField.Odometer)
        if (draft.totalCostCents != null && c.low("totalCostCents")) add(ScanField.Total)
        if (draft.vendorName != null && c.low("vendorName")) add(ScanField.Vendor)
        if (draft.serviceTypes.isNotEmpty() && c.low("serviceTypes")) add(ScanField.ServiceType)
    }
    val warnings = warningsOf(response.warnings).toMutableList()
    if (draft.suggestedScheduleId == null && ScanWarning.PickSchedule !in warnings && draft.suggestedTarget != "expense") {
        warnings += ScanWarning.PickSchedule
    }
    if (draft.suggestedTarget == "expense") warnings += ScanWarning.SuggestExpense
    return ScanReview(check, warnings)
}

/**
 * The note for a service scan read into the expense form (D-A56): only the fields the expense has (date, total,
 * vendor); schedule warnings don't apply there; a bill the server would rather file as a record says so.
 */
fun expenseReview(response: ServiceReceiptScanResponse): ScanReview {
    val draft = response.draft ?: return ScanReview()
    val c = draft.confidence
    val check = buildList {
        if (draft.performedAtLocal != null && c.low("performedAtLocal")) add(ScanField.Date)
        if (draft.totalCostCents != null && c.low("totalCostCents")) add(ScanField.Total)
        if (draft.vendorName != null && c.low("vendorName")) add(ScanField.Vendor)
    }
    val warnings = warningsOf(response.warnings).filter { it !in NOT_FOR_EXPENSE }.toMutableList()
    if (draft.suggestedTarget == "record") warnings += ScanWarning.SuggestRecord
    return ScanReview(check, warnings)
}

/** Warnings about fields the expense form doesn't have. */
private val NOT_FOR_EXPENSE = setOf(ScanWarning.PickSchedule, ScanWarning.OdometerImplausible, ScanWarning.ItemsTruncated)

private fun Map<String, Double>.low(key: String): Boolean = (this[key] ?: 0.0) < LOW_CONFIDENCE

/** Unknown codes are ignored (a newer server may add some); `time_missing` doesn't matter to a date-only form. */
internal fun warningsOf(codes: List<String>): List<ScanWarning> = codes.mapNotNull { code ->
    when (code) {
        "total_mismatch", "cost_breakdown_mismatch" -> ScanWarning.AmountsMismatch
        "currency_assumed" -> ScanWarning.CurrencyAssumed
        "currency_conflict" -> ScanWarning.CurrencyConflict
        "date_in_future" -> ScanWarning.DateInFuture
        "date_missing", "date_unreadable" -> ScanWarning.DateUnreadable
        "volume_missing", "volume_implausible" -> ScanWarning.VolumeUnreadable
        "total_missing", "total_implausible" -> ScanWarning.TotalUnreadable
        "odometer_implausible" -> ScanWarning.OdometerImplausible
        "service_type_unknown", "no_matching_schedule" -> ScanWarning.PickSchedule
        "items_truncated" -> ScanWarning.ItemsTruncated
        else -> null
    }
}.distinct()

/** The snackbar line for a fuel scan; null when the draft went into the form and the note says the rest. */
fun ReceiptScanResult<ReceiptScanResponse>.fuelMessage(): ScanMessage? = when (this) {
    is ReceiptScanResult.Success -> if (response.isFuelReceipt && response.draft != null) null else ScanMessage.NotFuelReceipt
    else -> failureMessage()
}

/** The snackbar line for a service scan. */
fun ReceiptScanResult<ServiceReceiptScanResponse>.serviceMessage(): ScanMessage? = when (this) {
    is ReceiptScanResult.Success -> when {
        response.isServiceReceipt && response.draft != null -> null
        "fuel_receipt" in response.warnings -> ScanMessage.FuelReceiptOnServiceForm
        else -> ScanMessage.NotServiceReceipt
    }
    else -> failureMessage()
}

private fun ReceiptScanResult<*>.failureMessage(): ScanMessage = when (this) {
    is ReceiptScanResult.Success -> ScanMessage.Failed
    ReceiptScanResult.Unavailable -> ScanMessage.Unavailable
    is ReceiptScanResult.Error -> when (error) {
        ReceiptScanError.NotFound -> ScanMessage.Unavailable
        ReceiptScanError.Forbidden -> ScanMessage.ViewOnly
        is ReceiptScanError.RateLimited -> ScanMessage.RateLimited
        ReceiptScanError.FileTooLarge, ReceiptScanError.UnsupportedFile -> ScanMessage.FileRejected
        is ReceiptScanError.Unreachable -> ScanMessage.Unreachable
        is ReceiptScanError.ExtractionFailed -> if (error.documentId != null) ScanMessage.FailedPhotoKept else ScanMessage.Failed
        ReceiptScanError.Unauthorized, is ReceiptScanError.Invalid -> ScanMessage.Failed
    }
}

/** `YYYY-MM-DDTHH:MM:SS` (no zone) as printed on the receipt; null when absent or not a date-time. */
fun parseReceiptLocal(text: String?): LocalDateTime? = text?.let {
    try {
        LocalDateTime.parse(it.trim())
    } catch (_: DateTimeParseException) {
        null
    }
}

/** The receipt's wall-clock time in the phone's zone (the contract: the receipt doesn't say, the phone does). */
fun receiptInstant(local: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Instant = local.atZone(zone).toInstant()

/**
 * A service scan carried from the expense form to the service record form (D-A56, "Log as service record instead"),
 * as a navigation argument: the response as JSON, so the record form fills itself exactly as after its own scan,
 * also after the process was restarted. Model name and quota are left out; nulls are omitted ([ApiJson]).
 */
fun encodeScanHandoff(response: ServiceReceiptScanResponse): String =
    ApiJson.encodeToString(ServiceReceiptScanResponse.serializer(), response.copy(model = null, scansRemainingToday = null))

/** The other side of [encodeScanHandoff]; null for no argument or one that doesn't parse. */
fun decodeScanHandoff(text: String?): ServiceReceiptScanResponse? = text?.let {
    runCatching { ApiJson.decodeFromString(ServiceReceiptScanResponse.serializer(), it) }.getOrNull()
}

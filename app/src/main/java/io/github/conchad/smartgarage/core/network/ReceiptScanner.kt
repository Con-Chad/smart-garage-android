package io.github.conchad.smartgarage.core.network

import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The receipt scanner hook (D-A27): the fill-up form's "Scan receipt" calls [scan] with the photo it just took and
 * gets back the server's draft, or a reason it couldn't. The contract is `docs/mobile/RECEIPT-SCANNER.md`.
 *
 * Tonight only [StubReceiptScanner] is bound (see `di/ReceiptScannerModule.kt`): it answers
 * [ReceiptScanResult.Unavailable] and the form stays manual. The real client replaces that one binding and must:
 *
 * - `POST /api/v1/fuel/receipt-scan` as `multipart/form-data` with the text part `vehicleId` and the file part
 *   `file` (JPEG from `CameraCapture`, already ≤ 1600 px / quality 80, so well under the 7 MB limit), through
 *   [ApiClient] so the device token, Cloudflare headers and the no-redirect rule apply. `ApiClient.writeMultipart`
 *   already builds that body; send an `Idempotency-Key` so a retry replays the stored draft (D-033).
 * - Give up after 25 s (the server's own model timeout) and report [ReceiptScanError.Unreachable].
 * - Decode a 2xx as [ReceiptScanResponse] (`draft` is null when `isFuelReceipt` is false).
 * - Map the error body's `code` / status to [ReceiptScanError]: 401 → [ReceiptScanError.Unauthorized],
 *   403 → [ReceiptScanError.Forbidden], 404 → [ReceiptScanError.NotFound] (feature off or vehicle not visible: hide
 *   the button), 413 → [ReceiptScanError.FileTooLarge], 415 → [ReceiptScanError.UnsupportedFile],
 *   422 → [ReceiptScanError.Invalid], 429 → [ReceiptScanError.RateLimited] with `Retry-After`,
 *   502 → [ReceiptScanError.ExtractionFailed] carrying the body's `documentId` (the photo was saved; it can still be
 *   attached to a manual entry).
 *
 * Next steps, in order, once the server has the feature on (none of this exists yet):
 * 1. Prefill: `FuelFormViewModel` takes [ReceiptDraft] into the form fields (`filledAtLocal` + the device zone →
 *    `filledAt`; `liters`, `totalCostCents` in display units; `stationName`; `fuelGrade`/`stationAddress` into the
 *    note) and keeps `documentId` as `FuelEntryInput.receiptDocumentId`.
 * 2. Confidence highlighting: fields with `confidence` < 0.8 and everything named in `warnings` get the form's
 *    supporting/error text so the user checks them; `source` shows "11.482 gal @ $3.459" under the volume.
 * 3. The attach-anyway path for `isFuelReceipt == false` and [ReceiptScanError.ExtractionFailed]: keep
 *    `documentId` so the photo is linked to the manual entry.
 * 4. Offline: queue the photo plus an "unscanned" outbox item; scan when back online and notify to review.
 */
interface ReceiptScanner {
    /** Sends [photo] (a JPEG file from `CameraCapture`) for [vehicleId]; never throws for server answers. */
    suspend fun scan(vehicleId: String, photo: File): ReceiptScanResult
}

sealed interface ReceiptScanResult {
    data class Success(val response: ReceiptScanResponse) : ReceiptScanResult

    data class Error(val error: ReceiptScanError) : ReceiptScanResult

    /** No scanner is wired into this build (the stub), or the server reported the feature off. */
    data object Unavailable : ReceiptScanResult
}

/** `POST /api/v1/fuel/receipt-scan` 200 body; `draft` is null when the photo isn't a fuel receipt. */
@Serializable
data class ReceiptScanResponse(
    val documentId: String,
    val isFuelReceipt: Boolean = false,
    val draft: ReceiptDraft? = null,
    /** Codes such as `time_missing`, `total_mismatch`, `currency_assumed`; see the contract's table. */
    val warnings: List<String> = emptyList(),
    val model: String? = null,
    val scansRemainingToday: Int? = null,
)

/** The server's reading of the receipt, in stored units (litres, integer cents). Nothing is saved from it directly. */
@Serializable
data class ReceiptDraft(
    /** Local date-time printed on the receipt, `YYYY-MM-DDTHH:MM:SS`, no zone; combine with the device zone. */
    val filledAtLocal: String? = null,
    val liters: Double? = null,
    val totalCostCents: Long? = null,
    val currency: String? = null,
    /** Informational, cents per litre. */
    val pricePerLiterCents: Double? = null,
    val stationName: String? = null,
    val stationAddress: String? = null,
    val fuelGrade: String? = null,
    /** 0–1 per draft field name; 0 when the field is null. */
    val confidence: Map<String, Double> = emptyMap(),
    val source: ReceiptSource? = null,
)

/** The values as printed, before unit conversion, for the confirm screen. */
@Serializable
data class ReceiptSource(
    val volume: ReceiptSourceQuantity? = null,
    val pricePerUnit: ReceiptSourceMoney? = null,
    val total: ReceiptSourceMoney? = null,
)

@Serializable
data class ReceiptSourceQuantity(val value: Double? = null, val unit: String? = null)

@Serializable
data class ReceiptSourceMoney(val value: Double? = null, val currency: String? = null)

/** One per error row of the contract, plus [Unreachable] for no answer at all. */
sealed interface ReceiptScanError {
    /** 401: the device token is gone; the usual sign-in-again handling applies. */
    data object Unauthorized : ReceiptScanError

    /** 403: the vehicle is view-only for this user. */
    data object Forbidden : ReceiptScanError

    /** 404: the feature is off on this server, or the vehicle isn't visible. Hide the button. */
    data object NotFound : ReceiptScanError

    /** 413 `file_too_large`. */
    data object FileTooLarge : ReceiptScanError

    /** 415 `unsupported_file_type` / `multipart_required`. */
    data object UnsupportedFile : ReceiptScanError

    /** 422 `validation_failed`: a part was missing; a client bug. */
    data class Invalid(val message: String?) : ReceiptScanError

    /** 429 `rate_limited`: the daily per-user limit; [retryAfterSeconds] from the `Retry-After` header. */
    data class RateLimited(val retryAfterSeconds: Long?) : ReceiptScanError

    /** 502 `extraction_failed`: the photo was saved as [documentId] but the model gave nothing usable. */
    data class ExtractionFailed(val documentId: String?) : ReceiptScanError

    /** Timeout or no connection; nothing is known about the server side. */
    data class Unreachable(val problem: ServerProblem) : ReceiptScanError
}

/**
 * The placeholder bound tonight: every scan is [ReceiptScanResult.Unavailable], so the form shows its snackbar and
 * stays manual. It never returns a `documentId`, so `FuelEntryInput.receiptDocumentId` stays null.
 */
@Singleton
class StubReceiptScanner @Inject constructor() : ReceiptScanner {
    override suspend fun scan(vehicleId: String, photo: File): ReceiptScanResult = ReceiptScanResult.Unavailable
}

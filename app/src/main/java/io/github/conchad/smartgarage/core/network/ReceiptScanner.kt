package io.github.conchad.smartgarage.core.network

import io.github.conchad.smartgarage.core.data.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The receipt scanner (D-A27 hook, real client D-A53): a form's "Scan receipt" sends the photo it just took and gets
 * back the server's draft, or a reason it couldn't. The contract is `docs/mobile/RECEIPT-SCANNER.md`.
 *
 * Both kinds `POST` `multipart/form-data` (text part `vehicleId`, file part `file`, a JPEG from `CameraCapture`
 * already ≤ 1600 px / quality 80) through [ApiClient], so the device token, the Cloudflare Access headers and the
 * no-redirect rule apply; each call carries a fresh `Idempotency-Key` (D-033). The AI key lives on the server only.
 */
interface ReceiptScanner {
    /**
     * `POST /api/v1/fuel/receipt-scan` with [photo] for [vehicleId]; never throws for server answers.
     *
     * Deliberately no host, base URL or endpoint parameter (D-A28): an implementation resolves the active profile's
     * `ServerConnection` itself, so the photo can only ever go to that profile's own server. It must not log the
     * response (station name and address are PII).
     */
    suspend fun scan(vehicleId: String, photo: File): ReceiptScanResult<ReceiptScanResponse>

    /** `POST /api/v1/records/receipt-scan` (D-044): a workshop invoice or other vehicle bill. Same rules as [scan]. */
    suspend fun scanService(vehicleId: String, photo: File): ReceiptScanResult<ServiceReceiptScanResponse>
}

sealed interface ReceiptScanResult<out T> {
    data class Success<T>(val response: T) : ReceiptScanResult<T>

    data class Error(val error: ReceiptScanError) : ReceiptScanResult<Nothing>

    /** No scanner is wired into this build, or nobody is signed in. */
    data object Unavailable : ReceiptScanResult<Nothing>
}

/** `documentId` the server saved the photo as, from a 200 or a 502; null when nothing was saved. */
fun ReceiptScanResult<*>.documentIdOrNull(): String? = when (this) {
    is ReceiptScanResult.Success -> when (val r = response) {
        is ReceiptScanResponse -> r.documentId
        is ServiceReceiptScanResponse -> r.documentId
        else -> null
    }
    is ReceiptScanResult.Error -> (error as? ReceiptScanError.ExtractionFailed)?.documentId
    ReceiptScanResult.Unavailable -> null
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

/** `POST /api/v1/records/receipt-scan` 200 body (D-044); `draft` is null when it isn't a vehicle bill. */
@Serializable
data class ServiceReceiptScanResponse(
    val documentId: String,
    val isServiceReceipt: Boolean = false,
    val draft: ServiceReceiptDraft? = null,
    /** `fuel_receipt`, `not_service_receipt`, `cost_breakdown_mismatch`, `service_type_unknown`, …; see the contract. */
    val warnings: List<String> = emptyList(),
    val model: String? = null,
    val scansRemainingToday: Int? = null,
)

/** A workshop invoice or vehicle bill as the server read it, in stored units (km, integer cents). */
@Serializable
data class ServiceReceiptDraft(
    /** Local date-time, `YYYY-MM-DDTHH:MM:SS`, no zone; usually noon (invoices rarely print a time). */
    val performedAtLocal: String? = null,
    val odometerKm: Int? = null,
    val vendorName: String? = null,
    val vendorAddress: String? = null,
    val invoiceNumber: String? = null,
    /** `MAINTENANCE`, `REPAIR`, `ACCESSORY`, `INSURANCE`, `TAX`, `REGISTRATION` or `OTHER`. */
    val expenseCategory: String = "OTHER",
    /** `record` or `expense`: which save screen fits; the user may switch. */
    val suggestedTarget: String = "record",
    val serviceTypes: List<ServiceTypeMatch> = emptyList(),
    /** The vehicle's active template-based schedule for the best service type; custom schedules are never matched. */
    val suggestedScheduleId: String? = null,
    /** Already in the `items[]` shape of `POST /records`. */
    val items: List<ServiceReceiptItem> = emptyList(),
    val otherLines: List<ServiceReceiptLine> = emptyList(),
    val labourCents: Long? = null,
    val partsCents: Long? = null,
    val taxCents: Long? = null,
    /** Final amount incl. tax; maps to the record's `costCents` or the expense's `amountCents`. */
    val totalCostCents: Long? = null,
    val currency: String? = null,
    /** Ready-made note (invoice number, other lines, advisories), ≤ 4000 chars; trim to 500 for an expense. */
    val note: String? = null,
    val confidence: Map<String, Double> = emptyMap(),
)

@Serializable
data class ServiceTypeMatch(
    val templateSlug: String? = null,
    val name: String? = null,
    val category: String? = null,
    val scheduleId: String? = null,
    val confidence: Double? = null,
)

@Serializable
data class ServiceReceiptItem(
    val category: String = "OTHER",
    val name: String? = null,
    val brand: String? = null,
    val partNumber: String? = null,
    val quantity: Double? = null,
    val unit: String? = null,
    val customUnit: String? = null,
    val costCents: Long? = null,
    val currency: String? = null,
)

@Serializable
data class ServiceReceiptLine(val kind: String? = null, val description: String? = null, val amountCents: Long? = null)

/** One per error row of the contract, plus [Unreachable] for no usable answer at all. */
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

    /** Timeout, no connection, or an answer that isn't the contract's; nothing is known about the server side. */
    data class Unreachable(val problem: ServerProblem) : ReceiptScanError
}

/**
 * The real client (D-A53): the active profile's connection through [ApiClient.postMultipartJson]. The request
 * timeout is [TIMEOUT_MS], above the server's own 25 s model timeout plus the upload, so a slow but successful
 * scan isn't cut off on the phone while the server still counts it against the daily limit.
 */
@Singleton
class ApiReceiptScanner @Inject constructor(
    private val api: ApiClient,
    private val profiles: ProfileStore,
) : ReceiptScanner {

    override suspend fun scan(vehicleId: String, photo: File): ReceiptScanResult<ReceiptScanResponse> =
        send(FUEL_PATH, vehicleId, photo, ReceiptScanResponse.serializer())

    override suspend fun scanService(vehicleId: String, photo: File): ReceiptScanResult<ServiceReceiptScanResponse> =
        send(SERVICE_PATH, vehicleId, photo, ServiceReceiptScanResponse.serializer())

    private suspend fun <T> send(path: String, vehicleId: String, photo: File, serializer: KSerializer<T>): ReceiptScanResult<T> {
        val profileId = profiles.current().active?.id ?: return ReceiptScanResult.Unavailable
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return ReceiptScanResult.Error(ReceiptScanError.Unauthorized)
        val bytes = withContext(Dispatchers.IO) { runCatching { photo.readBytes() }.getOrNull() }
            ?: return ReceiptScanResult.Error(ReceiptScanError.UnsupportedFile)
        val answer = api.postMultipartJson(
            connection = connection,
            path = path,
            idempotencyKey = UUID.randomUUID().toString(),
            fields = mapOf("vehicleId" to vehicleId),
            fileName = photo.name.ifBlank { "receipt.jpg" },
            mimeType = "image/jpeg",
            bytes = bytes,
            timeoutMillis = TIMEOUT_MS,
        )
        return interpret(answer, serializer)
    }

    companion object {
        const val FUEL_PATH = "/api/v1/fuel/receipt-scan"
        const val SERVICE_PATH = "/api/v1/records/receipt-scan"
        const val TIMEOUT_MS = 45_000L

        /** Status + body → result, per the contract's error table. Pure, for tests. */
        fun <T> interpret(answer: JsonAnswer, serializer: KSerializer<T>): ReceiptScanResult<T> = when (answer) {
            is JsonAnswer.Failed -> ReceiptScanResult.Error(ReceiptScanError.Unreachable(answer.problem))
            is JsonAnswer.Answered -> if (answer.status in 200..299) {
                runCatching { ApiJson.decodeFromJsonElement(serializer, answer.body) }
                    .fold(
                        onSuccess = { ReceiptScanResult.Success(it) },
                        onFailure = { ReceiptScanResult.Error(ReceiptScanError.Unreachable(ServerProblem.Unexpected(answer.status, null))) },
                    )
            } else {
                ReceiptScanResult.Error(errorOf(answer))
            }
        }

        private fun errorOf(answer: JsonAnswer.Answered): ReceiptScanError {
            val error = answer.body["error"] as? JsonObject
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            return when (answer.status) {
                401 -> ReceiptScanError.Unauthorized
                403 -> ReceiptScanError.Forbidden
                404 -> ReceiptScanError.NotFound
                413 -> ReceiptScanError.FileTooLarge
                415 -> ReceiptScanError.UnsupportedFile
                422 -> ReceiptScanError.Invalid(message)
                429 -> ReceiptScanError.RateLimited(answer.retryAfterSeconds)
                502 -> ReceiptScanError.ExtractionFailed(
                    ((answer.body["documentId"] ?: error?.get("documentId")) as? JsonPrimitive)?.contentOrNull,
                )
                else -> ReceiptScanError.Unreachable(ServerProblem.Unexpected(answer.status, message))
            }
        }
    }
}

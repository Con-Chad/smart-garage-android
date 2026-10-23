package io.github.conchad.smartgarage.core.network

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.data.ServerFeatures
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** The real receipt-scan client (D-A53): the multipart request, the contract's bodies and its error table. */
class ReceiptScannerTest {

    private fun answer(status: Int, body: String, retryAfter: Long? = null) =
        JsonAnswer.Answered(status, Json.parseToJsonElement(body) as JsonObject, retryAfter)

    private val fuelBody = """{
      "documentId": "doc1", "isFuelReceipt": true,
      "draft": {
        "filledAtLocal": "2026-10-20T14:32:00", "liters": 43.464, "totalCostCents": 3972, "currency": "USD",
        "pricePerLiterCents": 91.377, "stationName": "Shell", "stationAddress": "123 Main St, Springfield",
        "fuelGrade": "Regular 87",
        "confidence": { "filledAtLocal": 0.9, "liters": 0.98, "totalCostCents": 0.99, "stationName": 0.9 },
        "source": { "volume": { "value": 11.482, "unit": "gal" }, "pricePerUnit": { "value": 3.459, "currency": "USD" },
                    "total": { "value": 39.72, "currency": "USD" } }
      },
      "warnings": [], "model": "claude-sonnet-5-5", "scansRemainingToday": 29
    }"""

    private val serviceBody = """{
      "documentId": "doc2", "isServiceReceipt": true,
      "draft": {
        "performedAtLocal": "2026-10-15T12:00:00", "odometerKm": 48210, "vendorName": "Autohaus Müller",
        "vendorAddress": "Hauptstr. 1", "invoiceNumber": "R-2026-1042", "expenseCategory": "MAINTENANCE",
        "suggestedTarget": "record",
        "serviceTypes": [
          { "templateSlug": "oil_change", "name": "Engine oil & filter", "category": "ENGINE", "scheduleId": "sch1", "confidence": 0.95 },
          { "templateSlug": "cabin_filter", "name": "Cabin / pollen filter", "category": "FILTERS", "scheduleId": null, "confidence": 0.8 }
        ],
        "suggestedScheduleId": "sch1",
        "items": [
          { "category": "ENGINE_OIL", "name": "Motoröl 5W-30", "brand": "Castrol", "partNumber": null, "quantity": 4.5, "unit": "LITERS", "customUnit": null, "costCents": 6210, "currency": "EUR" }
        ],
        "otherLines": [ { "kind": "labour", "description": "Arbeitslohn", "amountCents": 12000 } ],
        "labourCents": 12000, "partsCents": 7700, "taxCents": 3743, "totalCostCents": 23443, "currency": "EUR",
        "note": "Invoice R-2026-1042",
        "confidence": { "performedAtLocal": 0.9, "odometerKm": 0.95, "totalCostCents": 0.98, "vendorName": 0.9, "serviceTypes": 0.85 },
        "source": { "odometer": { "value": 48210, "unit": "km" }, "total": 234.43 }
      },
      "warnings": [], "model": "claude-sonnet-5-5", "scansRemainingToday": 27
    }"""

    @Test
    fun `fuel 200 decodes the whole draft`() {
        val result = ApiReceiptScanner.interpret(answer(200, fuelBody), ReceiptScanResponse.serializer())
        val response = (result as ReceiptScanResult.Success).response
        assertThat(response.documentId).isEqualTo("doc1")
        assertThat(response.isFuelReceipt).isTrue()
        val draft = response.draft!!
        assertThat(draft.liters).isEqualTo(43.464)
        assertThat(draft.totalCostCents).isEqualTo(3972)
        assertThat(draft.fuelGrade).isEqualTo("Regular 87")
        assertThat(draft.source?.volume?.unit).isEqualTo("gal")
        assertThat(result.documentIdOrNull()).isEqualTo("doc1")
    }

    @Test
    fun `service 200 decodes items, schedule match and source with a bare total`() {
        val result = ApiReceiptScanner.interpret(answer(200, serviceBody), ServiceReceiptScanResponse.serializer())
        val draft = (result as ReceiptScanResult.Success).response.draft!!
        assertThat(draft.suggestedScheduleId).isEqualTo("sch1")
        assertThat(draft.serviceTypes.map { it.scheduleId }).containsExactly("sch1", null).inOrder()
        assertThat(draft.items.single().unit).isEqualTo("LITERS")
        assertThat(draft.totalCostCents).isEqualTo(23443)
        assertThat(draft.otherLines.single().amountCents).isEqualTo(12000)
        assertThat(result.documentIdOrNull()).isEqualTo("doc2")
    }

    @Test
    fun `not a receipt decodes with a null draft`() {
        val result = ApiReceiptScanner.interpret(
            answer(200, """{"documentId":"d","isServiceReceipt":false,"draft":null,"warnings":["fuel_receipt"]}"""),
            ServiceReceiptScanResponse.serializer(),
        )
        val response = (result as ReceiptScanResult.Success).response
        assertThat(response.draft).isNull()
        assertThat(response.warnings).containsExactly("fuel_receipt")
    }

    @Test
    fun `error statuses map to the contract's errors`() {
        fun err(status: Int, body: String = """{"error":{"code":"x","message":"m"}}""", retry: Long? = null) =
            (ApiReceiptScanner.interpret(answer(status, body, retry), ReceiptScanResponse.serializer()) as ReceiptScanResult.Error).error

        assertThat(err(401)).isEqualTo(ReceiptScanError.Unauthorized)
        assertThat(err(403)).isEqualTo(ReceiptScanError.Forbidden)
        assertThat(err(404)).isEqualTo(ReceiptScanError.NotFound)
        assertThat(err(413)).isEqualTo(ReceiptScanError.FileTooLarge)
        assertThat(err(415)).isEqualTo(ReceiptScanError.UnsupportedFile)
        assertThat(err(422)).isEqualTo(ReceiptScanError.Invalid("m"))
        assertThat(err(429, retry = 3600)).isEqualTo(ReceiptScanError.RateLimited(3600))
        assertThat(err(502, """{"error":{"code":"extraction_failed","message":"m"},"documentId":"doc9"}"""))
            .isEqualTo(ReceiptScanError.ExtractionFailed("doc9"))
        assertThat(err(502, """{"error":{"code":"extraction_failed","message":"m"}}""")).isEqualTo(ReceiptScanError.ExtractionFailed(null))
        assertThat(err(500)).isEqualTo(ReceiptScanError.Unreachable(ServerProblem.Unexpected(500, "m")))
    }

    @Test
    fun `a 502 keeps the saved photo's document id`() {
        val result = ApiReceiptScanner.interpret(
            answer(502, """{"error":{"code":"extraction_failed","message":"m"},"documentId":"doc9"}"""),
            ServiceReceiptScanResponse.serializer(),
        )
        assertThat(result.documentIdOrNull()).isEqualTo("doc9")
    }

    @Test
    fun `no answer and a malformed 200 are unreachable`() {
        val none = ApiReceiptScanner.interpret(JsonAnswer.Failed(ServerProblem.Unreachable(NetworkFailure.Timeout)), ReceiptScanResponse.serializer())
        assertThat((none as ReceiptScanResult.Error).error).isEqualTo(ReceiptScanError.Unreachable(ServerProblem.Unreachable(NetworkFailure.Timeout)))
        val malformed = ApiReceiptScanner.interpret(answer(200, """{"isFuelReceipt":true}"""), ReceiptScanResponse.serializer())
        assertThat((malformed as ReceiptScanResult.Error).error).isInstanceOf(ReceiptScanError.Unreachable::class.java)
    }

    @Test
    fun `the upload is multipart with the device token, Cloudflare headers and an idempotency key`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val api = ApiClient(
            HttpClientFactory.create(
                "test",
                MockEngine { request ->
                    requests += request
                    respond(
                        """{"error":{"code":"rate_limited","message":"m"}}""",
                        HttpStatusCode.TooManyRequests,
                        headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("120")),
                    )
                },
            ),
        )
        val connection = ServerConnection("https://garage.example.com/", CfAccessCredentials("id.access", "secret"), token = "sgd_abc")
        val result = api.postMultipartJson(
            connection, ApiReceiptScanner.SERVICE_PATH, "key-1", mapOf("vehicleId" to "veh1"), "r.jpg", "image/jpeg", byteArrayOf(1, 2, 3), 45_000,
        )

        assertThat(result).isInstanceOf(JsonAnswer.Answered::class.java)
        assertThat((result as JsonAnswer.Answered).status).isEqualTo(429)
        assertThat(result.retryAfterSeconds).isEqualTo(120)
        val sent = requests.single()
        assertThat(sent.method).isEqualTo(HttpMethod.Post)
        assertThat(sent.url.toString()).isEqualTo("https://garage.example.com/api/v1/records/receipt-scan")
        assertThat(sent.headers[HttpHeaders.Authorization]).isEqualTo("Bearer sgd_abc")
        assertThat(sent.headers["CF-Access-Client-Id"]).isEqualTo("id.access")
        assertThat(sent.headers["CF-Access-Client-Secret"]).isEqualTo("secret")
        assertThat(sent.headers["Idempotency-Key"]).isEqualTo("key-1")
        assertThat(sent.body.contentType?.match(ContentType.MultiPart.FormData)).isTrue()
        assertThat(ApiReceiptScanner.interpret(result, ReceiptScanResponse.serializer()))
            .isEqualTo(ReceiptScanResult.Error(ReceiptScanError.RateLimited(120)))
    }

    @Test
    fun `scanner kinds default to fuel only on servers before D-044`() {
        assertThat(MeFeatures(receiptScanner = true).scannerKinds()).containsExactly(RECEIPT_KIND_FUEL)
        assertThat(MeFeatures(receiptScanner = true, receiptScannerKinds = listOf("fuel", "service", "future")).scannerKinds())
            .containsExactly("fuel", "service").inOrder()
        assertThat(MeFeatures(receiptScanner = true, receiptScannerKinds = listOf("service")).scannerKinds()).containsExactly("service")
        assertThat(MeFeatures(receiptScanner = false, receiptScannerKinds = listOf("fuel")).scannerKinds()).isEmpty()
        val me = ApiJson.decodeFromString(
            MeResponse.serializer(),
            """{"user":{"id":"u","username":"c"},"features":{"receiptScanner":true,"receiptScannerKinds":["fuel","service"]}}""",
        )
        assertThat(me.features.scannerKinds()).containsExactly("fuel", "service").inOrder()
    }

    @Test
    fun `server features gate each scan button`() {
        assertThat(ServerFeatures(receiptScanner = true).scansFuel).isTrue()
        assertThat(ServerFeatures(receiptScanner = true).scansService).isFalse()
        assertThat(ServerFeatures(receiptScanner = true, receiptScannerKinds = listOf("service")).scansFuel).isFalse()
        assertThat(ServerFeatures(receiptScanner = true, receiptScannerKinds = listOf("service")).scansService).isTrue()
        assertThat(ServerFeatures(receiptScanner = false, receiptScannerKinds = listOf("fuel", "service")).scansService).isFalse()
        // A profile stored before D-A53 has no kinds and keeps its fuel button.
        val stored = ApiJson.decodeFromString(ServerFeatures.serializer(), """{"receiptScanner":true}""")
        assertThat(stored.scansFuel).isTrue()
        assertThat(stored.scansService).isFalse()
    }
}

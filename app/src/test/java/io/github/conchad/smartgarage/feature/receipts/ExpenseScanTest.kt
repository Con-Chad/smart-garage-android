package io.github.conchad.smartgarage.feature.receipts

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.network.ServiceReceiptDraft
import io.github.conchad.smartgarage.core.network.ServiceReceiptItem
import io.github.conchad.smartgarage.core.network.ServiceReceiptScanResponse
import io.github.conchad.smartgarage.feature.expenses.ExpenseFormState
import io.github.conchad.smartgarage.feature.expenses.scannedDescription
import io.github.conchad.smartgarage.feature.expenses.withServiceScan
import io.github.conchad.smartgarage.feature.maintenance.RecordFormState
import io.github.conchad.smartgarage.feature.maintenance.withServiceDraft
import io.github.conchad.smartgarage.ui.navigation.RecordFormRoute
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** A bill scanned on the expense form, the hand-over to the record form, and the gallery copy/size rules (D-A56). */
class ExpenseScanTest {

    private val metric = Formatter(DisplayPreferences(currency = "EUR", distanceUnit = "km", volumeUnit = "l", timezone = "Europe/Berlin"), Locale.US)
    private val berlin = ZoneId.of("Europe/Berlin")

    private val bill = ServiceReceiptDraft(
        performedAtLocal = "2026-10-15T12:00:00",
        vendorName = "Reifen Schmidt",
        expenseCategory = "REPAIR",
        suggestedTarget = "expense",
        totalCostCents = 18950,
        currency = "EUR",
        note = "Invoice 77-123\nPuncture repair",
        confidence = mapOf("performedAtLocal" to 0.95, "totalCostCents" to 0.6, "vendorName" to 0.9),
    )

    private val form = ExpenseFormState(vehicleId = "veh1", formatter = metric, date = LocalDate.of(2026, 10, 22))

    @Test
    fun `a bill fills date, category, amount, currency and description and links the document`() {
        val state = form.withServiceScan(ServiceReceiptScanResponse("doc9", true, bill))

        assertThat(state.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(state.category).isEqualTo("REPAIR")
        assertThat(state.amount).isEqualTo("189.50")
        assertThat(state.description).isEqualTo("Reifen Schmidt\nInvoice 77-123\nPuncture repair")
        assertThat(state.receiptDocumentId).isEqualTo("doc9")
        assertThat(state.recordHandoff).isNull()
        assertThat(state.dirty).isTrue()
        assertThat(state.scanReview).isEqualTo(ScanReview(check = listOf(ScanField.Total)))

        val input = state.input(zone = berlin)
        assertThat(input.category).isEqualTo("REPAIR")
        assertThat(input.amountCents).isEqualTo(18950)
        assertThat(input.currency).isEqualTo("EUR")
        assertThat(input.occurredAt).isEqualTo("2026-10-15T10:00:00Z")
        assertThat(input.receiptDocumentId).isEqualTo("doc9")
    }

    @Test
    fun `a foreign currency is kept and an unknown category leaves the form's`() {
        val state = form.copy(category = "TAX").withServiceScan(ServiceReceiptScanResponse("d", true, bill.copy(currency = "chf", expenseCategory = "SOMETHING_NEW")))
        assertThat(state.category).isEqualTo("TAX")
        assertThat(state.foreignCurrency).isEqualTo("CHF")
        assertThat(state.input().currency).isEqualTo("CHF")
    }

    @Test
    fun `null draft fields keep what the form had`() {
        val before = form.copy(amount = "12.00", description = "Wash")
        val state = before.withServiceScan(ServiceReceiptScanResponse("d", true, ServiceReceiptDraft()))
        assertThat(state.amount).isEqualTo("12.00")
        assertThat(state.description).isEqualTo("Wash")
        assertThat(state.date).isEqualTo(before.date)
        assertThat(state.category).isEqualTo("OTHER")
        assertThat(state.receiptDocumentId).isEqualTo("d")
    }

    @Test
    fun `no draft links the photo but fills nothing`() {
        val state = form.withServiceScan(ServiceReceiptScanResponse("d", false, null, listOf("not_service_receipt")))
        assertThat(state.receiptDocumentId).isEqualTo("d")
        assertThat(state.amount).isEmpty()
        assertThat(state.recordHandoff).isNull()
    }

    @Test
    fun `the description adds vendor and note to what the user typed, without repeats, at most 500 chars`() {
        assertThat(scannedDescription("Tyres", "Reifen Schmidt", "Tyres")).isEqualTo("Tyres\nReifen Schmidt")
        assertThat(scannedDescription("", null, null)).isEmpty()
        assertThat(scannedDescription("", "V", "n".repeat(800))).hasLength(500)
    }

    @Test
    fun `expense review drops record-only warnings`() {
        val response = ServiceReceiptScanResponse(
            "d", true, bill.copy(confidence = mapOf("performedAtLocal" to 1.0, "totalCostCents" to 1.0, "vendorName" to 1.0)),
            warnings = listOf("service_type_unknown", "odometer_implausible", "items_truncated", "currency_assumed"),
        )
        assertThat(expenseReview(response)).isEqualTo(ScanReview(warnings = listOf(ScanWarning.CurrencyAssumed)))
    }

    @Test
    fun `a workshop service offers the record form with the whole scan`() {
        val service = bill.copy(
            suggestedTarget = "record",
            expenseCategory = "MAINTENANCE",
            odometerKm = 48210,
            items = listOf(ServiceReceiptItem(category = "ENGINE_OIL", name = "Öl", quantity = 4.5, unit = "LITERS", costCents = 6210)),
        )
        val response = ServiceReceiptScanResponse("doc5", true, service, model = "m", scansRemainingToday = 3)
        val state = form.withServiceScan(response)

        assertThat(state.scanReview?.warnings).contains(ScanWarning.SuggestRecord)
        val route = state.recordHandoff!!
        assertThat(route.vehicleId).isEqualTo("veh1")
        assertThat(route.recordId).isNull()

        // The record form decodes the hand-over and fills itself as after its own scan.
        val decoded = decodeScanHandoff(route.scan)!!
        assertThat(decoded).isEqualTo(response.copy(model = null, scansRemainingToday = null))
        var key = 1L
        val record = RecordFormState(vehicleId = "veh1", formatter = metric).withServiceDraft(decoded) { key++ }
        assertThat(record.cost).isEqualTo("189.50")
        assertThat(record.odometer).isEqualTo("48210")
        assertThat(record.vendor).isEqualTo("Reifen Schmidt")
        assertThat(record.items.map { it.name }).containsExactly("Öl")
        assertThat(record.input(zone = berlin).receiptDocumentId).isEqualTo("doc5")
    }

    @Test
    fun `a hand-over that doesn't parse is ignored`() {
        assertThat(decodeScanHandoff(null)).isNull()
        assertThat(decodeScanHandoff("{not json")).isNull()
        assertThat(RecordFormRoute(vehicleId = "v").scan).isNull()
    }

    @Test
    fun `gallery photos are copied up to a cap and uploads respect the server limit`() {
        val out = ByteArrayOutputStream()
        assertThat(ReceiptCapture.copyCapped(ByteArrayInputStream(ByteArray(1000) { 7 }), out, limit = 1000)).isTrue()
        assertThat(out.size()).isEqualTo(1000)
        assertThat(ReceiptCapture.copyCapped(ByteArrayInputStream(ByteArray(1001)), ByteArrayOutputStream(), limit = 1000)).isFalse()
        assertThat(ReceiptCapture.copyCapped(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), limit = 1000)).isFalse()

        assertThat(ReceiptCapture.fitsUpload(400_000)).isTrue()
        assertThat(ReceiptCapture.fitsUpload(7_000_000)).isTrue()
        assertThat(ReceiptCapture.fitsUpload(7_000_001)).isFalse()
        assertThat(ReceiptCapture.fitsUpload(0)).isFalse()
    }
}

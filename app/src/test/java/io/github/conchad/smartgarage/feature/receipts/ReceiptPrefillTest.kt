package io.github.conchad.smartgarage.feature.receipts

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.MaintenanceItem
import io.github.conchad.smartgarage.core.model.Schedule
import io.github.conchad.smartgarage.core.network.ReceiptDraft
import io.github.conchad.smartgarage.core.network.ReceiptScanError
import io.github.conchad.smartgarage.core.network.ReceiptScanResponse
import io.github.conchad.smartgarage.core.network.ReceiptScanResult
import io.github.conchad.smartgarage.core.network.ServiceReceiptDraft
import io.github.conchad.smartgarage.core.network.ServiceReceiptItem
import io.github.conchad.smartgarage.core.network.ServiceReceiptScanResponse
import io.github.conchad.smartgarage.core.network.ServiceTypeMatch
import io.github.conchad.smartgarage.feature.expenses.ExpenseFormState
import io.github.conchad.smartgarage.feature.expenses.withRoute
import io.github.conchad.smartgarage.feature.fuel.FuelFormState
import io.github.conchad.smartgarage.feature.fuel.withFuelDraft
import io.github.conchad.smartgarage.feature.maintenance.RecordFormState
import io.github.conchad.smartgarage.feature.maintenance.ScheduleOption
import io.github.conchad.smartgarage.feature.maintenance.withServiceDraft
import io.github.conchad.smartgarage.ui.navigation.ExpenseFormRoute
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Draft → form fields, the review note and the snackbar messages (D-A53). */
class ReceiptPrefillTest {

    private val metric = Formatter(DisplayPreferences(currency = "EUR", distanceUnit = "km", volumeUnit = "l", timezone = "Europe/Berlin"), Locale.US)
    private val us = Formatter(DisplayPreferences(currency = "USD", distanceUnit = "mi", volumeUnit = "gal", timezone = "America/Chicago"), Locale.US)
    private val chicago = ZoneId.of("America/Chicago")

    private val fuelDraft = ReceiptDraft(
        filledAtLocal = "2026-10-20T14:32:00",
        liters = 43.464,
        totalCostCents = 3972,
        currency = "USD",
        pricePerLiterCents = 91.377,
        stationName = "Shell",
        stationAddress = "123 Main St",
        fuelGrade = "Regular 87",
        confidence = mapOf("filledAtLocal" to 0.9, "liters" to 0.98, "totalCostCents" to 0.99, "stationName" to 0.5),
    )

    // ── Fuel ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `fuel draft fills every field in display units and keeps the document`() {
        val state = FuelFormState(formatter = us, odometer = "31000", date = LocalDate.of(2026, 10, 22))
            .withFuelDraft(ReceiptScanResponse(documentId = "doc1", isFuelReceipt = true, draft = fuelDraft))

        assertThat(state.date).isEqualTo(LocalDate.of(2026, 10, 20))
        assertThat(state.volume).isEqualTo("11.482")
        assertThat(state.price).isEqualTo("3.459")
        assertThat(state.total).isEqualTo("39.72")
        assertThat(state.station).isEqualTo("Shell")
        assertThat(state.notes).isEqualTo("Regular 87\n123 Main St")
        assertThat(state.odometer).isEqualTo("31000")
        assertThat(state.receiptDocumentId).isEqualTo("doc1")
        assertThat(state.dirty).isTrue()
        assertThat(state.scanReview).isEqualTo(ScanReview(check = listOf(ScanField.Station)))

        val input = state.input(now = Instant.parse("2026-10-22T12:00:00Z"), zone = chicago)
        assertThat(input.receiptDocumentId).isEqualTo("doc1")
        assertThat(input.totalCostCents).isEqualTo(3972)
        assertThat(input.liters).isWithin(0.001).of(43.464)
        assertThat(input.currency).isEqualTo("USD")
        // The printed local time in the phone's zone (CDT, UTC-5).
        assertThat(input.filledAt).isEqualTo("2026-10-20T19:32:00Z")
    }

    @Test
    fun `changing the date after the scan drops the printed time`() {
        val state = FuelFormState(formatter = metric).withFuelDraft(ReceiptScanResponse("d", true, fuelDraft)).copy(date = LocalDate.of(2026, 10, 18))
        assertThat(state.input(now = Instant.parse("2026-10-22T12:00:00Z")).filledAt).isEqualTo("2026-10-18T10:00:00Z")
    }

    @Test
    fun `a foreign receipt currency is sent and flagged`() {
        val state = FuelFormState(formatter = metric).withFuelDraft(ReceiptScanResponse("d", true, fuelDraft))
        assertThat(state.foreignCurrency).isEqualTo("USD")
        assertThat(state.input().currency).isEqualTo("USD")
        assertThat(FuelFormState(formatter = metric).input().currency).isEqualTo("EUR")
    }

    @Test
    fun `missing price per unit is derived from total and volume`() {
        val draft = fuelDraft.copy(pricePerLiterCents = null, liters = 40.0, totalCostCents = 7000, currency = "EUR")
        val state = FuelFormState(formatter = metric, price = "1.5").withFuelDraft(ReceiptScanResponse("d", true, draft))
        assertThat(state.price).isEqualTo("1.75")
    }

    @Test
    fun `null draft fields keep what the form had`() {
        val draft = ReceiptDraft(totalCostCents = 5000)
        val state = FuelFormState(formatter = metric, volume = "30", station = "Aral", notes = "x").withFuelDraft(ReceiptScanResponse("d", true, draft))
        assertThat(state.volume).isEqualTo("30")
        assertThat(state.station).isEqualTo("Aral")
        assertThat(state.notes).isEqualTo("x")
        assertThat(state.total).isEqualTo("50.00")
        assertThat(state.scanReview?.check).containsExactly(ScanField.Total)
    }

    @Test
    fun `fuel messages follow the contract`() {
        val ok: ReceiptScanResult<ReceiptScanResponse> = ReceiptScanResult.Success(ReceiptScanResponse("d", true, fuelDraft))
        assertThat(ok.fuelMessage()).isNull()
        assertThat(ReceiptScanResult.Success(ReceiptScanResponse("d", false, null, listOf("not_fuel_receipt"))).fuelMessage())
            .isEqualTo(ScanMessage.NotFuelReceipt)
        assertThat(ReceiptScanResult.Error(ReceiptScanError.RateLimited(60)).fuelMessage()).isEqualTo(ScanMessage.RateLimited)
        assertThat(ReceiptScanResult.Error(ReceiptScanError.ExtractionFailed("d")).fuelMessage()).isEqualTo(ScanMessage.FailedPhotoKept)
        assertThat(ReceiptScanResult.Error(ReceiptScanError.ExtractionFailed(null)).fuelMessage()).isEqualTo(ScanMessage.Failed)
        assertThat(ReceiptScanResult.Error(ReceiptScanError.NotFound).fuelMessage()).isEqualTo(ScanMessage.Unavailable)
        assertThat(ReceiptScanResult.Error(ReceiptScanError.Forbidden).fuelMessage()).isEqualTo(ScanMessage.ViewOnly)
    }

    @Test
    fun `warnings fold into actionable notes and unknown codes are ignored`() {
        val review = fuelReview(
            ReceiptScanResponse("d", true, fuelDraft.copy(confidence = mapOf("filledAtLocal" to 0.5, "liters" to 0.5, "totalCostCents" to 0.5, "stationName" to 1.0)),
                listOf("total_mismatch", "currency_assumed", "time_missing", "brand_new_code")),
        )
        assertThat(review.check).containsExactly(ScanField.Date, ScanField.Volume, ScanField.Total).inOrder()
        assertThat(review.warnings).containsExactly(ScanWarning.AmountsMismatch, ScanWarning.CurrencyAssumed).inOrder()
    }

    // ── Service ──────────────────────────────────────────────────────────────────────────────────────────────

    private val oil = Schedule(id = "sch1", vehicleId = "veh1", templateId = "t1", itemDefaults = listOf(MaintenanceItem(name = "Default oil")))
    private val brakes = Schedule(id = "sch2", vehicleId = "veh1", templateId = "t2")
    private val recordForm = RecordFormState(
        vehicleId = "veh1",
        schedules = listOf(ScheduleOption(oil, "Oil change"), ScheduleOption(brakes, "Brakes")),
        odometer = "48000",
        formatter = metric,
    )

    private val serviceDraft = ServiceReceiptDraft(
        performedAtLocal = "2026-10-15T12:00:00",
        odometerKm = 48210,
        vendorName = "Autohaus Müller",
        expenseCategory = "MAINTENANCE",
        suggestedTarget = "record",
        serviceTypes = listOf(
            ServiceTypeMatch(templateSlug = "oil_change", name = "Engine oil & filter", scheduleId = "sch1", confidence = 0.95),
            ServiceTypeMatch(templateSlug = "cabin_filter", name = "Cabin / pollen filter", scheduleId = null, confidence = 0.8),
        ),
        suggestedScheduleId = "sch1",
        items = listOf(
            ServiceReceiptItem(category = "ENGINE_OIL", name = "Motoröl 5W-30", brand = "Castrol", quantity = 4.5, unit = "LITERS", costCents = 6210, currency = "EUR"),
            ServiceReceiptItem(category = "OIL_FILTER", name = "Ölfilter", partNumber = "HU 6004 x", quantity = 1.0, unit = "PIECES", costCents = 1490),
        ),
        totalCostCents = 23443,
        currency = "EUR",
        note = "Invoice R-2026-1042",
        confidence = mapOf("performedAtLocal" to 0.9, "odometerKm" to 0.95, "totalCostCents" to 0.98, "vendorName" to 0.9, "serviceTypes" to 0.85),
    )

    private fun keys(): () -> Long {
        var k = 100L
        return { k++ }
    }

    @Test
    fun `service draft fills the record and preselects the matched schedule`() {
        val state = recordForm.withServiceDraft(ServiceReceiptScanResponse("doc2", true, serviceDraft), keys())

        assertThat(state.scheduleId).isEqualTo("sch1")
        assertThat(state.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(state.odometer).isEqualTo("48210")
        assertThat(state.cost).isEqualTo("234.43")
        assertThat(state.vendor).isEqualTo("Autohaus Müller")
        assertThat(state.notes).isEqualTo("Invoice R-2026-1042")
        assertThat(state.items.map { it.name }).containsExactly("Motoröl 5W-30", "Ölfilter").inOrder()
        assertThat(state.items.first().base.unit).isEqualTo("LITERS")
        assertThat(state.items.first().base.brand).isEqualTo("Castrol")
        assertThat(state.items.map { it.key }).containsExactly(100L, 101L).inOrder()
        assertThat(state.alsoCovers).containsExactly("Cabin / pollen filter")
        assertThat(state.scanReview).isEqualTo(ScanReview())

        val input = state.input(zone = ZoneId.of("Europe/Berlin"))
        assertThat(input.receiptDocumentId).isEqualTo("doc2")
        assertThat(input.costCents).isEqualTo(23443)
        assertThat(input.currency).isEqualTo("EUR")
        assertThat(input.vendorName).isEqualTo("Autohaus Müller")
        assertThat(input.performedAt).isEqualTo("2026-10-15T10:00:00Z")
        assertThat(input.items!!.map { it.costCents }).containsExactly(6210L, 1490L).inOrder()
    }

    @Test
    fun `without a matched schedule the picker stays with the user`() {
        val draft = serviceDraft.copy(suggestedScheduleId = null, serviceTypes = emptyList())
        val state = recordForm.withServiceDraft(ServiceReceiptScanResponse("d", true, draft, listOf("service_type_unknown")), keys())
        assertThat(state.scheduleId).isNull()
        assertThat(state.canSave).isFalse()
        assertThat(state.alsoCovers).isEmpty()
        assertThat(state.scanReview?.warnings).containsExactly(ScanWarning.PickSchedule)
    }

    @Test
    fun `a locked form keeps its schedule`() {
        val locked = recordForm.copy(scheduleId = "sch2", locked = true)
        val state = locked.withServiceDraft(ServiceReceiptScanResponse("d", true, serviceDraft), keys())
        assertThat(state.scheduleId).isEqualTo("sch2")
        assertThat(state.alsoCovers).containsExactly("Engine oil & filter", "Cabin / pollen filter").inOrder()
    }

    @Test
    fun `a matched schedule without receipt items brings its default parts`() {
        val state = recordForm.withServiceDraft(ServiceReceiptScanResponse("d", true, serviceDraft.copy(items = emptyList())), keys())
        assertThat(state.items.map { it.name }).containsExactly("Default oil")
    }

    @Test
    fun `an expense bill suggests the expense form with the same receipt`() {
        val draft = serviceDraft.copy(
            expenseCategory = "INSURANCE", suggestedTarget = "expense", suggestedScheduleId = null, serviceTypes = emptyList(),
            items = emptyList(), totalCostCents = 45000, note = "x".repeat(800),
        )
        val state = recordForm.withServiceDraft(ServiceReceiptScanResponse("doc3", true, draft), keys())
        assertThat(state.scanReview?.warnings).containsExactly(ScanWarning.SuggestExpense)
        val route = state.expenseHandoff!!
        assertThat(route).isEqualTo(
            ExpenseFormRoute(
                vehicleId = "veh1", category = "INSURANCE", date = "2026-10-15T12:00", amountCents = 45000, currency = "EUR",
                description = "x".repeat(500), receiptDocumentId = "doc3",
            ),
        )

        val expense = ExpenseFormState(formatter = metric).withRoute(route)
        assertThat(expense.category).isEqualTo("INSURANCE")
        assertThat(expense.amount).isEqualTo("450.00")
        assertThat(expense.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(expense.description).hasLength(500)
        val input = expense.input(zone = ZoneId.of("Europe/Berlin"))
        assertThat(input.receiptDocumentId).isEqualTo("doc3")
        assertThat(input.amountCents).isEqualTo(45000)
        assertThat(input.occurredAt).isEqualTo("2026-10-15T10:00:00Z")
    }

    @Test
    fun `a plain expense route leaves the form alone`() {
        val before = ExpenseFormState(formatter = metric, category = "OTHER")
        assertThat(before.withRoute(ExpenseFormRoute(vehicleId = "veh1"))).isEqualTo(before)
    }

    @Test
    fun `service messages tell a fuel receipt from a non-receipt`() {
        assertThat(ReceiptScanResult.Success(ServiceReceiptScanResponse("d", false, null, listOf("fuel_receipt"))).serviceMessage())
            .isEqualTo(ScanMessage.FuelReceiptOnServiceForm)
        assertThat(ReceiptScanResult.Success(ServiceReceiptScanResponse("d", false, null, listOf("not_service_receipt"))).serviceMessage())
            .isEqualTo(ScanMessage.NotServiceReceipt)
        assertThat(ReceiptScanResult.Success(ServiceReceiptScanResponse("d", true, serviceDraft)).serviceMessage()).isNull()
    }

    @Test
    fun `receipt local times parse strictly`() {
        assertThat(parseReceiptLocal("2026-10-20T14:32:00")).isNotNull()
        assertThat(parseReceiptLocal("2026-02-30T12:00:00")).isNull()
        assertThat(parseReceiptLocal(null)).isNull()
        assertThat(parseReceiptLocal("yesterday")).isNull()
    }
}

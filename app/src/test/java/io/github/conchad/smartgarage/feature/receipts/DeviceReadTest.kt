package io.github.conchad.smartgarage.feature.receipts

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.network.ReceiptScanError
import io.github.conchad.smartgarage.core.network.ReceiptScanResult
import io.github.conchad.smartgarage.core.network.NetworkFailure
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.feature.expenses.ExpenseFormState
import io.github.conchad.smartgarage.feature.expenses.withDeviceRead
import io.github.conchad.smartgarage.feature.fuel.FuelFormState
import io.github.conchad.smartgarage.feature.fuel.MoneyField
import io.github.conchad.smartgarage.feature.fuel.withDeviceRead
import io.github.conchad.smartgarage.feature.maintenance.RecordFormState
import io.github.conchad.smartgarage.feature.maintenance.withDeviceRead
import io.github.conchad.smartgarage.feature.receipts.ocr.DeviceRead
import io.github.conchad.smartgarage.feature.receipts.ocr.DeviceReadFailure
import io.github.conchad.smartgarage.feature.receipts.ocr.ParseWarning
import io.github.conchad.smartgarage.feature.receipts.ocr.ParsedReceipt
import io.github.conchad.smartgarage.feature.receipts.ocr.ReceiptTextReader
import io.github.conchad.smartgarage.feature.receipts.ocr.toOcrLines
import io.github.conchad.smartgarage.ocr.BoundingRect
import io.github.conchad.smartgarage.ocr.OcrError
import io.github.conchad.smartgarage.ocr.OcrTextBlock
import io.github.conchad.smartgarage.feature.receipts.ocr.failureOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Server first, the phone as fallback, and how a phone read fills the three forms (D-A63). */
class DeviceReadTest {

    private val metric = Formatter(DisplayPreferences(currency = "EUR", distanceUnit = "km", volumeUnit = "l", timezone = "Europe/Berlin"), Locale.US)
    private val gallons = Formatter(DisplayPreferences(currency = "USD", distanceUnit = "mi", volumeUnit = "gal", timezone = "America/New_York"), Locale.US)
    private val berlin = ZoneId.of("Europe/Berlin")
    private val photo = File("receipt.jpg")

    private val fuelReceipt = ParsedReceipt(
        totalCents = 7215,
        volumeLiters = 40.08,
        pricePerLiterCents = 180.0,
        date = LocalDate.of(2026, 10, 20),
        time = LocalTime.of(14, 32),
        vendor = "ARAL Tankstelle",
        currency = "EUR",
        fuelGrade = "Super E10",
    )
    private val lines = listOf("ARAL Tankstelle", "Super E10 40,08 l", "SUMME EUR 72,15")

    /** Records what was asked of it. */
    private class FakeReader(private val result: DeviceRead) : ReceiptTextReader {
        var calls = 0
        override suspend fun read(photo: File, locale: Locale): DeviceRead {
            calls++
            return result
        }
    }

    private val text = DeviceRead.Text(fuelReceipt, lines)

    // ── Server first, then the phone ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `with the server's scanner off the photo goes straight to the phone`() = runTest {
        val reader = FakeReader(text)
        var serverCalls = 0
        var onDevice = 0
        val read = ReceiptReading(reader).read(serverOn = false, photo = photo, onDevice = { onDevice++ }) {
            serverCalls++
            ReceiptScanResult.Unavailable
        }
        assertThat(read).isEqualTo(ReceiptRead.OnDevice(text, serverTried = false))
        assertThat(serverCalls).isEqualTo(0)
        assertThat(onDevice).isEqualTo(1)
    }

    @Test
    fun `a server answer is final and the phone isn't asked`() = runTest {
        val reader = FakeReader(text)
        val ok = ReceiptScanResult.Success("draft")
        val read = ReceiptReading(reader).read(serverOn = true, photo = photo) { ok }
        assertThat(read).isEqualTo(ReceiptRead.Server(ok))
        assertThat(reader.calls).isEqualTo(0)
    }

    @Test
    fun `an unreachable or failing server falls back to the phone with the same photo`() = runTest {
        val reader = FakeReader(text)
        var seen: File? = null
        val read = ReceiptReading(reader).read(serverOn = true, photo = photo) {
            seen = it
            ReceiptScanResult.Error(ReceiptScanError.Unreachable(ServerProblem.Unreachable(NetworkFailure.Timeout)))
        }
        assertThat(seen).isEqualTo(photo)
        assertThat(read).isEqualTo(ReceiptRead.OnDevice(text, serverTried = true, serverDocumentId = null))
        assertThat(reader.calls).isEqualTo(1)
    }

    @Test
    fun `a 502 falls back and keeps the photo the server saved`() = runTest {
        val read = ReceiptReading(FakeReader(text)).read(serverOn = true, photo = photo) {
            ReceiptScanResult.Error(ReceiptScanError.ExtractionFailed(documentId = "doc5"))
        }
        assertThat(read).isEqualTo(ReceiptRead.OnDevice(text, serverTried = true, serverDocumentId = "doc5"))
    }

    @Test
    fun `every failure but view-only falls back`() {
        assertThat(fallsBackToDevice(ReceiptScanResult.Success(Unit))).isFalse()
        assertThat(fallsBackToDevice(ReceiptScanResult.Unavailable)).isTrue()
        assertThat(fallsBackToDevice(ReceiptScanResult.Error(ReceiptScanError.Forbidden))).isFalse()
        listOf(
            ReceiptScanError.NotFound,
            ReceiptScanError.Unauthorized,
            ReceiptScanError.RateLimited(null),
            ReceiptScanError.FileTooLarge,
            ReceiptScanError.UnsupportedFile,
            ReceiptScanError.ExtractionFailed(null),
        ).forEach { assertThat(fallsBackToDevice(ReceiptScanResult.Error(it))).isTrue() }
    }

    @Test
    fun `phone failures become friendly messages and a cancelled read says nothing`() {
        assertThat(DeviceRead.Failed(DeviceReadFailure.Unreadable).message()).isEqualTo(ScanMessage.FileRejected)
        assertThat(DeviceRead.Failed(DeviceReadFailure.OutOfMemory).message()).isEqualTo(ScanMessage.DeviceOutOfMemory)
        assertThat(DeviceRead.Failed(DeviceReadFailure.EngineFailed).message()).isEqualTo(ScanMessage.DeviceFailed)
        assertThat(DeviceRead.Failed(DeviceReadFailure.Cancelled).message()).isNull()
        assertThat(DeviceRead.Text(ParsedReceipt(warnings = listOf(ParseWarning.NO_TEXT)), emptyList()).message()).isEqualTo(ScanMessage.NoText)
        assertThat(text.message()).isNull()

        assertThat(failureOf(OcrError.UnreadableImage("x"))).isEqualTo(DeviceReadFailure.Unreadable)
        assertThat(failureOf(OcrError.OutOfMemory())).isEqualTo(DeviceReadFailure.OutOfMemory)
        assertThat(failureOf(OcrError.CorruptModel("det.onnx", "sha"))).isEqualTo(DeviceReadFailure.EngineFailed)
        assertThat(failureOf(OcrError.Inference(RuntimeException()))).isEqualTo(DeviceReadFailure.EngineFailed)
        assertThat(failureOf(OcrError.Cancelled())).isEqualTo(DeviceReadFailure.Cancelled)
    }

    @Test
    fun `OCR blocks reach the parser in reading order with centre and height`() {
        fun block(text: String, l: Float, t: Float, r: Float, b: Float) = OcrTextBlock(text, 0.9f, emptyList(), BoundingRect(l, t, r, b))
        val result = listOf(
            block("72,15", 300f, 100f, 360f, 120f),
            block("SUMME", 10f, 102f, 80f, 122f),
            block("ARAL", 10f, 10f, 90f, 40f),
            block("  ", 0f, 0f, 1f, 1f),
        ).toOcrLines()
        assertThat(result.map { it.text }).containsExactly("ARAL", "72,15", "SUMME").inOrder()
        assertThat(result[1].centerX).isEqualTo(330f)
        assertThat(result[1].centerY).isEqualTo(110f)
        assertThat(result[1].height).isEqualTo(20f)
    }

    // ── Fuel form ────────────────────────────────────────────────────────────────────────────────────────────

    private val fuel = FuelFormState(vehicleId = "veh1", formatter = metric, date = LocalDate.of(2026, 10, 22), price = "1.799", odometer = "45210")

    @Test
    fun `a fuel receipt read on the phone fills an untouched form, prefilled price included`() {
        val state = fuel.withDeviceRead(text, serverTried = false)

        assertThat(state.date).isEqualTo(LocalDate.of(2026, 10, 20))
        assertThat(state.receiptTime).isEqualTo(LocalDateTime.of(2026, 10, 20, 14, 32))
        assertThat(state.volume).isEqualTo("40.08")
        assertThat(state.price).isEqualTo("1.8")
        assertThat(state.total).isEqualTo("72.15")
        assertThat(state.station).isEqualTo("ARAL Tankstelle")
        assertThat(state.notes).isEqualTo("Super E10")
        assertThat(state.odometer).isEqualTo("45210")
        assertThat(state.receiptCurrency).isEqualTo("EUR")
        assertThat(state.receiptDocumentId).isNull()
        assertThat(state.dirty).isTrue()
        assertThat(state.deviceReview).isEqualTo(
            DeviceReview(listOf(ScanField.Date, ScanField.Volume, ScanField.Price, ScanField.Total, ScanField.Station), emptyList(), lines, serverTried = false),
        )

        // Stored units: integer cents, litres, the printed time in the phone's zone.
        val input = state.input(zone = berlin)
        assertThat(input.totalCostCents).isEqualTo(7215)
        assertThat(input.liters).isEqualTo(40.08)
        assertThat(input.filledAt).isEqualTo("2026-10-20T12:32:00Z")
        assertThat(input.stationName).isEqualTo("ARAL Tankstelle")
    }

    @Test
    fun `fields the user typed keep their text and the price follows the money on the form`() {
        val typed = fuel.copy(volume = "38", station = "My station", date = LocalDate.of(2026, 10, 21), typed = setOf(ScanField.Volume, ScanField.Station, ScanField.Date))
        val receipt = fuelReceipt.copy(pricePerLiterCents = null)
        val state = typed.withDeviceRead(DeviceRead.Text(receipt, lines), serverTried = true)

        assertThat(state.volume).isEqualTo("38")
        assertThat(state.station).isEqualTo("My station")
        assertThat(state.date).isEqualTo(LocalDate.of(2026, 10, 21))
        assertThat(state.receiptTime).isNull()
        assertThat(state.total).isEqualTo("72.15")
        // No printed price: total ÷ volume on the form (72.15 / 38), not the prefilled 1.799.
        assertThat(state.lastMoney).isEqualTo(MoneyField.Total)
        assertThat(state.price).isEqualTo("1.899")
        assertThat(state.deviceReview?.filled).containsExactly(ScanField.Total)
        assertThat(state.deviceReview?.serverTried).isTrue()
    }

    @Test
    fun `a typed field that is empty again is filled`() {
        val state = fuel.copy(total = "", typed = setOf(ScanField.Total)).withDeviceRead(text, serverTried = false)
        assertThat(state.total).isEqualTo("72.15")
    }

    @Test
    fun `gallon display converts litres and the price per litre`() {
        val state = fuel.copy(formatter = gallons, price = "").withDeviceRead(DeviceRead.Text(fuelReceipt.copy(currency = "USD"), lines), serverTried = false)
        // 40.08 L = 10.588 gal; 180 c/L = 6.814 $/gal.
        assertThat(state.volume).isEqualTo("10.588")
        assertThat(state.price).isEqualTo("6.814")
        assertThat(state.input().liters!!).isWithin(0.01).of(40.08)
        assertThat(state.input().totalCostCents).isEqualTo(7215)
        assertThat(state.foreignCurrency).isNull()
    }

    @Test
    fun `warnings name only what was filled`() {
        val receipt = fuelReceipt.copy(
            pricePerLiterDerived = true,
            warnings = listOf(ParseWarning.TOTAL_UNLABELLED, ParseWarning.PRICE_DERIVED, ParseWarning.DATE_AMBIGUOUS, ParseWarning.VOLUME_CONVERTED_FROM_GALLONS, ParseWarning.LOW_OCR_CONFIDENCE),
        )
        val all = fuel.withDeviceRead(DeviceRead.Text(receipt, lines), serverTried = false)
        assertThat(all.deviceReview?.warnings).containsExactly(
            DeviceWarning.TotalUnlabelled, DeviceWarning.PriceDerived, DeviceWarning.FromGallons, DeviceWarning.DateAmbiguous, DeviceWarning.HardToRead,
        ).inOrder()

        val kept = fuel.copy(total = "70", price = "1.75", typed = setOf(ScanField.Total, ScanField.Price, ScanField.Date))
            .withDeviceRead(DeviceRead.Text(receipt, lines), serverTried = false)
        assertThat(kept.deviceReview?.warnings).containsExactly(DeviceWarning.FromGallons, DeviceWarning.HardToRead).inOrder()
    }

    @Test
    fun `nothing usable leaves the form as it was and opens the text`() {
        val state = fuel.withDeviceRead(DeviceRead.Text(ParsedReceipt(), listOf("THANK YOU")), serverTried = false)
        assertThat(state.copy(deviceReview = null)).isEqualTo(fuel)
        assertThat(state.deviceReview?.nothingFilled).isTrue()
        assertThat(state.deviceReview?.lines).containsExactly("THANK YOU")
    }

    // ── Service and expense forms ────────────────────────────────────────────────────────────────────────────

    private val invoice = ParsedReceipt(totalCents = 18950, date = LocalDate.of(2026, 10, 15), vendor = "Reifen Schmidt", currency = "CHF")

    @Test
    fun `an invoice fills date, cost, vendor and currency on a service record and leaves the rest`() {
        val record = RecordFormState(vehicleId = "veh1", scheduleId = "s1", formatter = metric, date = LocalDate.of(2026, 10, 22), odometer = "45210", notes = "Winter tyres")
        val state = record.withDeviceRead(DeviceRead.Text(invoice, listOf("Reifen Schmidt")), serverTried = true)

        assertThat(state.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(state.cost).isEqualTo("189.50")
        assertThat(state.vendor).isEqualTo("Reifen Schmidt")
        assertThat(state.receiptCurrency).isEqualTo("CHF")
        assertThat(state.foreignCurrency).isEqualTo("CHF")
        assertThat(state.odometer).isEqualTo("45210")
        assertThat(state.notes).isEqualTo("Winter tyres")
        assertThat(state.scheduleId).isEqualTo("s1")
        assertThat(state.deviceReview?.filled).containsExactly(ScanField.Date, ScanField.Total, ScanField.Vendor).inOrder()
        assertThat(state.input(zone = berlin).costCents).isEqualTo(18950)

        val typed = record.copy(cost = "200", vendor = "ATU", typed = setOf(ScanField.Total, ScanField.Vendor))
            .withDeviceRead(DeviceRead.Text(invoice, emptyList()), serverTried = false)
        assertThat(typed.cost).isEqualTo("200")
        assertThat(typed.vendor).isEqualTo("ATU")
    }

    @Test
    fun `a fuel receipt on the service form is pointed out`() {
        val record = RecordFormState(vehicleId = "veh1", formatter = metric)
        val state = record.withDeviceRead(text, serverTried = false)
        assertThat(state.deviceReview?.warnings).contains(DeviceWarning.FuelReceipt)
    }

    @Test
    fun `a bill fills the expense, appends the vendor and a fuel receipt becomes a Fuel expense`() {
        val expense = ExpenseFormState(vehicleId = "veh1", formatter = metric, date = LocalDate.of(2026, 10, 22), description = "Parking")
        val state = expense.withDeviceRead(DeviceRead.Text(invoice, emptyList()), serverTried = false)
        assertThat(state.amount).isEqualTo("189.50")
        assertThat(state.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(state.description).isEqualTo("Parking\nReifen Schmidt")
        assertThat(state.category).isEqualTo("OTHER")
        assertThat(state.input(zone = berlin).amountCents).isEqualTo(18950)
        assertThat(state.input(zone = berlin).currency).isEqualTo("CHF")

        val fuelBill = expense.withDeviceRead(text, serverTried = false)
        assertThat(fuelBill.category).isEqualTo("FUEL")
        assertThat(fuelBill.deviceReview?.warnings).doesNotContain(DeviceWarning.FuelReceipt)

        val picked = expense.copy(category = "REPAIR", categoryPicked = true, amount = "12", typed = setOf(ScanField.Total)).withDeviceRead(text, serverTried = false)
        assertThat(picked.category).isEqualTo("REPAIR")
        assertThat(picked.amount).isEqualTo("12")
    }
}

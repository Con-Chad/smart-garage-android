package io.github.conchad.smartgarage.feature.maintenance

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.data.RecordInput
import io.github.conchad.smartgarage.core.data.RecordPatch
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.MaintenanceItem
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.MaintenanceTemplate
import io.github.conchad.smartgarage.core.model.Schedule
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.core.network.ReceiptScanResult
import io.github.conchad.smartgarage.core.network.ReceiptScanner
import io.github.conchad.smartgarage.core.network.ServiceReceiptScanResponse
import io.github.conchad.smartgarage.core.network.documentIdOrNull
import io.github.conchad.smartgarage.feature.receipts.ReceiptCapture
import io.github.conchad.smartgarage.feature.receipts.ScanMessage
import io.github.conchad.smartgarage.feature.receipts.ScanReview
import io.github.conchad.smartgarage.feature.receipts.parseReceiptLocal
import io.github.conchad.smartgarage.feature.receipts.receiptInstant
import io.github.conchad.smartgarage.feature.receipts.serviceMessage
import io.github.conchad.smartgarage.feature.receipts.serviceReview
import io.github.conchad.smartgarage.ui.files.PickedFile
import io.github.conchad.smartgarage.ui.links.CalendarLinks
import io.github.conchad.smartgarage.ui.navigation.ExpenseFormRoute
import io.github.conchad.smartgarage.ui.navigation.RecordFormRoute
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

/** One editable part or fluid. [base] keeps the fields the form doesn't show (brand, part number, unit…). */
data class ItemDraft(
    val key: Long,
    val base: MaintenanceItem,
    val name: String,
    val quantity: String,
    val cost: String,
) {
    fun quantityValue(fmt: Formatter): Double? = fmt.parseNumber(quantity)
    fun costCents(fmt: Formatter): Long? = fmt.parseNumber(cost)?.let(fmt::centsFromDisplay)
    fun quantityInvalid(fmt: Formatter): Boolean = quantity.isNotBlank() && (quantityValue(fmt) ?: -1.0) < 0
    fun costInvalid(fmt: Formatter): Boolean = cost.isNotBlank() && (costCents(fmt) ?: -1L) < 0
    fun isBlank(fmt: Formatter): Boolean = name.isBlank() && quantity.isBlank() && cost.isBlank() && base.brand.isNullOrBlank() && base.partNumber.isNullOrBlank()

    fun toItem(fmt: Formatter): MaintenanceItem = base.copy(
        id = null,
        name = name.trim().ifEmpty { null },
        quantity = quantityValue(fmt),
        costCents = costCents(fmt),
    )

    companion object {
        fun of(item: MaintenanceItem, key: Long, fmt: Formatter) = ItemDraft(
            key = key,
            base = item,
            name = item.name ?: item.productName.orEmpty(),
            quantity = item.quantity?.let { fmt.inputNumber(it, 2) }.orEmpty(),
            cost = item.costCents?.let(fmt::moneyInput).orEmpty(),
        )
    }
}

data class ScheduleOption(val schedule: Schedule, val name: String)

/**
 * Logging or editing a service record (D-A20). The schedule is fixed when the form opens from a schedule or an
 * existing record; from the Service tab's FAB the user picks vehicle and schedule first. New records start with
 * the schedule's default parts.
 */
data class RecordFormState(
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val schedules: List<ScheduleOption> = emptyList(),
    val scheduleId: String? = null,
    /** Vehicle and schedule can't change: the form came from a schedule or edits an existing record. */
    val locked: Boolean = false,
    val editingId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val odometer: String = "",
    val cost: String = "",
    val vendor: String = "",
    val notes: String = "",
    val items: List<ItemDraft> = emptyList(),
    val saveAsDefault: Boolean = false,
    val currentOdometerKm: Int? = null,
    /** `/me.features.receiptScannerKinds` contains `service` (D-A53): shows "Scan receipt" on a new record. */
    val scannerAvailable: Boolean = false,
    val scanning: Boolean = false,
    val receiptPhoto: PickedFile? = null,
    /** `documentId` the scan saved the invoice as (also after a 502), sent as `receiptDocumentId`. */
    val receiptDocumentId: String? = null,
    /** Date and time printed on the invoice; used for `performedAt` while the form's date still matches it. */
    val receiptTime: LocalDateTime? = null,
    val receiptCurrency: String? = null,
    val scanReview: ScanReview? = null,
    /** Other service types the invoice covers, besides the selected schedule's (names as the server gave them). */
    val alsoCovers: List<String> = emptyList(),
    /** The same invoice as an expense, for "Save as expense instead" (the scan's `suggestedTarget` may say so). */
    val expenseHandoff: ExpenseFormRoute? = null,
    val scanMessage: ScanMessage? = null,
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
    val schedule: ScheduleOption? get() = schedules.firstOrNull { it.schedule.id == scheduleId }
    val canEdit: Boolean get() = vehicle?.access?.canEdit == true
    val editing: Boolean get() = editingId != null

    val odometerKm: Int? get() = formatter.parseNumber(odometer)?.let(formatter::kmFromDisplay)
    val costCents: Long? get() = formatter.parseNumber(cost)?.let(formatter::centsFromDisplay)
    val odometerInvalid: Boolean get() = odometer.isNotBlank() && (odometerKm == null || odometerKm!! < 0)
    val costInvalid: Boolean get() = cost.isNotBlank() && (costCents == null || costCents!! < 0)
    val itemsInvalid: Boolean get() = items.any { it.quantityInvalid(formatter) || it.costInvalid(formatter) }
    val odometerBelowCurrent: Boolean get() = odometerKm?.let { km -> currentOdometerKm?.let { km < it } } == true

    /** What the listed parts add up to, as a hint under the cost field. */
    val itemsTotalCents: Long? get() = items.mapNotNull { it.costCents(formatter) }.takeIf { it.isNotEmpty() }?.sum()

    val canSave: Boolean get() = canEdit && scheduleId != null && !odometerInvalid && !costInvalid && !itemsInvalid && !saving && !scanning

    /** Only a new record can be scanned into, and only for a chosen vehicle the user may write to. */
    val canScan: Boolean get() = scannerAvailable && !editing && vehicleId != null && canEdit && !saving

    val foreignCurrency: String? get() = receiptCurrency?.takeIf { !it.equals(formatter.currencyCode, ignoreCase = true) }

    /** When this service falls due again (the date plus the schedule's month interval), for "Add to calendar" (D-A25). */
    val nextDue: LocalDate? get() = schedule?.schedule?.let { CalendarLinks.nextDueAfter(date, it.intervalMonths) }

    private fun itemsToSend(): List<MaintenanceItem> = items.filterNot { it.isBlank(formatter) }.map { it.toItem(formatter) }

    fun input(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): RecordInput = RecordInput(
        performedAt = (receiptTime?.takeIf { it.toLocalDate() == date }?.let { receiptInstant(it, zone) } ?: formatter.instantFor(date, now)).toString(),
        odometerKm = odometerKm,
        costCents = costCents ?: 0,
        currency = receiptCurrency ?: formatter.currencyCode,
        vendorName = vendor.trim().ifEmpty { null },
        note = notes.trim().ifEmpty { null },
        items = itemsToSend(),
        saveAsDefault = saveAsDefault.takeIf { it },
        // No POST /expenses alongside: the server books the record's cost as a MAINTENANCE expense itself (D-044).
        receiptDocumentId = receiptDocumentId,
    )

    fun patch(now: Instant = Instant.now()): RecordPatch = RecordPatch(
        performedAt = formatter.instantFor(date, now).toString(),
        odometerKm = odometerKm,
        costCents = costCents ?: 0,
        vendorName = vendor.trim().ifEmpty { null },
        note = notes.trim().ifEmpty { null },
        items = itemsToSend(),
        saveAsDefault = saveAsDefault.takeIf { it },
        clear = buildSet {
            if (odometerKm == null) add("odometerKm")
            if (vendor.isBlank()) add("vendorName")
            if (notes.isBlank()) add("note")
        },
    )
}

/**
 * The form with a service scan's draft filled in (D-A53): date (and printed time), odometer, total cost, vendor,
 * note, currency, the parts and fluids, and the schedule when the server matched a template-based one
 * (`suggestedScheduleId`) and the form isn't locked to another. Without a match the user picks the schedule in the
 * usual picker. Draft fields that are null keep what the form had. [nextKey] numbers the new part rows.
 */
fun RecordFormState.withServiceDraft(response: ServiceReceiptScanResponse, nextKey: () -> Long): RecordFormState {
    val draft = response.draft ?: return copy(receiptDocumentId = response.documentId, scanReview = null)
    val fmt = formatter
    val time = parseReceiptLocal(draft.performedAtLocal)
    val suggested = draft.suggestedScheduleId?.takeIf { id -> !locked && schedules.any { it.schedule.id == id } }
    val chosen = suggested ?: scheduleId
    val receiptItems = draft.items.map {
        MaintenanceItem(
            category = it.category,
            name = it.name,
            brand = it.brand,
            partNumber = it.partNumber,
            quantity = it.quantity,
            unit = it.unit,
            customUnit = it.customUnit,
            costCents = it.costCents,
        )
    }
    val newItems = when {
        receiptItems.isNotEmpty() -> receiptItems.map { ItemDraft.of(it, nextKey(), fmt) }
        suggested != null && suggested != scheduleId ->
            schedules.first { it.schedule.id == suggested }.schedule.itemDefaults.map { ItemDraft.of(it, nextKey(), fmt) }
        else -> items
    }
    val note = draft.note?.trim()?.takeIf { it.isNotEmpty() && !notes.contains(it) }
    return copy(
        scheduleId = chosen,
        date = time?.toLocalDate() ?: date,
        receiptTime = time,
        odometer = draft.odometerKm?.let(fmt::distanceInput) ?: odometer,
        cost = draft.totalCostCents?.let(fmt::moneyInput) ?: cost,
        vendor = draft.vendorName?.trim()?.takeIf { it.isNotEmpty() } ?: vendor,
        notes = listOfNotNull(notes.trim().ifEmpty { null }, note).joinToString("\n"),
        items = newItems,
        receiptCurrency = draft.currency?.uppercase() ?: receiptCurrency,
        receiptDocumentId = response.documentId,
        scanReview = serviceReview(response),
        alsoCovers = if (chosen == null) emptyList() else draft.serviceTypes.filter { it.scheduleId != chosen }.mapNotNull { it.name?.trim()?.ifEmpty { null } }.distinct(),
        expenseHandoff = this.vehicleId?.let {
            ExpenseFormRoute(
                vehicleId = it,
                category = draft.expenseCategory,
                date = time?.toString(),
                amountCents = draft.totalCostCents,
                currency = draft.currency?.uppercase(),
                // The expense description holds 500 characters; the record note up to 4000.
                description = draft.note?.trim()?.take(500)?.ifEmpty { null },
                receiptDocumentId = response.documentId,
            )
        },
        dirty = true,
    )
}

@HiltViewModel
class RecordFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
    profiles: ProfileStore,
    private val scanner: ReceiptScanner,
    @ApplicationContext context: Context,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<RecordFormRoute>()
    private val capture = ReceiptCapture(context, savedStateHandle, CAPTURE_PATH)
    private val german = Locale.getDefault().language == "de"
    private val form = MutableStateFlow(RecordFormState())
    val state: StateFlow<RecordFormState> = form.asStateFlow()

    private var allSchedules: List<Schedule> = emptyList()
    private var templates: Map<String, MaintenanceTemplate> = emptyMap()
    private var nextKey = 1L

    init {
        viewModelScope.launch {
            profiles.state.map { it.active?.features?.scansService == true }.distinctUntilChanged()
                .collect { on -> form.update { it.copy(scannerAvailable = on) } }
        }
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicles = garage.vehicles.first().filter { it.access.canEdit }.sortedWith(GarageMath.vehicleOrder)
            allSchedules = garage.schedules.first()
            templates = garage.templates.first().associateBy { it.id }
            val record: MaintenanceRecord? = route.recordId?.let { id -> garage.records.first().firstOrNull { it.id == id } }
            val schedule = (record?.scheduleId ?: route.scheduleId)?.let { id -> allSchedules.firstOrNull { it.id == id } }
            val vehicleId = (record?.vehicleId ?: schedule?.vehicleId ?: route.vehicleId)?.takeIf { id -> vehicles.any { it.id == id } }
                ?: vehicles.singleOrNull()?.id
            val vehicle = vehicles.firstOrNull { it.id == vehicleId }
            val performedAt = record?.performedAt?.toInstantOrNull()
            form.value = RecordFormState(
                scannerAvailable = form.value.scannerAvailable,
                vehicles = vehicles,
                vehicleId = vehicleId,
                schedules = vehicleId?.let(::optionsFor).orEmpty(),
                scheduleId = schedule?.id,
                locked = record != null || schedule != null,
                editingId = record?.id,
                date = performedAt?.let(fmt::localDate) ?: fmt.today(),
                odometer = (record?.odometerKm ?: vehicle?.currentOdometerKm)?.let(fmt::distanceInput).orEmpty(),
                cost = record?.let { fmt.moneyInput(it.costCents) }.orEmpty(),
                vendor = record?.vendorName.orEmpty(),
                notes = record?.note.orEmpty(),
                items = (record?.items ?: schedule?.itemDefaults.orEmpty()).map { ItemDraft.of(it, nextKey++, fmt) },
                currentOdometerKm = vehicle?.currentOdometerKm,
                formatter = fmt,
                loaded = true,
            )
        }
    }

    private fun optionsFor(vehicleId: String): List<ScheduleOption> = allSchedules
        .filter { it.vehicleId == vehicleId }
        .map { ScheduleOption(it, it.displayName(it.templateId?.let(templates::get), german)) }
        .sortedBy { it.name.lowercase() }

    fun selectVehicle(id: String) = form.update { s ->
        if (s.locked || id == s.vehicleId) return@update s
        val vehicle = s.vehicles.firstOrNull { it.id == id } ?: return@update s
        s.copy(
            vehicleId = id,
            schedules = optionsFor(id),
            scheduleId = null,
            items = emptyList(),
            currentOdometerKm = vehicle.currentOdometerKm,
            odometer = s.formatter.distanceInput(vehicle.currentOdometerKm),
            // A scanned invoice belongs to the vehicle it was scanned for; the server refuses it on another one.
            receiptDocumentId = null,
            receiptPhoto = null,
            expenseHandoff = null,
            alsoCovers = emptyList(),
            dirty = true,
        )
    }

    /** Picking a schedule (new records only) loads its default parts, replacing any from the previous pick. */
    fun selectSchedule(id: String) = form.update { s ->
        if (s.locked) return@update s
        val option = s.schedules.firstOrNull { it.schedule.id == id } ?: return@update s
        s.copy(scheduleId = id, items = option.schedule.itemDefaults.map { ItemDraft.of(it, nextKey++, s.formatter) }, dirty = true)
    }

    fun setDate(date: LocalDate) = form.update { it.copy(date = date, dirty = true) }
    fun setOdometer(text: String) = form.update { it.copy(odometer = text, dirty = true) }
    fun setCost(text: String) = form.update { it.copy(cost = text, dirty = true) }
    fun setVendor(text: String) = form.update { it.copy(vendor = text, dirty = true) }
    fun setNotes(text: String) = form.update { it.copy(notes = text, dirty = true) }
    fun setSaveAsDefault(on: Boolean) = form.update { it.copy(saveAsDefault = on, dirty = true) }

    /** Copies the parts total into the cost field. */
    fun useItemsTotal() = form.update { s ->
        s.itemsTotalCents?.let { s.copy(cost = s.formatter.moneyInput(it), dirty = true) } ?: s
    }

    fun addItem() = form.update { s ->
        s.copy(items = s.items + ItemDraft(nextKey++, MaintenanceItem(), "", "", ""), dirty = true)
    }

    fun removeItem(key: Long) = form.update { s -> s.copy(items = s.items.filterNot { it.key == key }, dirty = true) }

    fun setItemName(key: Long, text: String) = updateItem(key) { it.copy(name = text) }
    fun setItemQuantity(key: Long, text: String) = updateItem(key) { it.copy(quantity = text) }
    fun setItemCost(key: Long, text: String) = updateItem(key) { it.copy(cost = text) }

    private fun updateItem(key: Long, change: (ItemDraft) -> ItemDraft) = form.update { s ->
        s.copy(items = s.items.map { if (it.key == key) change(it) else it }, dirty = true)
    }

    // ── Receipt scan (D-A53) ─────────────────────────────────────────────────────────────────────────────────

    fun startScan(): Uri = capture.start()

    /** The camera came back: downscale, send to `POST /records/receipt-scan` and fill the form from the draft. */
    fun captured(success: Boolean) {
        val file = capture.taken(success) ?: return
        val vehicleId = form.value.vehicleId ?: run {
            file.delete()
            return
        }
        form.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val photo = capture.finish(file)
            if (photo == null) {
                form.update { it.copy(scanning = false, scanMessage = ScanMessage.FileRejected) }
                return@launch
            }
            form.value.receiptPhoto?.let { capture.fileOf(it)?.takeIf { f -> f != file }?.delete() }
            val result = scanner.scanService(vehicleId, file)
            form.update { s ->
                val scanned = s.copy(scanning = false, receiptPhoto = photo, scanMessage = result.serviceMessage())
                when {
                    // A fuel receipt doesn't belong on a service record; the photo stays unlinked on the server.
                    result is ReceiptScanResult.Success && "fuel_receipt" in result.response.warnings ->
                        scanned.copy(receiptPhoto = null, receiptDocumentId = null, scanReview = null).also { capture.fileOf(photo)?.delete() }
                    result is ReceiptScanResult.Success -> scanned.withServiceDraft(result.response) { nextKey++ }
                    // A 502 still saved the photo: keep its id so it is attached to the manual entry.
                    else -> scanned.copy(receiptDocumentId = result.documentIdOrNull(), scanReview = null)
                }
            }
        }
    }

    fun removeReceiptPhoto() {
        form.value.receiptPhoto?.let { capture.fileOf(it)?.delete() }
        form.update { it.copy(receiptPhoto = null, receiptDocumentId = null, scanReview = null, expenseHandoff = null, alsoCovers = emptyList()) }
    }

    fun cameraUnavailable() = form.update { it.copy(scanMessage = ScanMessage.NoCamera) }
    fun clearScanMessage() = form.update { it.copy(scanMessage = null) }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        val scheduleId = s.scheduleId ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = s.editingId?.let { writer.updateRecord(it, s.patch()) } ?: writer.addRecord(scheduleId, s.input())
            val queued = report(result)
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }

    private companion object {
        const val CAPTURE_PATH = "serviceReceiptCapturePath"
    }
}

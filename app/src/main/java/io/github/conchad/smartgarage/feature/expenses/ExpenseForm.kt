package io.github.conchad.smartgarage.feature.expenses

import android.content.Context
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.network.ReceiptScanResult
import io.github.conchad.smartgarage.core.network.ReceiptScanner
import io.github.conchad.smartgarage.core.network.ServiceReceiptScanResponse
import io.github.conchad.smartgarage.core.network.documentIdOrNull
import io.github.conchad.smartgarage.feature.receipts.DeviceReview
import io.github.conchad.smartgarage.feature.receipts.DeviceReviewNote
import io.github.conchad.smartgarage.feature.receipts.DeviceWarning
import io.github.conchad.smartgarage.feature.receipts.ReceiptCapture
import io.github.conchad.smartgarage.feature.receipts.ReceiptRead
import io.github.conchad.smartgarage.feature.receipts.ReceiptReading
import io.github.conchad.smartgarage.feature.receipts.ScanField
import io.github.conchad.smartgarage.feature.receipts.deviceWarnings
import io.github.conchad.smartgarage.feature.receipts.fillField
import io.github.conchad.smartgarage.feature.receipts.message
import io.github.conchad.smartgarage.feature.receipts.ocr.DeviceRead
import io.github.conchad.smartgarage.feature.receipts.ReceiptPhotoRow
import io.github.conchad.smartgarage.feature.receipts.ReceiptScanButtons
import io.github.conchad.smartgarage.feature.receipts.ScanMessage
import io.github.conchad.smartgarage.feature.receipts.ScanReview
import io.github.conchad.smartgarage.feature.receipts.encodeScanHandoff
import io.github.conchad.smartgarage.feature.receipts.expenseReview
import io.github.conchad.smartgarage.feature.receipts.messageRes
import io.github.conchad.smartgarage.feature.receipts.rememberReceiptCamera
import io.github.conchad.smartgarage.feature.receipts.rememberReceiptGallery
import io.github.conchad.smartgarage.feature.receipts.serviceMessage
import io.github.conchad.smartgarage.ui.files.PickedFile
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.ui.navigation.RecordFormRoute
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.forms.FormSkeleton
import io.github.conchad.smartgarage.core.data.ExpenseInput
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.expenseCategoryLabel
import io.github.conchad.smartgarage.ui.components.forms.FormDateField
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormMoneyField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.feature.receipts.ScanReviewNote
import io.github.conchad.smartgarage.feature.receipts.parseReceiptLocal
import io.github.conchad.smartgarage.feature.receipts.receiptInstant
import io.github.conchad.smartgarage.ui.navigation.ExpenseFormRoute
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/** The web's expense categories, in its order; "Other" is the default there too. */
val EXPENSE_CATEGORIES = listOf("MAINTENANCE", "FUEL", "INSURANCE", "TAX", "REGISTRATION", "REPAIR", "ACCESSORY", "OTHER")

/** Vehicle, category, date, amount and a description: the web's "Add expense" form (D-A20). */
data class ExpenseFormState(
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val category: String = "OTHER",
    val date: LocalDate = LocalDate.now(),
    val amount: String = "",
    val description: String = "",
    /** From a scanned bill (D-A53): its `documentId`, local date-time and currency, sent with the expense. */
    val receiptDocumentId: String? = null,
    val receiptTime: LocalDateTime? = null,
    val receiptCurrency: String? = null,
    /**
     * `/me.features.receiptScannerKinds` contains `service` (D-A56): the photo goes to the server first; without it
     * the photo is read on the phone (D-A63). The scan buttons show either way.
     */
    val scannerAvailable: Boolean = false,
    val scanning: Boolean = false,
    /** The scan has moved on to the phone (D-A63). */
    val readingOnDevice: Boolean = false,
    /** The bill photo scanned on this form, kept in `cache/captures/`; null for a scan handed over from a record. */
    val receiptPhoto: PickedFile? = null,
    val scanReview: ScanReview? = null,
    /** The note after a bill read on the phone (D-A63); replaces [scanReview] then. */
    val deviceReview: DeviceReview? = null,
    /** Fields the user typed in or picked (category); a phone read leaves those alone unless they are empty (D-A63). */
    val typed: Set<ScanField> = emptySet(),
    val categoryPicked: Boolean = false,
    val scanMessage: ScanMessage? = null,
    /** The same bill as a service record, offered when the scan's `suggestedTarget` is `record` (D-A56). */
    val recordHandoff: RecordFormRoute? = null,
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
    val canEdit: Boolean get() = vehicle?.access?.canEdit == true
    val amountCents: Long? get() = formatter.parseNumber(amount)?.let(formatter::centsFromDisplay)
    val amountInvalid: Boolean get() = amount.isNotBlank() && (amountCents == null || amountCents!! < 0)
    val descriptionTooLong: Boolean get() = description.length > 500
    val canSave: Boolean get() = canEdit && amountCents != null && !amountInvalid && !descriptionTooLong && !saving && !scanning

    /** Scanning needs a vehicle the user may write to; with or without the server's scanner (D-A63). */
    val canScan: Boolean get() = canEdit && vehicleId != null && !saving

    val foreignCurrency: String? get() = receiptCurrency?.takeIf { !it.equals(formatter.currencyCode, ignoreCase = true) }

    fun input(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()) = ExpenseInput(
        category = category,
        occurredAt = (receiptTime?.takeIf { it.toLocalDate() == date }?.let { receiptInstant(it, zone) } ?: formatter.instantFor(date, now)).toString(),
        amountCents = amountCents ?: 0,
        currency = receiptCurrency ?: formatter.currencyCode,
        description = description.trim().ifEmpty { null },
        receiptDocumentId = receiptDocumentId,
    )
}

/** The form prefilled from [route]'s scanned-bill fields (D-A53); a plain route leaves it as it was. */
fun ExpenseFormState.withRoute(route: ExpenseFormRoute): ExpenseFormState {
    val time = parseReceiptLocal(route.date)
    return copy(
        category = route.category?.takeIf { it in EXPENSE_CATEGORIES } ?: category,
        categoryPicked = categoryPicked || route.category in EXPENSE_CATEGORIES,
        date = time?.toLocalDate() ?: date,
        receiptTime = time,
        amount = route.amountCents?.let(formatter::moneyInput) ?: amount,
        receiptCurrency = route.currency ?: receiptCurrency,
        description = route.description?.take(500) ?: description,
        receiptDocumentId = route.receiptDocumentId ?: receiptDocumentId,
        dirty = dirty || route.receiptDocumentId != null,
    )
}

/** The expense description's limit on the server. */
const val EXPENSE_DESCRIPTION_MAX = 500

/**
 * The form with a service scan (`POST /records/receipt-scan`) read into it (D-A56): date (and printed time for
 * `occurredAt`), category from `expenseCategory`, total, currency, and a description of the vendor plus the
 * server's ready-made note (invoice number, other lines), trimmed to [EXPENSE_DESCRIPTION_MAX]. Null draft fields
 * keep what the form had. When the server says the bill is really a service (`suggestedTarget == "record"`),
 * [ExpenseFormState.recordHandoff] carries the whole scan to the service record form.
 */
fun ExpenseFormState.withServiceScan(response: ServiceReceiptScanResponse): ExpenseFormState {
    val draft = response.draft ?: return copy(receiptDocumentId = response.documentId, scanReview = null, recordHandoff = null)
    val time = parseReceiptLocal(draft.performedAtLocal)
    return copy(
        category = draft.expenseCategory.uppercase().takeIf { it in EXPENSE_CATEGORIES } ?: category,
        date = time?.toLocalDate() ?: date,
        receiptTime = time,
        amount = draft.totalCostCents?.let(formatter::moneyInput) ?: amount,
        receiptCurrency = draft.currency?.uppercase() ?: receiptCurrency,
        description = scannedDescription(description, draft.vendorName, draft.note),
        receiptDocumentId = response.documentId,
        scanReview = expenseReview(response),
        recordHandoff = vehicleId?.takeIf { draft.suggestedTarget == "record" }
            ?.let { RecordFormRoute(vehicleId = it, scan = encodeScanHandoff(response)) },
        dirty = true,
    )
}

/**
 * The form with a bill read on the phone (D-A63): date (and printed time), amount, currency, and the vendor added
 * to the description (appended, never replacing it). A fuel receipt sets the category to Fuel unless the user picked
 * one. A field the user typed in keeps its text unless it is empty.
 */
fun ExpenseFormState.withDeviceRead(read: DeviceRead.Text, serverTried: Boolean): ExpenseFormState {
    val p = read.receipt
    val filled = mutableListOf<ScanField>()
    val newDate = p.date?.takeIf { ScanField.Date !in typed }?.also { filled += ScanField.Date }
    val amount = fillField(ScanField.Total, amount, p.totalCents, typed, filled, formatter::moneyInput)
    val vendor = p.vendor?.trim()?.ifEmpty { null }
    val description = scannedDescription(description, vendor, null)
    if (vendor != null && description != this.description.trim()) filled += ScanField.Vendor
    return copy(
        category = if (p.isFuelReceipt && !categoryPicked) "FUEL" else category,
        date = newDate ?: date,
        receiptTime = newDate?.let { d -> p.time?.let { d.atTime(it) } } ?: receiptTime.takeIf { newDate == null },
        amount = amount,
        description = description,
        receiptCurrency = p.currency ?: receiptCurrency,
        scanReview = null,
        recordHandoff = null,
        // A fuel receipt is fine as a Fuel expense, so the "use the fill-up form" line is left out here.
        deviceReview = DeviceReview(filled, deviceWarnings(p, filled, fuelForm = false) - DeviceWarning.FuelReceipt, read.lines, serverTried),
        dirty = dirty || filled.isNotEmpty(),
    )
}

/** What the user had, then the vendor and the note (each only if not already in it), one per line, ≤ 500 chars. */
internal fun scannedDescription(current: String, vendor: String?, note: String?): String {
    val kept = current.trim()
    val added = listOfNotNull(vendor, note).map { it.trim() }.filter { it.isNotEmpty() && !kept.contains(it) }
    return (listOf(kept).filter { it.isNotEmpty() } + added).joinToString("\n").take(EXPENSE_DESCRIPTION_MAX).trimEnd()
}

@HiltViewModel
class ExpenseFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
    profiles: ProfileStore,
    private val scanner: ReceiptScanner,
    private val reading: ReceiptReading,
    @ApplicationContext context: Context,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<ExpenseFormRoute>()
    private val capture = ReceiptCapture(context, savedStateHandle, CAPTURE_PATH)
    private val form = MutableStateFlow(ExpenseFormState())
    val state: StateFlow<ExpenseFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            profiles.state.map { it.active?.features?.scansService == true }.distinctUntilChanged()
                .collect { on -> form.update { it.copy(scannerAvailable = on) } }
        }
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicles = garage.vehicles.first().filter { it.access.canEdit }.sortedWith(GarageMath.vehicleOrder)
            val preselected = route.vehicleId?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id
            form.value = ExpenseFormState(
                vehicles = vehicles,
                vehicleId = preselected,
                date = fmt.today(),
                formatter = fmt,
                scannerAvailable = form.value.scannerAvailable,
                loaded = true,
            ).withRoute(route)
        }
    }

    // A scanned bill belongs to the vehicle it was scanned for; the server refuses its document on another one.
    fun selectVehicle(id: String) = form.update {
        if (id == it.vehicleId) return@update it
        it.receiptPhoto?.let { photo -> capture.fileOf(photo)?.delete() }
        it.copy(vehicleId = id, receiptDocumentId = null, receiptPhoto = null, scanReview = null, recordHandoff = null, dirty = true)
    }
    fun setCategory(category: String) = form.update { it.copy(category = category, categoryPicked = true, dirty = true) }
    fun setDate(date: LocalDate) = form.update { it.copy(date = date, typed = it.typed + ScanField.Date, dirty = true) }
    fun setAmount(text: String) = form.update { it.copy(amount = text, typed = it.typed + ScanField.Total, dirty = true) }
    fun setDescription(text: String) = form.update { it.copy(description = text, dirty = true) }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        val vehicleId = s.vehicleId ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val queued = report(writer.addExpense(vehicleId, s.input()))
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }

    // ── Bill scan (D-A56): camera or gallery → `POST /records/receipt-scan` → expense fields ────────────────

    fun startScan(): Uri = capture.start()

    fun captured(success: Boolean) {
        val file = capture.taken(success) ?: return
        scan { file }
    }

    fun picked(uri: Uri) = scan { capture.fromGallery(uri) }

    private fun scan(source: suspend () -> File?) {
        val vehicleId = form.value.vehicleId ?: return
        if (form.value.scanning) return
        form.update { it.copy(scanning = true) }
        viewModelScope.launch {
            val file = source()
            val photo = file?.let { capture.finish(it) }
            if (file == null || photo == null) {
                form.update { it.copy(scanning = false, scanMessage = ScanMessage.FileRejected) }
                return@launch
            }
            form.value.receiptPhoto?.let { capture.fileOf(it)?.takeIf { f -> f != file }?.delete() }
            val read = reading.read(form.value.scannerAvailable, file, onDevice = { form.update { it.copy(readingOnDevice = true) } }) {
                scanner.scanService(vehicleId, it)
            }
            ensureActive()
            form.update { s ->
                val base = s.copy(scanning = false, readingOnDevice = false, receiptPhoto = photo, deviceReview = null)
                when (read) {
                    is ReceiptRead.Server -> {
                        val result = read.result
                        val scanned = base.copy(scanMessage = result.serviceMessage())
                        when {
                            // A fuel receipt goes on the fill-up form; the photo stays unlinked on the server.
                            result is ReceiptScanResult.Success && "fuel_receipt" in result.response.warnings ->
                                scanned.copy(receiptPhoto = null, receiptDocumentId = null, scanReview = null, recordHandoff = null)
                                    .also { capture.fileOf(photo)?.delete() }
                            result is ReceiptScanResult.Success -> scanned.withServiceScan(result.response)
                            // A 502 still saved the photo: keep its id so it is attached to the manual entry.
                            else -> scanned.copy(receiptDocumentId = result.documentIdOrNull(), scanReview = null, recordHandoff = null)
                        }
                    }
                    // Read on the phone (D-A63); a 502's saved photo stays attached.
                    is ReceiptRead.OnDevice -> base.copy(receiptDocumentId = read.serverDocumentId, scanReview = null, recordHandoff = null, scanMessage = read.read.message())
                        .let { if (read.read is DeviceRead.Text && !read.read.noText) it.withDeviceRead(read.read, read.serverTried) else it }
                }
            }
        }
    }

    fun removeReceiptPhoto() {
        form.value.receiptPhoto?.let { capture.fileOf(it)?.delete() }
        form.update { it.copy(receiptPhoto = null, receiptDocumentId = null, scanReview = null, deviceReview = null, recordHandoff = null) }
    }

    fun cameraUnavailable() = form.update { it.copy(scanMessage = ScanMessage.NoCamera) }
    fun galleryUnavailable() = form.update { it.copy(scanMessage = ScanMessage.NoGallery) }
    fun clearScanMessage() = form.update { it.copy(scanMessage = null) }

    private companion object {
        const val CAPTURE_PATH = "expenseReceiptCapturePath"
    }
}

@Composable
fun ExpenseFormScreen(
    onClose: () -> Unit,
    onOpenRecord: ((RecordFormRoute) -> Unit)? = null,
    viewModel: ExpenseFormViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    // Bill scan (D-A56): the same camera/gallery pair as the fill-up and service forms.
    val launchScan = rememberReceiptCamera(viewModel::startScan, viewModel::captured, viewModel::cameraUnavailable)
    val pickPhoto = rememberReceiptGallery(viewModel::picked, viewModel::galleryUnavailable)
    val scanMessage = state.scanMessage?.let { stringResource(it.messageRes()) }
    LaunchedEffect(scanMessage) {
        if (scanMessage != null) {
            snackbar.showSnackbar(scanMessage)
            viewModel.clearScanMessage()
        }
    }

    val validation = when {
        !state.loaded -> null
        state.vehicles.isEmpty() -> stringResource(R.string.form_no_editable_vehicles)
        !state.canEdit -> stringResource(R.string.view_only)
        state.amountCents == null -> stringResource(R.string.expense_form_needs_amount)
        state.amountInvalid || state.descriptionTooLong -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    FormScreen(
        title = stringResource(R.string.expense_form_title),
        onClose = onClose,
        saveLabel = stringResource(R.string.action_save),
        canSave = state.canSave,
        saving = state.saving,
        onSave = viewModel::save,
        dirty = state.dirty && !state.done,
        validation = validation,
        snackbarHost = snackbar,
    ) {
        if (!state.loaded) {
            FormSkeleton()
            return@FormScreen
        }
        FormPicker(
            label = stringResource(R.string.field_vehicle),
            options = state.vehicles,
            selected = state.vehicle,
            optionLabel = { it.displayName },
            onSelect = { viewModel.selectVehicle(it.id) },
            enabled = state.vehicles.size > 1,
            placeholder = stringResource(R.string.form_no_editable_vehicles).takeIf { state.vehicles.isEmpty() },
        )
        if (state.vehicle != null && !state.canEdit) ViewOnlyNote(state.vehicle?.owner?.name)
        // Always offered (D-A63): the server's scanner when it lists "service" (D-A56), else the phone reads the bill.
        if (state.canEdit) {
            ReceiptScanButtons(
                scanning = state.scanning,
                enabled = state.canScan,
                onCamera = launchScan,
                onGallery = pickPhoto,
                hint = stringResource(R.string.receipt_scan_expense_hint),
                onDevice = state.readingOnDevice,
            )
            state.receiptPhoto?.let { ReceiptPhotoRow(it, onRemove = viewModel::removeReceiptPhoto, linked = state.receiptDocumentId != null) }
            state.deviceReview?.let { DeviceReviewNote(it) } ?: ScanReviewNote(state.scanReview)
            val handoff = state.recordHandoff
            if (handoff != null && onOpenRecord != null) {
                TextButton(onClick = { onOpenRecord(handoff) }, colors = glassTextButtonColors(), modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.receipt_scan_save_as_record))
                }
            }
        }
        val categoryLabels = EXPENSE_CATEGORIES.associateWith { expenseCategoryLabel(it) }
        FormPicker(
            label = stringResource(R.string.wishlist_category),
            options = EXPENSE_CATEGORIES,
            selected = state.category,
            optionLabel = { categoryLabels[it] ?: it },
            onSelect = viewModel::setCategory,
        )
        FormDateField(date = state.date, onDateChange = viewModel::setDate, label = stringResource(R.string.field_date), fmt = fmt)
        FormMoneyField(
            value = state.amount,
            onValueChange = viewModel::setAmount,
            label = stringResource(R.string.expense_amount),
            fmt = fmt,
            large = true,
            error = if (state.amountInvalid) stringResource(R.string.form_invalid_number) else null,
            supporting = state.foreignCurrency?.let { stringResource(R.string.receipt_scan_currency, it) },
        )
        // A bill handed over from the service form (or a 502 here) is linked without a photo row to show it.
        if (state.receiptDocumentId != null && state.receiptPhoto == null) ScanReviewNote(review = null, extra = stringResource(R.string.receipt_scan_expense_attached))
        FormNotesField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            label = stringResource(R.string.expense_description),
            error = if (state.descriptionTooLong) pluralStringResource(R.plurals.form_too_long, 500, 500) else null,
        )
        FormEndSpacer()
    }
}

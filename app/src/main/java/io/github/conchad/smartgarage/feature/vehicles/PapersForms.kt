package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.forms.FormSkeleton
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.InspectionInput
import io.github.conchad.smartgarage.core.data.InsuranceInput
import io.github.conchad.smartgarage.core.data.InsurancePatch
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Inspection
import io.github.conchad.smartgarage.core.model.InsurancePolicy
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.AddToCalendarButton
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.coverageLabel
import io.github.conchad.smartgarage.ui.components.forms.FormAutocompleteField
import io.github.conchad.smartgarage.ui.components.forms.FormDateField
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormMoneyField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormNumberField
import io.github.conchad.smartgarage.ui.components.forms.FormOptionalDateField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.FormSwitchRow
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.links.InsuranceProviders
import io.github.conchad.smartgarage.ui.links.dueCalendarEvent
import io.github.conchad.smartgarage.ui.links.rememberInsurerIcon
import io.github.conchad.smartgarage.ui.links.rememberAddToCalendar
import io.github.conchad.smartgarage.ui.links.renewalCalendarEvent
import io.github.conchad.smartgarage.ui.navigation.InspectionFormRoute
import io.github.conchad.smartgarage.ui.navigation.InsuranceFormRoute
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

// The vehicle's papers (web: VehicleInspectionsPanel, VehicleInsurancePanel). Both are short, but with dates,
// a picker and notes they pass three fields, so they are full-screen forms (D-A20).

val INSPECTION_TYPES = listOf("HU", "AU")
val COVERAGE_TYPES = listOf("LIABILITY", "PARTIAL", "FULL")

// ── Inspection ───────────────────────────────────────────────────────────────────────────────────────────────

/** One inspection per vehicle and type: saving "HU" again replaces the vehicle's HU, which the form says when it would. */
data class InspectionFormState(
    val vehicle: Vehicle? = null,
    val editing: Inspection? = null,
    val type: String = "HU",
    val nextDue: LocalDate? = null,
    val lastDone: LocalDate? = null,
    val reminderWeeks: String = "4",
    val sticker: String = "",
    val notes: String = "",
    /** The vehicle's existing inspections by type, to warn that a new one of that type replaces the old one. */
    val existingTypes: Set<String> = emptySet(),
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val canEdit: Boolean get() = vehicle?.access?.canEdit == true
    val isEdit: Boolean get() = editing != null
    val weeks: Int? get() = reminderWeeks.trim().toIntOrNull()
    val weeksInvalid: Boolean get() = reminderWeeks.isNotBlank() && (weeks == null || weeks!! !in 0..52)
    val stickerTooLong: Boolean get() = sticker.length > 60
    val notesTooLong: Boolean get() = notes.length > 2000
    val lastAfterNext: Boolean get() = lastDone != null && nextDue != null && lastDone!!.isAfter(nextDue!!)
    val replaces: Boolean get() = !isEdit && type in existingTypes
    val anyInvalid: Boolean get() = weeksInvalid || stickerTooLong || notesTooLong || lastAfterNext
    val canSave: Boolean get() = canEdit && nextDue != null && !anyInvalid && !saving

    fun input(): InspectionInput = InspectionInput(
        type = type,
        nextDueAt = formatter.instantFor(nextDue ?: formatter.today()).toString(),
        lastPerformedAt = lastDone?.let { formatter.instantFor(it).toString() },
        reminderWeeksBefore = weeks ?: 4,
        stickerNumber = sticker.trim().ifEmpty { null },
        notes = notes.trim().ifEmpty { null },
    )
}

@HiltViewModel
class InspectionFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<InspectionFormRoute>()
    private val form = MutableStateFlow(InspectionFormState())
    val state: StateFlow<InspectionFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicle = garage.vehicles.first().firstOrNull { it.id == route.vehicleId }
            val mine = garage.inspections.first().filter { it.vehicleId == route.vehicleId }
            val existing = route.inspectionId?.let { id -> mine.firstOrNull { it.id == id } }
            form.value = InspectionFormState(
                vehicle = vehicle,
                editing = existing,
                type = existing?.type ?: INSPECTION_TYPES.firstOrNull { type -> mine.none { it.type == type } } ?: "HU",
                nextDue = existing?.nextDueAt?.toInstantOrNull()?.let(fmt::localDate),
                lastDone = existing?.lastPerformedAt?.toInstantOrNull()?.let(fmt::localDate),
                reminderWeeks = (existing?.reminderWeeksBefore ?: 4).toString(),
                sticker = existing?.stickerNumber.orEmpty(),
                notes = existing?.notes.orEmpty(),
                existingTypes = mine.mapTo(HashSet()) { it.type },
                formatter = fmt,
                loaded = true,
            )
        }
    }

    fun setType(value: String) = form.update { if (it.isEdit) it else it.copy(type = value, dirty = true) }
    fun setNextDue(date: LocalDate?) = form.update { it.copy(nextDue = date, dirty = true) }
    fun setLastDone(date: LocalDate?) = form.update { it.copy(lastDone = date, dirty = true) }
    fun setReminderWeeks(text: String) = form.update { it.copy(reminderWeeks = text, dirty = true) }
    fun setSticker(text: String) = form.update { it.copy(sticker = text, dirty = true) }
    fun setNotes(text: String) = form.update { it.copy(notes = text, dirty = true) }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        val vehicleId = s.vehicle?.id ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val queued = report(writer.saveInspection(vehicleId, s.input()))
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }
}

@Composable
fun InspectionFormScreen(onClose: () -> Unit, viewModel: InspectionFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    val addToCalendar = rememberAddToCalendar(snackbar)
    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        state.vehicle == null -> stringResource(R.string.write_denied_unknown)
        !state.canEdit -> stringResource(R.string.view_only)
        state.nextDue == null -> stringResource(R.string.inspection_form_needs_due)
        state.anyInvalid -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    FormScreen(
        title = stringResource(if (state.isEdit) R.string.inspection_form_edit_title else R.string.inspection_form_title),
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
        state.vehicle?.let { vehicle ->
            Text(vehicle.displayName, style = MaterialTheme.typography.titleMedium)
            if (!vehicle.access.canEdit) ViewOnlyNote(vehicle.owner?.name)
        }
        val typeLabels = INSPECTION_TYPES.associateWith { inspectionLabel(it) }
        FormPicker(
            label = stringResource(R.string.inspection_type),
            options = INSPECTION_TYPES,
            selected = state.type,
            optionLabel = { typeLabels[it] ?: it },
            onSelect = viewModel::setType,
            enabled = !state.isEdit,
            supporting = if (state.replaces) stringResource(R.string.inspection_form_replaces, typeLabels[state.type] ?: state.type) else null,
        )
        FormOptionalDateField(
            date = state.nextDue,
            onDateChange = viewModel::setNextDue,
            label = stringResource(R.string.inspection_next_due),
            fmt = fmt,
            allowFuture = true,
        )
        FormOptionalDateField(
            date = state.lastDone,
            onDateChange = viewModel::setLastDone,
            label = stringResource(R.string.inspection_last_done),
            fmt = fmt,
            error = if (state.lastAfterNext) stringResource(R.string.inspection_form_last_after_next) else null,
        )
        FormNumberField(
            value = state.reminderWeeks,
            onValueChange = viewModel::setReminderWeeks,
            label = stringResource(R.string.inspection_form_reminder_weeks),
            integer = true,
            supporting = stringResource(R.string.inspection_form_reminder_hint),
            error = if (state.weeksInvalid) stringResource(R.string.inspection_form_weeks_invalid) else null,
        )
        FormTextField(
            value = state.sticker,
            onValueChange = viewModel::setSticker,
            label = stringResource(R.string.inspection_sticker),
            error = if (state.stickerTooLong) pluralStringResource(R.plurals.form_too_long, 60, 60) else null,
        )
        FormNotesField(
            value = state.notes,
            onValueChange = viewModel::setNotes,
            error = if (state.notesTooLong) pluralStringResource(R.plurals.form_too_long, 2000, 2000) else null,
        )
        // The inspection's due day as a calendar reminder (D-A25), once a date is picked.
        val vehicle = state.vehicle
        val nextDue = state.nextDue
        if (vehicle != null && nextDue != null) {
            val event = dueCalendarEvent(typeLabels[state.type] ?: state.type, vehicle, nextDue, state.sticker.takeIf { it.isNotBlank() }?.let { stringResource(R.string.inspection_sticker) + ": " + it }, state.notes)
            AddToCalendarButton(fmt.date(nextDue), onClick = { addToCalendar(event) })
        }
        FormEndSpacer()
    }
}

// ── Insurance ────────────────────────────────────────────────────────────────────────────────────────────────

data class InsuranceFormState(
    val vehicle: Vehicle? = null,
    val editing: InsurancePolicy? = null,
    val provider: String = "",
    val coverage: String = "LIABILITY",
    val sfClass: String = "",
    val premium: String = "",
    val policyNumber: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate = LocalDate.now().plusYears(1),
    val autoRenew: Boolean = true,
    val notes: String = "",
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val canEdit: Boolean get() = vehicle?.access?.canEdit == true
    val isEdit: Boolean get() = editing != null
    val premiumCents: Long? get() = formatter.parseNumber(premium)?.let(formatter::centsFromDisplay)
    val premiumInvalid: Boolean get() = premium.isNotBlank() && (premiumCents == null || premiumCents!! < 0)
    val providerTooLong: Boolean get() = provider.length > 120
    val sfTooLong: Boolean get() = sfClass.length > 20
    val numberTooLong: Boolean get() = policyNumber.length > 80
    val notesTooLong: Boolean get() = notes.length > 2000
    val endBeforeStart: Boolean get() = endDate.isBefore(startDate)
    val anyInvalid: Boolean get() = premiumInvalid || providerTooLong || sfTooLong || numberTooLong || notesTooLong || endBeforeStart
    val missing: Boolean get() = provider.isBlank() || premiumCents == null
    val canSave: Boolean get() = canEdit && !missing && !anyInvalid && !saving

    fun input(): InsuranceInput = InsuranceInput(
        provider = provider.trim(),
        startDate = formatter.instantFor(startDate).toString(),
        endDate = formatter.instantFor(endDate).toString(),
        premiumCents = premiumCents ?: 0,
        policyNumber = policyNumber.trim().ifEmpty { null },
        currency = formatter.currencyCode,
        sfClass = sfClass.trim().ifEmpty { null },
        coverageType = coverage,
        autoRenew = autoRenew,
        notes = notes.trim().ifEmpty { null },
    )

    fun patch(): InsurancePatch = InsurancePatch(
        provider = provider.trim(),
        startDate = formatter.instantFor(startDate).toString(),
        endDate = formatter.instantFor(endDate).toString(),
        premiumCents = premiumCents,
        policyNumber = policyNumber.trim().ifEmpty { null },
        sfClass = sfClass.trim().ifEmpty { null },
        coverageType = coverage,
        autoRenew = autoRenew,
        notes = notes.trim().ifEmpty { null },
        clear = buildSet {
            if (policyNumber.isBlank()) add("policyNumber")
            if (sfClass.isBlank()) add("sfClass")
            if (notes.isBlank()) add("notes")
        },
    )
}

@HiltViewModel
class InsuranceFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<InsuranceFormRoute>()
    private val form = MutableStateFlow(InsuranceFormState())
    val state: StateFlow<InsuranceFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicle = garage.vehicles.first().firstOrNull { it.id == route.vehicleId }
            val existing = route.policyId?.let { id -> garage.insurancePolicies.first().firstOrNull { it.id == id } }
            val today = fmt.today()
            form.value = InsuranceFormState(
                vehicle = vehicle,
                editing = existing,
                provider = existing?.provider.orEmpty(),
                coverage = existing?.coverageType ?: "LIABILITY",
                sfClass = existing?.sfClass.orEmpty(),
                premium = existing?.let { fmt.moneyInput(it.premiumCents) }.orEmpty(),
                policyNumber = existing?.policyNumber.orEmpty(),
                startDate = existing?.startDate?.toInstantOrNull()?.let(fmt::localDate) ?: today,
                endDate = existing?.endDate?.toInstantOrNull()?.let(fmt::localDate) ?: today.plusYears(1),
                autoRenew = existing?.autoRenew ?: true,
                notes = existing?.notes.orEmpty(),
                formatter = fmt,
                loaded = true,
            )
        }
    }

    fun setProvider(text: String) = form.update { it.copy(provider = text, dirty = true) }
    fun setCoverage(value: String) = form.update { it.copy(coverage = value, dirty = true) }
    fun setSfClass(text: String) = form.update { it.copy(sfClass = text, dirty = true) }
    fun setPremium(text: String) = form.update { it.copy(premium = text, dirty = true) }
    fun setPolicyNumber(text: String) = form.update { it.copy(policyNumber = text, dirty = true) }
    fun setStartDate(date: LocalDate) = form.update { it.copy(startDate = date, dirty = true) }
    fun setEndDate(date: LocalDate) = form.update { it.copy(endDate = date, dirty = true) }
    fun setAutoRenew(on: Boolean) = form.update { it.copy(autoRenew = on, dirty = true) }
    fun setNotes(text: String) = form.update { it.copy(notes = text, dirty = true) }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        val vehicleId = s.vehicle?.id ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = s.editing?.let { writer.updateInsurance(it.id, s.patch()) } ?: writer.addInsurance(vehicleId, s.input())
            val queued = report(result)
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }
}

@Composable
fun InsuranceFormScreen(onClose: () -> Unit, viewModel: InsuranceFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    val addToCalendar = rememberAddToCalendar(snackbar)
    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        state.vehicle == null -> stringResource(R.string.write_denied_unknown)
        !state.canEdit -> stringResource(R.string.view_only)
        state.provider.isBlank() -> stringResource(R.string.insurance_form_needs_provider)
        state.premiumCents == null && !state.premiumInvalid -> stringResource(R.string.insurance_form_needs_premium)
        state.anyInvalid -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    FormScreen(
        title = stringResource(if (state.isEdit) R.string.insurance_form_edit_title else R.string.insurance_form_title),
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
        state.vehicle?.let { vehicle ->
            Text(vehicle.displayName, style = MaterialTheme.typography.titleMedium)
            if (!vehicle.access.canEdit) ViewOnlyNote(vehicle.owner?.name)
        }
        // D-A50: suggests known insurers as the user types; any other name stays free text.
        FormAutocompleteField(
            value = state.provider,
            onValueChange = viewModel::setProvider,
            label = stringResource(R.string.insurance_provider),
            suggest = InsuranceProviders::suggest,
            capitalizeWords = true,
            error = if (state.providerTooLong) pluralStringResource(R.plurals.form_too_long, 120, 120) else null,
            fallbackIcon = Icons.Outlined.Shield,
            icon = { rememberInsurerIcon(it) },
        )
        val coverageLabels = COVERAGE_TYPES.associateWith { coverageLabel(it) }
        FormPicker(
            label = stringResource(R.string.insurance_coverage),
            options = COVERAGE_TYPES,
            selected = state.coverage,
            optionLabel = { coverageLabels[it] ?: it },
            onSelect = viewModel::setCoverage,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormMoneyField(
                value = state.premium,
                onValueChange = viewModel::setPremium,
                label = stringResource(R.string.insurance_premium_label),
                fmt = fmt,
                modifier = Modifier.weight(1f),
                error = if (state.premiumInvalid) stringResource(R.string.form_invalid_number) else null,
            )
            FormTextField(
                value = state.sfClass,
                onValueChange = viewModel::setSfClass,
                label = stringResource(R.string.insurance_sf_class),
                modifier = Modifier.weight(1f),
                error = if (state.sfTooLong) pluralStringResource(R.plurals.form_too_long, 20, 20) else null,
            )
        }
        FormTextField(
            value = state.policyNumber,
            onValueChange = viewModel::setPolicyNumber,
            label = stringResource(R.string.insurance_policy_number),
            error = if (state.numberTooLong) pluralStringResource(R.plurals.form_too_long, 80, 80) else null,
        )
        FormDateField(
            date = state.startDate,
            onDateChange = viewModel::setStartDate,
            label = stringResource(R.string.insurance_start),
            fmt = fmt,
            allowFuture = true,
        )
        FormDateField(
            date = state.endDate,
            onDateChange = viewModel::setEndDate,
            label = stringResource(R.string.insurance_end),
            fmt = fmt,
            allowFuture = true,
            error = if (state.endBeforeStart) stringResource(R.string.insurance_form_end_before_start) else null,
        )
        FormSwitchRow(
            label = stringResource(R.string.insurance_auto_renew),
            supporting = stringResource(R.string.insurance_form_auto_renew_hint),
            checked = state.autoRenew,
            onCheckedChange = viewModel::setAutoRenew,
        )
        FormNotesField(
            value = state.notes,
            onValueChange = viewModel::setNotes,
            error = if (state.notesTooLong) pluralStringResource(R.plurals.form_too_long, 2000, 2000) else null,
        )
        // The renewal (the policy's end) as a calendar reminder (D-A25); the end date always has a value.
        state.vehicle?.let { vehicle ->
            val provider = state.provider.trim().ifEmpty { stringResource(R.string.insurance_provider) }
            val event = renewalCalendarEvent(provider, vehicle, state.endDate, coverageLabels[state.coverage], state.policyNumber.takeIf { it.isNotBlank() }?.let { stringResource(R.string.insurance_policy_number) + ": " + it })
            AddToCalendarButton(fmt.date(state.endDate), onClick = { addToCalendar(event) })
        }
        FormEndSpacer()
    }
}

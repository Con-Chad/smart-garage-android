package io.github.conchad.smartgarage.feature.expenses

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
import io.github.conchad.smartgarage.core.data.ExpenseInput
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.LoadingState
import io.github.conchad.smartgarage.ui.components.expenseCategoryLabel
import io.github.conchad.smartgarage.ui.components.forms.FormDateField
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormMoneyField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.navigation.ExpenseFormRoute
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
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
    val canSave: Boolean get() = canEdit && amountCents != null && !amountInvalid && !descriptionTooLong && !saving

    fun input(now: Instant = Instant.now()) = ExpenseInput(
        category = category,
        occurredAt = formatter.instantFor(date, now).toString(),
        amountCents = amountCents ?: 0,
        currency = formatter.currencyCode,
        description = description.trim().ifEmpty { null },
    )
}

@HiltViewModel
class ExpenseFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<ExpenseFormRoute>()
    private val form = MutableStateFlow(ExpenseFormState())
    val state: StateFlow<ExpenseFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicles = garage.vehicles.first().filter { it.access.canEdit }.sortedWith(GarageMath.vehicleOrder)
            val preselected = route.vehicleId?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id
            form.value = ExpenseFormState(vehicles = vehicles, vehicleId = preselected, date = fmt.today(), formatter = fmt, loaded = true)
        }
    }

    fun selectVehicle(id: String) = form.update { it.copy(vehicleId = id, dirty = true) }
    fun setCategory(category: String) = form.update { it.copy(category = category, dirty = true) }
    fun setDate(date: LocalDate) = form.update { it.copy(date = date, dirty = true) }
    fun setAmount(text: String) = form.update { it.copy(amount = text, dirty = true) }
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
}

@Composable
fun ExpenseFormScreen(onClose: () -> Unit, viewModel: ExpenseFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

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
            LoadingState(stringResource(R.string.loading))
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
        )
        FormNotesField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            label = stringResource(R.string.expense_description),
            error = if (state.descriptionTooLong) pluralStringResource(R.plurals.form_too_long, 500, 500) else null,
        )
        FormEndSpacer()
    }
}

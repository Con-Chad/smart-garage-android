package io.github.conchad.smartgarage.feature.wishlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
import io.github.conchad.smartgarage.core.data.WishlistInput
import io.github.conchad.smartgarage.core.data.WishlistPatch
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.WishlistItem
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormMoneyField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormNumberField
import io.github.conchad.smartgarage.ui.components.forms.FormOptionalDateField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.components.forms.VehicleChoice
import io.github.conchad.smartgarage.ui.components.wishlistCategoryLabel
import io.github.conchad.smartgarage.ui.components.wishlistStatusLabel
import io.github.conchad.smartgarage.ui.navigation.WishlistFormRoute
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

val WISHLIST_CATEGORIES = listOf("VEHICLE", "PART", "ACCESSORY", "OTHER")
val WISHLIST_STATUSES = listOf("IDEA", "PLANNED", "ORDERED", "DONE")

/** The web's "Add item" form plus the fields the API takes (status, priority, cost, link). */
data class WishlistFormState(
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val editingId: String? = null,
    val title: String = "",
    val category: String = "VEHICLE",
    val status: String = "IDEA",
    val priority: String = "",
    val estimate: String = "",
    val targetDate: LocalDate? = null,
    val url: String = "",
    val description: String = "",
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val editing: Boolean get() = editingId != null
    val priorityValue: Int? get() = formatter.parseNumber(priority)?.toInt()
    val priorityInvalid: Boolean get() = priority.isNotBlank() && (priorityValue == null || priorityValue!! !in 0..10)
    val estimateCents: Long? get() = formatter.parseNumber(estimate)?.let(formatter::centsFromDisplay)
    val estimateInvalid: Boolean get() = estimate.isNotBlank() && (estimateCents == null || estimateCents!! < 0)
    val titleTooLong: Boolean get() = title.length > 160
    val urlTooLong: Boolean get() = url.length > 500
    val descriptionTooLong: Boolean get() = description.length > 2000
    val canSave: Boolean
        get() = title.isNotBlank() && !titleTooLong && !priorityInvalid && !estimateInvalid && !urlTooLong && !descriptionTooLong && !saving

    private fun targetInstant(): String? = targetDate?.let { formatter.instantFor(it).toString() }

    fun input() = WishlistInput(
        title = title.trim(),
        description = description.trim().ifEmpty { null },
        category = category,
        status = status,
        priority = priorityValue ?: 0,
        targetDate = targetInstant(),
        url = url.trim().ifEmpty { null },
        estimatedCostCents = estimateCents,
        currency = formatter.currencyCode,
        vehicleId = vehicleId,
    )

    fun patch() = WishlistPatch(
        title = title.trim(),
        description = description.trim().ifEmpty { null },
        category = category,
        status = status,
        priority = priorityValue ?: 0,
        targetDate = targetInstant(),
        url = url.trim().ifEmpty { null },
        estimatedCostCents = estimateCents,
        vehicleId = vehicleId,
        clear = buildSet {
            if (description.isBlank()) add("description")
            if (targetDate == null) add("targetDate")
            if (url.isBlank()) add("url")
            if (estimateCents == null) add("estimatedCostCents")
            if (vehicleId == null) add("vehicleId")
        },
    )
}

@HiltViewModel
class WishlistFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<WishlistFormRoute>()
    private val form = MutableStateFlow(WishlistFormState())
    val state: StateFlow<WishlistFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicles = garage.vehicles.first().sortedWith(GarageMath.vehicleOrder)
            val item: WishlistItem? = route.itemId?.let { id -> garage.wishlist.first().firstOrNull { it.id == id } }
            form.value = WishlistFormState(
                vehicles = vehicles,
                vehicleId = (item?.vehicleId ?: route.vehicleId)?.takeIf { id -> vehicles.any { it.id == id } },
                editingId = item?.id,
                title = item?.title.orEmpty(),
                category = item?.category ?: "VEHICLE",
                status = item?.status ?: "IDEA",
                priority = item?.priority?.takeIf { it > 0 }?.toString().orEmpty(),
                estimate = item?.estimatedCostCents?.let(fmt::moneyInput).orEmpty(),
                targetDate = item?.targetDate?.toInstantOrNull()?.let(fmt::localDate),
                url = item?.url.orEmpty(),
                description = item?.description.orEmpty(),
                formatter = fmt,
                loaded = true,
            )
        }
    }

    fun selectVehicle(id: String?) = form.update { it.copy(vehicleId = id, dirty = true) }
    fun setTitle(text: String) = form.update { it.copy(title = text, dirty = true) }
    fun setCategory(value: String) = form.update { it.copy(category = value, dirty = true) }
    fun setStatus(value: String) = form.update { it.copy(status = value, dirty = true) }
    fun setPriority(text: String) = form.update { it.copy(priority = text, dirty = true) }
    fun setEstimate(text: String) = form.update { it.copy(estimate = text, dirty = true) }
    fun setTargetDate(date: LocalDate?) = form.update { it.copy(targetDate = date, dirty = true) }
    fun setUrl(text: String) = form.update { it.copy(url = text, dirty = true) }
    fun setDescription(text: String) = form.update { it.copy(description = text, dirty = true) }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = s.editingId?.let { writer.updateWishlistItem(it, s.patch()) } ?: writer.addWishlistItem(s.input())
            val queued = report(result)
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }
}

@Composable
fun WishlistFormScreen(onClose: () -> Unit, viewModel: WishlistFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        state.title.isBlank() -> stringResource(R.string.wishlist_form_needs_title)
        !state.canSave && !state.saving -> stringResource(R.string.form_fix_errors)
        else -> null
    }
    val noVehicle = stringResource(R.string.wishlist_no_vehicle)
    val choices = remember(state.vehicles, noVehicle) { listOf(VehicleChoice(null, noVehicle)) + state.vehicles.map { VehicleChoice(it, it.displayName) } }

    FormScreen(
        title = stringResource(if (state.editing) R.string.wishlist_form_edit_title else R.string.wishlist_form_title),
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
        FormTextField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            label = stringResource(R.string.wishlist_item_title),
            error = if (state.titleTooLong) pluralStringResource(R.plurals.form_too_long, 160, 160) else null,
        )
        val categoryLabels = WISHLIST_CATEGORIES.associateWith { wishlistCategoryLabel(it) }
        val statusLabels = WISHLIST_STATUSES.associateWith { wishlistStatusLabel(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormPicker(
                label = stringResource(R.string.wishlist_category),
                options = WISHLIST_CATEGORIES,
                selected = state.category,
                optionLabel = { categoryLabels[it] ?: it },
                onSelect = viewModel::setCategory,
                modifier = Modifier.weight(1f),
            )
            FormPicker(
                label = stringResource(R.string.wishlist_status),
                options = WISHLIST_STATUSES,
                selected = state.status,
                optionLabel = { statusLabels[it] ?: it },
                onSelect = viewModel::setStatus,
                modifier = Modifier.weight(1f),
            )
        }
        FormPicker(
            label = stringResource(R.string.field_vehicle),
            options = choices,
            selected = choices.firstOrNull { it.vehicle?.id == state.vehicleId } ?: choices.first(),
            optionLabel = { it.label },
            onSelect = { viewModel.selectVehicle(it.vehicle?.id) },
            enabled = state.vehicles.isNotEmpty(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormMoneyField(
                value = state.estimate,
                onValueChange = viewModel::setEstimate,
                label = stringResource(R.string.wishlist_estimate),
                fmt = fmt,
                modifier = Modifier.weight(1f),
                error = if (state.estimateInvalid) stringResource(R.string.form_invalid_number) else null,
            )
            FormNumberField(
                value = state.priority,
                onValueChange = viewModel::setPriority,
                label = stringResource(R.string.wishlist_priority),
                modifier = Modifier.weight(1f),
                integer = true,
                supporting = stringResource(R.string.wishlist_priority_hint),
                error = if (state.priorityInvalid) stringResource(R.string.wishlist_priority_invalid) else null,
            )
        }
        FormOptionalDateField(
            date = state.targetDate,
            onDateChange = viewModel::setTargetDate,
            label = stringResource(R.string.wishlist_target),
            fmt = fmt,
            allowFuture = true,
        )
        FormTextField(
            value = state.url,
            onValueChange = viewModel::setUrl,
            label = stringResource(R.string.wishlist_url),
            error = if (state.urlTooLong) pluralStringResource(R.plurals.form_too_long, 500, 500) else null,
            imeAction = ImeAction.Next,
        )
        FormNotesField(
            value = state.description,
            onValueChange = viewModel::setDescription,
            label = stringResource(R.string.wishlist_description),
            error = if (state.descriptionTooLong) pluralStringResource(R.plurals.form_too_long, 2000, 2000) else null,
        )
        FormEndSpacer()
    }
}

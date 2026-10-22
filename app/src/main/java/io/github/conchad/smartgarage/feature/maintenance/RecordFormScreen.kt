package io.github.conchad.smartgarage.feature.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.ui.components.AddToCalendarButton
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.LoadingState
import io.github.conchad.smartgarage.ui.components.enumLabel
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.components.forms.FormDateField
import io.github.conchad.smartgarage.ui.components.forms.FormDistanceField
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormMoneyField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormNumberField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.FormSection
import io.github.conchad.smartgarage.ui.components.forms.FormSwitchRow
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.components.intervalText
import io.github.conchad.smartgarage.ui.components.unitLabel
import io.github.conchad.smartgarage.ui.links.dueCalendarEvent
import io.github.conchad.smartgarage.ui.links.rememberAddToCalendar

/** Log or edit a service (D-A20): vehicle and schedule, date, odometer, cost, shop, notes, then the parts. */
@Composable
fun RecordFormScreen(onClose: () -> Unit, viewModel: RecordFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    var confirmRegression by rememberSaveable { mutableStateOf(false) }
    val addToCalendar = rememberAddToCalendar(snackbar)

    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        state.vehicles.isEmpty() -> stringResource(R.string.form_no_editable_vehicles)
        !state.canEdit -> stringResource(R.string.view_only)
        state.scheduleId == null -> stringResource(R.string.record_form_needs_schedule)
        state.odometerInvalid || state.costInvalid || state.itemsInvalid -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    FormScreen(
        title = stringResource(if (state.editing) R.string.record_form_edit_title else R.string.record_form_title),
        onClose = onClose,
        saveLabel = stringResource(R.string.action_save),
        canSave = state.canSave,
        saving = state.saving,
        onSave = { if (state.odometerBelowCurrent && !state.editing) confirmRegression = true else viewModel.save() },
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
            enabled = !state.locked && state.vehicles.size > 1,
            placeholder = stringResource(R.string.form_no_editable_vehicles).takeIf { state.vehicles.isEmpty() },
        )
        if (state.vehicle != null && !state.canEdit) ViewOnlyNote(state.vehicle?.owner?.name)
        FormPicker(
            label = stringResource(R.string.field_schedule),
            options = state.schedules,
            selected = state.schedule,
            optionLabel = { it.name },
            onSelect = { viewModel.selectSchedule(it.schedule.id) },
            enabled = !state.locked && state.schedules.isNotEmpty(),
            placeholder = stringResource(R.string.record_form_no_schedules).takeIf { state.vehicleId != null && state.schedules.isEmpty() },
            supporting = stringResource(R.string.record_form_no_schedules).takeIf { state.vehicleId != null && state.schedules.isEmpty() },
        )
        FormDateField(date = state.date, onDateChange = viewModel::setDate, label = stringResource(R.string.field_date), fmt = fmt)
        FormDistanceField(
            value = state.odometer,
            onValueChange = viewModel::setOdometer,
            label = stringResource(R.string.field_odometer),
            fmt = fmt,
            error = if (state.odometerInvalid) stringResource(R.string.form_invalid_number) else null,
            supporting = when {
                state.odometerBelowCurrent && !state.editing -> stringResource(R.string.form_odometer_below_current, fmt.distance(state.currentOdometerKm ?: 0))
                state.currentOdometerKm != null -> stringResource(R.string.form_odometer_current, fmt.distance(state.currentOdometerKm ?: 0))
                else -> null
            },
        )
        FormMoneyField(
            value = state.cost,
            onValueChange = viewModel::setCost,
            label = stringResource(R.string.field_cost),
            fmt = fmt,
            error = if (state.costInvalid) stringResource(R.string.form_invalid_number) else null,
            supporting = state.itemsTotalCents?.let { stringResource(R.string.record_form_parts_total, fmt.money(it)) },
        )
        if (state.itemsTotalCents != null && state.itemsTotalCents != state.costCents) {
            TextButton(onClick = viewModel::useItemsTotal, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.record_form_use_parts_total))
            }
        }
        FormTextField(value = state.vendor, onValueChange = viewModel::setVendor, label = stringResource(R.string.field_vendor), capitalizeWords = true)
        FormNotesField(value = state.notes, onValueChange = viewModel::setNotes)
        // The next due date this service implies (date + the schedule's month interval), as a calendar reminder (D-A25).
        val vehicle = state.vehicle
        val option = state.schedule
        val nextDue = state.nextDue
        if (vehicle != null && option != null && nextDue != null) {
            val event = dueCalendarEvent(option.name, vehicle, nextDue, intervalText(option.schedule.intervalKm, option.schedule.intervalMonths, fmt))
            AddToCalendarButton(fmt.date(nextDue), onClick = { addToCalendar(event) })
        }

        FormSection(
            stringResource(R.string.schedule_parts),
            action = {
                TextButton(onClick = viewModel::addItem) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.record_form_add_part), modifier = Modifier.padding(start = 4.dp))
                }
            },
        )
        if (state.items.isEmpty()) {
            Text(
                stringResource(R.string.record_form_no_parts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.items.forEach { item ->
            ItemEditor(
                item = item,
                fmt = fmt,
                onName = { viewModel.setItemName(item.key, it) },
                onQuantity = { viewModel.setItemQuantity(item.key, it) },
                onCost = { viewModel.setItemCost(item.key, it) },
                onRemove = { viewModel.removeItem(item.key) },
            )
        }
        if (state.items.isNotEmpty()) {
            FormSwitchRow(
                label = stringResource(R.string.record_form_save_as_default),
                supporting = stringResource(R.string.record_form_save_as_default_hint),
                checked = state.saveAsDefault,
                onCheckedChange = viewModel::setSaveAsDefault,
            )
        }
        FormEndSpacer()
    }

    if (confirmRegression) {
        ConfirmDialog(
            title = stringResource(R.string.form_odometer_confirm_title),
            body = stringResource(R.string.form_odometer_confirm_body, fmt.distance(state.odometerKm ?: 0), fmt.distance(state.currentOdometerKm ?: 0)),
            confirmLabel = stringResource(R.string.action_save_anyway),
            onConfirm = {
                confirmRegression = false
                viewModel.save()
            },
            onDismiss = { confirmRegression = false },
        )
    }
}

/**
 * One part: name on top, quantity (with the item's unit) and cost side by side, a remove button at the end. Shared
 * with the schedule's default-parts editor, so both lists look and read the same.
 */
@Composable
internal fun ItemEditor(
    item: ItemDraft,
    fmt: Formatter,
    onName: (String) -> Unit,
    onQuantity: (String) -> Unit,
    onCost: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val category = item.base.category.takeIf { it != "OTHER" }?.let { enumLabel(it) }
                Text(
                    category ?: stringResource(R.string.record_form_part),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                val removeLabel = stringResource(R.string.record_form_remove_part, item.name.ifBlank { category ?: "" }.trim())
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = removeLabel) }
            }
            FormTextField(
                value = item.name,
                onValueChange = onName,
                label = stringResource(R.string.record_form_part_name),
                modifier = Modifier.padding(end = 8.dp),
                supporting = listOfNotNull(item.base.brand, item.base.partNumber, item.base.specification).joinToString(" · ").ifEmpty { null },
            )
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FormNumberField(
                    value = item.quantity,
                    onValueChange = onQuantity,
                    label = stringResource(R.string.record_form_quantity),
                    unit = item.base.customUnit ?: item.base.unit?.let { unitLabel(it) },
                    modifier = Modifier.weight(1f),
                    error = if (item.quantityInvalid(fmt)) stringResource(R.string.form_invalid_number) else null,
                )
                FormMoneyField(
                    value = item.cost,
                    onValueChange = onCost,
                    label = stringResource(R.string.field_cost),
                    fmt = fmt,
                    modifier = Modifier.weight(1f),
                    error = if (item.costInvalid(fmt)) stringResource(R.string.form_invalid_number) else null,
                    imeAction = ImeAction.Next,
                )
            }
        }
    }
}

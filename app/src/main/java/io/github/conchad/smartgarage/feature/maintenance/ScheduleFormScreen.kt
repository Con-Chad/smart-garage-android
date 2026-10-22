package io.github.conchad.smartgarage.feature.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.LoadingState
import io.github.conchad.smartgarage.ui.components.forms.FormDisclosure
import io.github.conchad.smartgarage.ui.components.forms.FormDistanceField
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormMoneyField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormNumberField
import io.github.conchad.smartgarage.ui.components.forms.FormOptionalDateField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.FormSection
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.components.maintenanceCategoryLabel

/**
 * New or edited service schedule (D-A20): vehicle, template or custom name and category, the two intervals,
 * estimated cost and notes. Editing adds the baseline (last done, odometer then) the web adjusts on the schedule page.
 */
@Composable
fun ScheduleFormScreen(onClose: () -> Unit, viewModel: ScheduleFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    var baselineOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        state.vehicles.isEmpty() -> stringResource(R.string.form_no_editable_vehicles)
        !state.canEdit -> stringResource(R.string.view_only)
        state.needsName -> stringResource(R.string.schedule_form_needs_name)
        state.anyInvalid -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    FormScreen(
        title = stringResource(if (state.editing) R.string.schedule_form_edit_title else R.string.schedule_form_title),
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
            enabled = !state.editing && state.vehicles.size > 1,
            placeholder = stringResource(R.string.form_no_editable_vehicles).takeIf { state.vehicles.isEmpty() },
        )
        if (state.vehicle != null && !state.canEdit) ViewOnlyNote(state.vehicle?.owner?.name)

        if (!state.editing) {
            val customLabel = stringResource(R.string.schedule_form_custom)
            FormPicker(
                label = stringResource(R.string.schedule_form_template),
                options = state.templates,
                selected = state.template ?: state.templates.firstOrNull(),
                optionLabel = { it.template?.let { _ -> it.name } ?: customLabel },
                onSelect = { viewModel.selectTemplate(it.template?.id) },
                supporting = stringResource(R.string.schedule_form_template_hint),
            )
        }
        FormTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = stringResource(R.string.schedule_form_name),
            capitalizeWords = true,
            supporting = if (state.custom) null else stringResource(R.string.schedule_form_name_optional, state.template?.name.orEmpty()),
            error = if (state.nameTooLong) pluralStringResource(R.plurals.form_too_long, 120, 120) else null,
        )
        if (state.custom || state.editing) {
            val categoryLabels = MAINTENANCE_CATEGORIES.associateWith { maintenanceCategoryLabel(it) }
            FormPicker(
                label = stringResource(R.string.wishlist_category),
                options = MAINTENANCE_CATEGORIES,
                selected = state.category,
                optionLabel = { categoryLabels[it] ?: it },
                onSelect = viewModel::setCategory,
            )
        }

        FormSection(stringResource(R.string.schedule_interval))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormDistanceField(
                value = state.intervalDistance,
                onValueChange = viewModel::setIntervalDistance,
                label = stringResource(R.string.schedule_form_interval_distance),
                fmt = fmt,
                modifier = Modifier.weight(1f),
                error = if (state.intervalKmInvalid) stringResource(R.string.form_invalid_number) else null,
            )
            FormNumberField(
                value = state.intervalMonths,
                onValueChange = viewModel::setIntervalMonths,
                label = stringResource(R.string.schedule_form_interval_months),
                modifier = Modifier.weight(1f),
                integer = true,
                error = if (state.monthsInvalid) stringResource(R.string.schedule_form_months_invalid) else null,
            )
        }
        Text(
            stringResource(R.string.schedule_form_interval_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FormMoneyField(
            value = state.cost,
            onValueChange = viewModel::setCost,
            label = stringResource(R.string.schedule_estimated_cost),
            fmt = fmt,
            error = if (state.costInvalid) stringResource(R.string.form_invalid_number) else null,
        )
        FormNotesField(
            value = state.notes,
            onValueChange = viewModel::setNotes,
            error = if (state.notesTooLong) pluralStringResource(R.plurals.form_too_long, 2000, 2000) else null,
        )

        if (state.editing) {
            FormDisclosure(
                title = stringResource(R.string.schedule_form_baseline),
                summary = stringResource(R.string.schedule_form_baseline_hint),
                expanded = baselineOpen,
                onToggle = { baselineOpen = !baselineOpen },
            ) {
                FormOptionalDateField(
                    date = state.lastPerformed,
                    onDateChange = viewModel::setLastPerformed,
                    label = stringResource(R.string.schedule_last_done),
                    fmt = fmt,
                )
                FormDistanceField(
                    value = state.lastOdometer,
                    onValueChange = viewModel::setLastOdometer,
                    label = stringResource(R.string.schedule_form_last_odometer),
                    fmt = fmt,
                    error = if (state.lastOdometerInvalid) stringResource(R.string.form_invalid_number) else null,
                )
            }
        }
        FormEndSpacer()
    }
}

/** The schedule's default parts and fluids: the same editable rows a service record has; saving an empty list clears them. */
@Composable
fun ScheduleDefaultsScreen(onClose: () -> Unit, viewModel: ScheduleDefaultsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        state.scheduleId == null -> stringResource(R.string.write_denied_unknown)
        !state.canEdit -> stringResource(R.string.view_only)
        state.itemsInvalid -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    FormScreen(
        title = stringResource(R.string.schedule_defaults_title),
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
        Text(state.name, style = MaterialTheme.typography.titleMedium)
        state.vehicle?.let { vehicle ->
            Text(vehicle.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!vehicle.access.canEdit) ViewOnlyNote(vehicle.owner?.name)
        }
        Text(
            stringResource(R.string.schedule_defaults_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                stringResource(R.string.schedule_defaults_empty),
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
        FormEndSpacer()
    }
}

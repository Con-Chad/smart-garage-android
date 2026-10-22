package io.github.conchad.smartgarage.feature.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.LoadingState
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.components.forms.FormDateField
import io.github.conchad.smartgarage.ui.components.forms.FormDistanceField
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormMoneyField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.components.forms.FormVolumeField
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote

/**
 * The gas-station form (D-A20): vehicle, date, then the three big numbers (odometer, volume, price), the total,
 * station and notes. Prefilled so the usual fill-up is three taps: check the odometer, type the volume, save.
 */
@Composable
fun FuelEntryScreen(onClose: () -> Unit, viewModel: FuelFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    var confirmRegression by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        state.vehicles.isEmpty() -> stringResource(R.string.form_no_editable_vehicles)
        !state.canEdit -> stringResource(R.string.view_only)
        state.totalCents == null -> stringResource(R.string.fuel_form_needs_total)
        state.odometerInvalid || state.volumeInvalid || state.priceInvalid || state.totalInvalid -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    FormScreen(
        title = stringResource(R.string.fuel_form_title),
        onClose = onClose,
        saveLabel = stringResource(R.string.action_save),
        canSave = state.canSave,
        saving = state.saving,
        onSave = { if (state.odometerBelowCurrent) confirmRegression = true else viewModel.save() },
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

        FormDateField(
            date = state.date,
            onDateChange = viewModel::setDate,
            label = stringResource(R.string.field_date),
            fmt = fmt,
        )
        FormDistanceField(
            value = state.odometer,
            onValueChange = viewModel::setOdometer,
            label = stringResource(R.string.field_odometer),
            fmt = fmt,
            large = true,
            error = when {
                state.odometerInvalid -> stringResource(R.string.form_invalid_number)
                else -> null
            },
            supporting = when {
                state.odometerBelowCurrent -> stringResource(R.string.form_odometer_below_current, fmt.distance(state.currentOdometerKm ?: 0))
                state.currentOdometerKm != null -> stringResource(R.string.form_odometer_current, fmt.distance(state.currentOdometerKm ?: 0))
                else -> null
            },
        )
        FormVolumeField(
            value = state.volume,
            onValueChange = viewModel::setVolume,
            label = stringResource(R.string.fuel_volume),
            fmt = fmt,
            large = true,
            error = if (state.volumeInvalid) stringResource(R.string.form_invalid_number) else null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FormMoneyField(
                value = state.price,
                onValueChange = viewModel::setPrice,
                label = stringResource(R.string.fuel_form_price_per, fmt.volumeUnit),
                fmt = fmt,
                modifier = Modifier.weight(1f),
                error = if (state.priceInvalid) stringResource(R.string.form_invalid_number) else null,
            )
            FormMoneyField(
                value = state.total,
                onValueChange = viewModel::setTotal,
                label = stringResource(R.string.fuel_form_total),
                fmt = fmt,
                modifier = Modifier.weight(1f),
                large = true,
                error = if (state.totalInvalid) stringResource(R.string.form_invalid_number) else null,
            )
        }
        state.lastEntry?.let { last ->
            val parts = listOfNotNull(
                last.filledAt.toInstantOrNull()?.let(fmt::shortDate),
                last.odometerKm?.let(fmt::distance),
                last.liters?.let { fmt.unitPrice(last.totalCostCents, it, last.currency) },
            )
            Text(
                stringResource(R.string.fuel_form_last_fill_up, parts.joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FormTextField(
            value = state.station,
            onValueChange = viewModel::setStation,
            label = stringResource(R.string.fuel_station),
            capitalizeWords = true,
        )
        FormNotesField(value = state.notes, onValueChange = viewModel::setNotes)
        FormEndSpacer()
    }

    if (confirmRegression) {
        ConfirmDialog(
            title = stringResource(R.string.form_odometer_confirm_title),
            body = stringResource(
                R.string.form_odometer_confirm_body,
                fmt.distance(state.odometerKm ?: 0),
                fmt.distance(state.currentOdometerKm ?: 0),
            ),
            confirmLabel = stringResource(R.string.action_save_anyway),
            onConfirm = {
                confirmRegression = false
                viewModel.save()
            },
            onDismiss = { confirmRegression = false },
        )
    }
}

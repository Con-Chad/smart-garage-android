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
import io.github.conchad.smartgarage.ui.components.forms.FormSkeleton
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
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
import io.github.conchad.smartgarage.feature.receipts.ReceiptPhotoRow
import io.github.conchad.smartgarage.feature.receipts.ReceiptScanButtons
import io.github.conchad.smartgarage.feature.receipts.ScanReviewNote
import io.github.conchad.smartgarage.feature.receipts.messageRes
import io.github.conchad.smartgarage.feature.receipts.rememberReceiptCamera
import io.github.conchad.smartgarage.feature.receipts.rememberReceiptGallery

/**
 * The gas-station form (D-A20): vehicle, date, then the two big numbers (odometer, volume), price and total side by
 * side, station and notes. Prefilled so the usual fill-up is three taps: check the odometer, type the volume, save.
 */
@Composable
fun FuelEntryScreen(
    onClose: () -> Unit,
    scanOnOpen: Boolean = false,
    /** With [scanOnOpen]: the Photo Picker instead of the camera (D-A61). */
    scanFromGallery: Boolean = false,
    viewModel: FuelFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    var confirmRegression by rememberSaveable { mutableStateOf(false) }
    // The fan's "Scan Receipt" petal (D-A32): once, when the form has loaded; saved so a rotation doesn't start it again.
    var scanPending by rememberSaveable { mutableStateOf(scanOnOpen) }

    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    // Receipt scan (D-A27): the camera app writes into a cache file of ours, as on the document form (D-A23).
    val launchScan = rememberReceiptCamera(viewModel::startScan, viewModel::captured, viewModel::cameraUnavailable)
    // Or an existing photo from the gallery through the Photo Picker (D-A56): same upload and prefill.
    val pickPhoto = rememberReceiptGallery(viewModel::picked, viewModel::galleryUnavailable)
    val scanMessage = state.scanMessage?.let { stringResource(it.messageRes()) }
    LaunchedEffect(scanMessage) {
        if (scanMessage != null) {
            snackbar.showSnackbar(scanMessage)
            viewModel.clearScanMessage()
        }
    }
    val scanUnavailable = stringResource(R.string.fuel_scan_unavailable)
    val viewOnly = stringResource(R.string.view_only)
    LaunchedEffect(state.loaded) {
        if (!scanPending || !state.loaded) return@LaunchedEffect
        scanPending = false
        when {
            state.canScan -> if (scanFromGallery) pickPhoto() else launchScan()
            !state.scannerAvailable -> snackbar.showSnackbar(scanUnavailable)
            else -> snackbar.showSnackbar(viewOnly)
        }
    }

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
        // Only servers that list the "fuel" scan get the button (D-A53); elsewhere the form is unchanged.
        if (state.scannerAvailable && state.canEdit) {
            ReceiptScanButtons(scanning = state.scanning, enabled = state.canScan, onCamera = launchScan, onGallery = pickPhoto)
            state.receiptPhoto?.let { ReceiptPhotoRow(it, onRemove = viewModel::removeReceiptPhoto, linked = state.receiptDocumentId != null) }
            ScanReviewNote(state.scanReview, extra = state.foreignCurrency?.let { stringResource(R.string.receipt_scan_currency, it) })
        }

        FormDateField(
            date = state.date,
            onDateChange = viewModel::setDate,
            label = stringResource(R.string.field_date),
            fmt = fmt,
        )
        // Hidden when fuel economy isn't tracked (D-A25): no reading is asked for or sent.
        if (state.trackOdometer) {
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
        }
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
            // Same size as its neighbour: an outlined field rests its label at the text size, so a large Total next
            // to a regular price sat lower and taller. The big numbers are the odometer and the volume above.
            FormMoneyField(
                value = state.total,
                onValueChange = viewModel::setTotal,
                label = stringResource(R.string.fuel_form_total),
                fmt = fmt,
                modifier = Modifier.weight(1f),
                error = if (state.totalInvalid) stringResource(R.string.form_invalid_number) else null,
            )
        }
        state.lastEntry?.let { last ->
            val parts = listOfNotNull(
                last.filledAt.toInstantOrNull()?.let(fmt::shortDate),
                last.odometerKm?.takeIf { state.trackOdometer }?.let(fmt::distance),
                last.liters?.let { fmt.unitPrice(last.totalCostCents, it, last.currency) },
            )
            Text(
                stringResource(R.string.fuel_form_last_fill_up, parts.joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
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

package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.OdometerInput
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.OdometerReminder
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.components.forms.FormDateField
import io.github.conchad.smartgarage.ui.components.forms.FormDistanceField
import io.github.conchad.smartgarage.ui.components.forms.FormSheet
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.glass.glass
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

data class OdometerFormState(
    val reading: String = "",
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val currentOdometerKm: Int = 0,
    /** When the odometer was last recorded (a log or a fill-up with a reading, D-A70); null when it never was. */
    val lastUpdatedAt: Instant? = null,
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val done: Boolean = false,
) {
    val readingKm: Int? get() = formatter.parseNumber(reading)?.let(formatter::kmFromDisplay)
    val invalid: Boolean get() = reading.isNotBlank() && (readingKm == null || readingKm!! < 0)
    val belowCurrent: Boolean get() = readingKm?.let { it < currentOdometerKm } == true
    val canSave: Boolean get() = readingKm != null && !invalid && !saving
}

/** A new odometer reading for one vehicle; keyed by vehicle so the detail and the dashboard can each open one. */
@HiltViewModel(assistedFactory = OdometerFormViewModel.Factory::class)
class OdometerFormViewModel @AssistedInject constructor(
    @Assisted private val vehicleId: String,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {

    @AssistedFactory
    interface Factory {
        fun create(vehicleId: String): OdometerFormViewModel
    }

    private val form = MutableStateFlow(OdometerFormState())
    val state: StateFlow<OdometerFormState> = form.asStateFlow()

    init {
        reset()
    }

    /** Fresh form: today's date, the current reading as the starting value. */
    fun reset() {
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val current = garage.vehicles.first().firstOrNull { it.id == vehicleId }?.currentOdometerKm ?: 0
            val lastUpdated = OdometerReminder.lastUpdate(vehicleId, garage.odometerLogs.first(), garage.fuelEntries.first())
            form.value = OdometerFormState(
                reading = fmt.distanceInput(current),
                date = fmt.today(),
                currentOdometerKm = current,
                lastUpdatedAt = lastUpdated,
                formatter = fmt,
                loaded = true,
            )
        }
    }

    fun setReading(text: String) = form.update { it.copy(reading = text) }
    fun setDate(date: LocalDate) = form.update { it.copy(date = date) }
    fun setNote(text: String) = form.update { it.copy(note = text) }

    fun save() {
        val s = form.value
        val km = s.readingKm ?: return
        if (!s.canSave) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val input = OdometerInput(
                odometerKm = km,
                recordedAt = s.formatter.instantFor(s.date).toString(),
                note = s.note.trim().ifEmpty { null },
            )
            val queued = report(writer.addOdometerLog(vehicleId, input))
            form.update { it.copy(saving = false, done = queued) }
        }
    }
}

/**
 * The odometer quick update (D-A20): one number, the date, an optional note, in a sheet. A reading below the
 * vehicle's current one asks first; the server accepts it (a late entry), but it is usually a typo.
 */
@Composable
fun OdometerSheet(vehicleId: String, onDismiss: () -> Unit, snackbar: SnackbarHostState) {
    val viewModel = hiltViewModel<OdometerFormViewModel, OdometerFormViewModel.Factory>(key = "odometer-$vehicleId") { it.create(vehicleId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var confirm by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.done) {
        if (state.done) {
            onDismiss()
            viewModel.reset()
        }
    }
    DeniedSnackbar(viewModel, snackbar)

    FormSheet(
        title = stringResource(R.string.odometer_form_title),
        onDismiss = {
            onDismiss()
            viewModel.reset()
        },
        saveLabel = stringResource(R.string.action_save),
        canSave = state.loaded && state.canSave,
        saving = state.saving,
        onSave = { if (state.belowCurrent) confirm = true else viewModel.save() },
        validation = when {
            !state.loaded -> null
            state.readingKm == null -> stringResource(R.string.odometer_form_needs_reading)
            state.invalid -> stringResource(R.string.form_invalid_number)
            else -> null
        },
    ) {
        // D-A70: when the reading was last taken, so one can tell at a glance whether it is stale.
        if (state.loaded) {
            val last = state.lastUpdatedAt
            Text(
                when {
                    last != null -> stringResource(R.string.odometer_last_updated, fmt.dateTime(last))
                    // A reading from the vehicle itself (entered with the car), with no log or fill-up to date it.
                    state.currentOdometerKm > 0 -> stringResource(R.string.odometer_update_unknown)
                    else -> stringResource(R.string.odometer_never_updated)
                },
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        FormDistanceField(
            value = state.reading,
            onValueChange = viewModel::setReading,
            label = stringResource(R.string.odometer_form_reading),
            fmt = fmt,
            large = true,
            error = if (state.invalid) stringResource(R.string.form_invalid_number) else null,
            supporting = if (state.belowCurrent) {
                stringResource(R.string.form_odometer_below_current, fmt.distance(state.currentOdometerKm))
            } else {
                stringResource(R.string.form_odometer_current, fmt.distance(state.currentOdometerKm))
            },
        )
        FormDateField(date = state.date, onDateChange = viewModel::setDate, label = stringResource(R.string.field_date), fmt = fmt)
        FormTextField(value = state.note, onValueChange = viewModel::setNote, label = stringResource(R.string.field_notes), imeAction = ImeAction.Done)
    }

    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.form_odometer_confirm_title),
            body = stringResource(R.string.form_odometer_confirm_body, fmt.distance(state.readingKm ?: 0), fmt.distance(state.currentOdometerKm)),
            confirmLabel = stringResource(R.string.action_save_anyway),
            onConfirm = {
                confirm = false
                viewModel.save()
            },
            onDismiss = { confirm = false },
        )
    }
}

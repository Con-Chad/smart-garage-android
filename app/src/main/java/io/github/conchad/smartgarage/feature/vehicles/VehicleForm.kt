package io.github.conchad.smartgarage.feature.vehicles

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
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.GlassHintBubble
import io.github.conchad.smartgarage.ui.glass.LocalAuroraTint
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.conchad.smartgarage.core.data.AppSettings
import io.github.conchad.smartgarage.core.data.AuroraChoice
import io.github.conchad.smartgarage.core.data.VehicleLocalPrefs
import io.github.conchad.smartgarage.core.data.WriteResult
import io.github.conchad.smartgarage.ui.glass.AuroraColorSetting
import io.github.conchad.smartgarage.ui.glass.AuroraPresets
import io.github.conchad.smartgarage.ui.glass.AuroraTint
import io.github.conchad.smartgarage.ui.glass.AuroraTintResolver
import io.github.conchad.smartgarage.ui.glass.VehicleTint
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.VehicleInput
import io.github.conchad.smartgarage.core.data.VehiclePatch
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.bodyTypeLabel
import io.github.conchad.smartgarage.ui.components.driveTypeLabel
import io.github.conchad.smartgarage.ui.components.forms.FormCard
import io.github.conchad.smartgarage.ui.components.forms.FormDisclosureCard
import io.github.conchad.smartgarage.ui.components.forms.FormDistanceField
import io.github.conchad.smartgarage.ui.components.forms.CarColorPicker
import io.github.conchad.smartgarage.ui.components.forms.FormMoneyField
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormNumberField
import io.github.conchad.smartgarage.ui.components.forms.FormOptionalDateField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.components.forms.FormAutocompleteField
import io.github.conchad.smartgarage.ui.components.forms.EmphasizedFormFields
import androidx.compose.ui.text.input.KeyboardType
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.components.fuelTypeLabel
import io.github.conchad.smartgarage.ui.navigation.VehicleFormRoute
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

// The web's enum names (prisma/schema.prisma), in its order; "" is the picker's "not set".
val FUEL_TYPES = listOf("", "PETROL", "DIESEL", "ELECTRIC", "HYBRID", "PLUGIN_HYBRID", "LPG", "CNG", "OTHER")
val BODY_TYPES = listOf("", "SEDAN", "HATCHBACK", "WAGON", "COUPE", "CONVERTIBLE", "SUV", "CROSSOVER", "VAN", "PICKUP", "OTHER")
val DRIVE_TYPES = listOf("", "FWD", "RWD", "AWD", "FOUR_WD", "OTHER")

private val VIN_PATTERN = Regex("^[A-HJ-NPR-Z0-9]{0,17}$", RegexOption.IGNORE_CASE)
private val HSN_PATTERN = Regex("^\\d{0,4}$")
private val TSN_PATTERN = Regex("^[A-Z0-9]{0,3}$", RegexOption.IGNORE_CASE)

/**
 * Manual vehicle entry (D-035) and the edit of what the server lets a patch change. A new vehicle needs make,
 * model, year and the odometer; registration and the folded-away specs are optional. Editing covers registration,
 * color, purchase and notes; make, model, year and specs stay on the web (the API has no patch for them).
 */
data class VehicleFormState(
    val editing: Vehicle? = null,
    val make: String = "",
    val model: String = "",
    val year: String = "",
    val odometer: String = "",
    val plate: String = "",
    val vin: String = "",
    val hsn: String = "",
    val tsn: String = "",
    val color: String = "",
    val notes: String = "",
    val purchaseDate: LocalDate? = null,
    val purchasePrice: String = "",
    val fuelType: String = "",
    val bodyType: String = "",
    val driveType: String = "",
    val powerKw: String = "",
    val powerPs: String = "",
    val torqueNm: String = "",
    val displacementCc: String = "",
    val engine: String = "",
    val formatter: Formatter = Formatter(null),
    /** The aurora color (D-A44): Settings' choice, and this vehicle's override as loaded and as edited (null = follow Settings). */
    val appAurora: AuroraChoice = AuroraChoice.MatchVehicle,
    val loadedAurora: AuroraChoice? = null,
    val auroraOverride: AuroraChoice? = null,
    /** The color the picker opens on when "Match vehicle color" is switched off. */
    val auroraColor: Int = AuroraPresets.first(),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val isEdit: Boolean get() = editing != null
    val auroraMatches: Boolean get() = (auroraOverride ?: appAurora) == AuroraChoice.MatchVehicle
    val canEdit: Boolean get() = editing?.access?.canEdit ?: true

    val yearValue: Int? get() = year.trim().toIntOrNull()
    val odometerKm: Int? get() = formatter.parseNumber(odometer)?.let(formatter::kmFromDisplay)
    val purchaseCents: Long? get() = formatter.parseNumber(purchasePrice)?.let(formatter::centsFromDisplay)

    val yearInvalid: Boolean get() = year.isNotBlank() && (yearValue == null || yearValue!! !in 1886..(LocalDate.now().year + 1))
    val odometerInvalid: Boolean get() = odometer.isNotBlank() && (odometerKm == null || odometerKm!! < 0)
    val purchaseInvalid: Boolean get() = purchasePrice.isNotBlank() && (purchaseCents == null || purchaseCents!! < 0)
    val vinInvalid: Boolean get() = !VIN_PATTERN.matches(vin.trim())
    val hsnInvalid: Boolean get() = !HSN_PATTERN.matches(hsn.trim())
    val tsnInvalid: Boolean get() = !TSN_PATTERN.matches(tsn.trim())
    val makeTooLong: Boolean get() = make.length > 80
    val modelTooLong: Boolean get() = model.length > 80
    val plateTooLong: Boolean get() = plate.length > 20
    val colorTooLong: Boolean get() = color.length > 50
    val notesTooLong: Boolean get() = notes.length > 5000
    val engineTooLong: Boolean get() = engine.length > 200
    val powerKwInvalid: Boolean get() = intInvalid(powerKw)
    val powerPsInvalid: Boolean get() = intInvalid(powerPs)
    val torqueInvalid: Boolean get() = intInvalid(torqueNm)
    val displacementInvalid: Boolean get() = intInvalid(displacementCc)
    val specsInvalid: Boolean get() = powerKwInvalid || powerPsInvalid || torqueInvalid || displacementInvalid || engineTooLong

    val anyInvalid: Boolean
        get() = yearInvalid || odometerInvalid || purchaseInvalid || vinInvalid || hsnInvalid || tsnInvalid || makeTooLong || modelTooLong ||
            plateTooLong || colorTooLong || notesTooLong || specsInvalid

    /** What a new vehicle still needs, for the save bar's explanation. */
    val missing: Boolean get() = !isEdit && (make.isBlank() || model.isBlank() || yearValue == null || odometerKm == null)

    val canSave: Boolean get() = canEdit && !missing && !anyInvalid && !saving

    private fun intInvalid(text: String): Boolean = text.isNotBlank() && (formatter.parseNumber(text)?.let { it < 0 || it != Math.floor(it) } ?: true)
    private fun intOrNull(text: String): Int? = formatter.parseNumber(text)?.toInt()

    fun input(): VehicleInput = VehicleInput(
        make = make.trim(),
        model = model.trim(),
        productionYear = yearValue ?: 0,
        currentOdometerKm = odometerKm ?: 0,
        licensePlate = plate.trim().ifEmpty { null },
        vin = vin.trim().uppercase().ifEmpty { null },
        hsn = hsn.trim().ifEmpty { null },
        tsn = tsn.trim().uppercase().ifEmpty { null },
        color = color.trim().ifEmpty { null },
        notes = notes.trim().ifEmpty { null },
        fuelType = fuelType.ifEmpty { null },
        bodyType = bodyType.ifEmpty { null },
        driveType = driveType.ifEmpty { null },
        powerKw = intOrNull(powerKw),
        powerPs = intOrNull(powerPs),
        torqueNm = intOrNull(torqueNm),
        displacementCc = intOrNull(displacementCc),
        engineDescription = engine.trim().ifEmpty { null },
    )

    fun patch(): VehiclePatch = VehiclePatch(
        licensePlate = plate.trim().ifEmpty { null },
        vin = vin.trim().uppercase().ifEmpty { null },
        hsn = hsn.trim().ifEmpty { null },
        tsn = tsn.trim().uppercase().ifEmpty { null },
        color = color.trim().ifEmpty { null },
        notes = notes.trim().ifEmpty { null },
        purchaseDate = purchaseDate?.let { formatter.instantFor(it).toString() },
        purchasePriceCents = purchaseCents,
        purchaseCurrency = purchaseCents?.let { editing?.purchaseCurrency ?: formatter.currencyCode },
        clear = buildSet {
            if (plate.isBlank()) add("licensePlate")
            if (vin.isBlank()) add("vin")
            if (hsn.isBlank()) add("hsn")
            if (tsn.isBlank()) add("tsn")
            if (color.isBlank()) add("color")
            if (notes.isBlank()) add("notes")
            if (purchaseDate == null) add("purchaseDate")
            if (purchaseCents == null) {
                add("purchasePriceCents")
                add("purchaseCurrency")
            }
        },
    )
}

@HiltViewModel
class VehicleFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
    private val localPrefs: VehicleLocalPrefs,
    private val settings: AppSettings,
) : WritingViewModel(control, writer) {
    /** What an untouched edit would patch: when only the aurora changed, nothing goes to the server (D-A44). */
    private var loadedPatch: VehiclePatch? = null
    private val route = savedStateHandle.toRoute<VehicleFormRoute>()
    private val form = MutableStateFlow(VehicleFormState())
    val state: StateFlow<VehicleFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicle = route.vehicleId?.let { id -> garage.vehicles.first().firstOrNull { it.id == id } }
            val appAurora = settings.aurora.first()
            val override = vehicle?.let { localPrefs.auroraOverrides.first()[it.id] }
            val startColor = ((override ?: appAurora) as? AuroraChoice.Custom)?.argb
                ?: VehicleTint.argb(vehicle?.color) ?: AuroraPresets.first()
            form.value = VehicleFormState(
                editing = vehicle,
                make = vehicle?.make.orEmpty(),
                model = vehicle?.model.orEmpty(),
                year = (vehicle?.productionYear ?: vehicle?.year)?.toString().orEmpty(),
                odometer = vehicle?.let { fmt.distanceInput(it.currentOdometerKm) }.orEmpty(),
                plate = vehicle?.licensePlate.orEmpty(),
                vin = vehicle?.vin.orEmpty(),
                hsn = vehicle?.hsn.orEmpty(),
                tsn = vehicle?.tsn.orEmpty(),
                color = vehicle?.color.orEmpty(),
                notes = vehicle?.notes.orEmpty(),
                purchaseDate = vehicle?.purchaseDate?.toInstantOrNull()?.let(fmt::localDate),
                purchasePrice = vehicle?.purchasePriceCents?.let(fmt::moneyInput).orEmpty(),
                formatter = fmt,
                appAurora = appAurora,
                loadedAurora = override,
                auroraOverride = override,
                auroraColor = startColor,
                loaded = true,
            )
            if (vehicle != null) loadedPatch = form.value.patch()
        }
    }

    fun setMake(text: String) = form.update { it.copy(make = text, dirty = true) }
    fun setModel(text: String) = form.update { it.copy(model = text, dirty = true) }
    fun setYear(text: String) = form.update { it.copy(year = text, dirty = true) }
    fun setOdometer(text: String) = form.update { it.copy(odometer = text, dirty = true) }
    fun setPlate(text: String) = form.update { it.copy(plate = text, dirty = true) }
    fun setVin(text: String) = form.update { it.copy(vin = text, dirty = true) }
    fun setHsn(text: String) = form.update { it.copy(hsn = text, dirty = true) }
    fun setTsn(text: String) = form.update { it.copy(tsn = text, dirty = true) }
    fun setColor(text: String) = form.update { it.copy(color = text, dirty = true) }
    fun setNotes(text: String) = form.update { it.copy(notes = text, dirty = true) }
    fun setPurchaseDate(date: LocalDate?) = form.update { it.copy(purchaseDate = date, dirty = true) }
    fun setPurchasePrice(text: String) = form.update { it.copy(purchasePrice = text, dirty = true) }
    fun setFuelType(value: String) = form.update { it.copy(fuelType = value, dirty = true) }
    fun setBodyType(value: String) = form.update { it.copy(bodyType = value, dirty = true) }
    fun setDriveType(value: String) = form.update { it.copy(driveType = value, dirty = true) }
    fun setPowerKw(text: String) = form.update { it.copy(powerKw = text, dirty = true) }
    fun setPowerPs(text: String) = form.update { it.copy(powerPs = text, dirty = true) }
    fun setTorque(text: String) = form.update { it.copy(torqueNm = text, dirty = true) }
    fun setDisplacement(text: String) = form.update { it.copy(displacementCc = text, dirty = true) }
    fun setEngine(text: String) = form.update { it.copy(engine = text, dirty = true) }

    /** On follows the car's color: no override when Settings matches too, an explicit "match" when Settings picked one. */
    fun setAuroraMatch(on: Boolean) = form.update {
        val override = when {
            !on -> AuroraChoice.Custom(it.auroraColor)
            it.appAurora == AuroraChoice.MatchVehicle -> null
            else -> AuroraChoice.MatchVehicle
        }
        it.copy(auroraOverride = override, dirty = true)
    }

    fun setAuroraColor(argb: Int) = form.update { it.copy(auroraOverride = AuroraChoice.Custom(argb), auroraColor = argb, dirty = true) }

    /** Drops this vehicle's override so it follows Settings again. */
    fun useAppAurora() = form.update {
        val color = (it.appAurora as? AuroraChoice.Custom)?.argb ?: it.auroraColor
        it.copy(auroraOverride = null, auroraColor = color, dirty = true)
    }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val editing = s.editing
            val auroraChanged = s.auroraOverride != s.loadedAurora
            if (editing != null && auroraChanged && s.patch() == loadedPatch) {
                // Only the device-local aurora color changed: nothing to send.
                localPrefs.setAuroraOverride(editing.id, s.auroraOverride)
                form.update { it.copy(saving = false, done = true, dirty = false) }
                return@launch
            }
            val result = editing?.let { writer.updateVehicle(it.id, s.patch()) } ?: writer.addVehicle(s.input())
            val queued = report(result)
            // A new vehicle's id is a temp id until the server answers; see VehicleLocalPrefs.moveVehicle (D-A44).
            if (queued && auroraChanged && result is WriteResult.Queued) localPrefs.setAuroraOverride(result.id, s.auroraOverride)
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }
}

/**
 * Add (manual entry) or edit a vehicle: identity, the vehicle ID details, then the specs folded away under "Specs".
 *
 * D-A69: on Home's glass. Each group is its own glass card on the aurora ([FormCard]: icon badge, metallic title,
 * plate glow), the fields are the rounder frosted glass fields of the emphasized form, make / model / year drop
 * their suggestion list attached to the field, and the HSN and TSN fields explain themselves in a frosted bubble
 * while focused ([GlassHintBubble]). A new vehicle with no colour picked yet keeps the aurora it was opened over
 * instead of falling back to the theme's primary.
 */
@Composable
fun VehicleFormScreen(onClose: () -> Unit, viewModel: VehicleFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val snackbar = remember { SnackbarHostState() }
    var specsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.done) { if (state.done) onClose() }
    // The shell lets this route tint the aurora, so the color being picked previews behind the form (D-A44). With
    // nothing to preview yet, the aurora the form was opened over (Home's car), not the theme's grey primary (D-A69).
    // Saved as ARGB so a rotation or theme change, which recreates the shell's tint holder, keeps it.
    val holder = LocalAuroraTint.current
    val arrivedOver = rememberSaveable { mutableStateOf(holder.effective?.toArgb()) }.value
    val preview = AuroraTintResolver.resolve(state.auroraOverride, state.appAurora, state.color) ?: arrivedOver
    AuroraTint(preview?.let { Color(it) } ?: MaterialTheme.colorScheme.primary)
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        !state.canEdit -> stringResource(R.string.view_only)
        state.missing -> stringResource(R.string.vehicle_form_needs_basics)
        state.anyInvalid -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    // D-A62: this form's fields get the stronger outline and 2 sp larger text; D-A69 the frosted glass fill too.
    EmphasizedFormFields {
        FormScreen(
            title = stringResource(if (state.isEdit) R.string.vehicle_form_edit_title else R.string.vehicle_form_title),
            onClose = onClose,
            saveLabel = stringResource(R.string.action_save),
            canSave = state.canSave,
            saving = state.saving,
            onSave = viewModel::save,
            dirty = state.dirty && !state.done,
            validation = validation,
            snackbarHost = snackbar,
            grouped = true,
        ) {
            if (!state.loaded) {
                FormCard(title = null) { FormSkeleton() }
                return@FormScreen
            }
            state.editing?.let { vehicle ->
                FormCard(title = vehicle.displayName, icon = Icons.Outlined.DirectionsCar) {
                    if (!vehicle.access.canEdit) ViewOnlyNote(vehicle.owner?.name)
                    Text(
                        stringResource(R.string.vehicle_form_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textSecondary,
                    )
                }
            }
            if (!state.isEdit) {
                FormCard(title = stringResource(R.string.vehicle_form_section_vehicle), icon = Icons.Outlined.DirectionsCar) {
                    // D-A62: make, model and year suggest from the built-in catalog as the user types, like the insurer
                    // field; the arrow lists them all (the models of the chosen make, the years). All three stay free text.
                    FormAutocompleteField(
                        value = state.make,
                        onValueChange = { make ->
                            viewModel.setMake(make)
                            // A known make that doesn't list the model typed for the old one: start the model over.
                            if (!VehicleCatalog.keepsModel(make, state.model)) viewModel.setModel("")
                        },
                        label = stringResource(R.string.vehicle_form_make),
                        suggest = VehicleCatalog::suggestMakes,
                        browse = VehicleCatalog.makes,
                        capitalizeWords = true,
                        error = if (state.makeTooLong) pluralStringResource(R.plurals.form_too_long, 80, 80) else null,
                    )
                    val make = state.make
                    val models = remember(make) { VehicleCatalog.modelsFor(make) }
                    FormAutocompleteField(
                        value = state.model,
                        onValueChange = viewModel::setModel,
                        label = stringResource(R.string.vehicle_form_model),
                        suggest = remember(make) { { query: String -> VehicleCatalog.suggestModels(make, query) } },
                        browse = models,
                        capitalizeWords = true,
                        error = if (state.modelTooLong) pluralStringResource(R.plurals.form_too_long, 80, 80) else null,
                    )
                    // Full width (D-A62): the larger label and the dropdown arrow don't fit half a row, nor does the list.
                    val years = remember { VehicleCatalog.years() }
                    FormAutocompleteField(
                        value = state.year,
                        onValueChange = viewModel::setYear,
                        label = stringResource(R.string.vehicle_form_year),
                        suggest = { query -> VehicleCatalog.suggestYears(query) },
                        browse = years,
                        keyboardType = KeyboardType.Number,
                        filter = { text -> text.filter { it.isDigit() }.take(4) },
                        error = if (state.yearInvalid) stringResource(R.string.vehicle_form_year_invalid) else null,
                    )
                    FormDistanceField(
                        value = state.odometer,
                        onValueChange = viewModel::setOdometer,
                        label = stringResource(R.string.field_odometer),
                        fmt = fmt,
                        error = if (state.odometerInvalid) stringResource(R.string.form_invalid_number) else null,
                    )
                }
            }

            FormCard(title = stringResource(R.string.vehicle_form_registration), icon = Icons.Outlined.Badge) {
                FormTextField(
                    value = state.plate,
                    onValueChange = viewModel::setPlate,
                    label = stringResource(R.string.field_plate),
                    error = if (state.plateTooLong) pluralStringResource(R.plurals.form_too_long, 20, 20) else null,
                )
                FormTextField(
                    value = state.vin,
                    onValueChange = viewModel::setVin,
                    label = stringResource(R.string.field_vin),
                    error = if (state.vinInvalid) stringResource(R.string.vehicle_form_vin_invalid) else null,
                )
                // D-A69: each code explains itself and where to find it in a frosted bubble while its field is focused.
                var hsnFocused by remember { mutableStateOf(false) }
                var tsnFocused by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlassHintBubble(
                        visible = hsnFocused,
                        title = stringResource(R.string.vehicle_form_hsn_hint_title),
                        text = stringResource(R.string.vehicle_form_hsn_hint),
                        modifier = Modifier.weight(1f),
                    ) {
                        FormTextField(
                            value = state.hsn,
                            onValueChange = viewModel::setHsn,
                            label = stringResource(R.string.vehicle_form_hsn),
                            modifier = Modifier.onFocusChanged { hsnFocused = it.isFocused },
                            error = if (state.hsnInvalid) stringResource(R.string.vehicle_form_hsn_invalid) else null,
                        )
                    }
                    GlassHintBubble(
                        visible = tsnFocused,
                        title = stringResource(R.string.vehicle_form_tsn_hint_title),
                        text = stringResource(R.string.vehicle_form_tsn_hint),
                        modifier = Modifier.weight(1f),
                    ) {
                        FormTextField(
                            value = state.tsn,
                            onValueChange = viewModel::setTsn,
                            label = stringResource(R.string.vehicle_form_tsn),
                            modifier = Modifier.onFocusChanged { tsnFocused = it.isFocused },
                            error = if (state.tsnInvalid) stringResource(R.string.vehicle_form_tsn_invalid) else null,
                        )
                    }
                }
            }

            FormCard(title = stringResource(R.string.field_color), icon = Icons.Outlined.Palette) {
                // D-A52: one paint per car, so a single-select swatch grid instead of free text (same stored string).
                CarColorPicker(
                    value = state.color,
                    onValueChange = viewModel::setColor,
                    label = stringResource(R.string.field_color),
                )
            }

            if (state.isEdit) {
                FormCard(title = stringResource(R.string.field_purchased), icon = Icons.Outlined.ShoppingBag) {
                    FormOptionalDateField(
                        date = state.purchaseDate,
                        onDateChange = viewModel::setPurchaseDate,
                        label = stringResource(R.string.field_purchased),
                        fmt = fmt,
                    )
                    FormMoneyField(
                        value = state.purchasePrice,
                        onValueChange = viewModel::setPurchasePrice,
                        label = stringResource(R.string.field_purchase_price),
                        fmt = fmt,
                        error = if (state.purchaseInvalid) stringResource(R.string.form_invalid_number) else null,
                    )
                }
            }

            FormCard(
                title = stringResource(R.string.aurora_title),
                icon = Icons.Outlined.AutoAwesome,
                action = if (state.auroraOverride != null) {
                    {
                        TextButton(onClick = viewModel::useAppAurora, colors = glassTextButtonColors()) {
                            Text(stringResource(R.string.aurora_use_app_setting))
                        }
                    }
                } else {
                    null
                },
            ) {
                AuroraColorSetting(
                    match = state.auroraMatches,
                    onMatchChange = viewModel::setAuroraMatch,
                    color = Color(((state.auroraOverride ?: state.appAurora) as? AuroraChoice.Custom)?.argb ?: state.auroraColor),
                    onColorChange = { viewModel.setAuroraColor(it.toArgb()) },
                    matchDescription = stringResource(
                        when {
                            state.auroraOverride == null && state.appAurora != AuroraChoice.MatchVehicle -> R.string.vehicle_aurora_follows_app
                            state.auroraMatches -> R.string.vehicle_aurora_match_desc
                            else -> R.string.vehicle_aurora_custom_desc
                        },
                    ),
                    horizontalPadding = 0.dp,
                )
            }

            FormCard(title = null) {
                FormNotesField(
                    value = state.notes,
                    onValueChange = viewModel::setNotes,
                    error = if (state.notesTooLong) pluralStringResource(R.plurals.form_too_long, 5000, 5000) else null,
                )
            }

            if (!state.isEdit) {
                val fuelLabels = FUEL_TYPES.associateWith { if (it.isEmpty()) stringResource(R.string.form_not_set) else fuelTypeLabel(it) }
                val bodyLabels = BODY_TYPES.associateWith { if (it.isEmpty()) stringResource(R.string.form_not_set) else bodyTypeLabel(it) }
                val driveLabels = DRIVE_TYPES.associateWith { if (it.isEmpty()) stringResource(R.string.form_not_set) else driveTypeLabel(it) }
                FormDisclosureCard(
                    title = stringResource(R.string.vehicle_form_specs),
                    summary = stringResource(R.string.vehicle_form_specs_hint),
                    icon = Icons.Outlined.Tune,
                    expanded = specsOpen,
                    onToggle = { specsOpen = !specsOpen },
                ) {
                    FormPicker(
                        label = stringResource(R.string.spec_fuel),
                        options = FUEL_TYPES,
                        selected = state.fuelType,
                        optionLabel = { fuelLabels[it] ?: it },
                        onSelect = viewModel::setFuelType,
                    )
                    FormPicker(
                        label = stringResource(R.string.spec_body),
                        options = BODY_TYPES,
                        selected = state.bodyType,
                        optionLabel = { bodyLabels[it] ?: it },
                        onSelect = viewModel::setBodyType,
                    )
                    FormPicker(
                        label = stringResource(R.string.spec_drive),
                        options = DRIVE_TYPES,
                        selected = state.driveType,
                        optionLabel = { driveLabels[it] ?: it },
                        onSelect = viewModel::setDriveType,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormNumberField(
                            value = state.powerKw,
                            onValueChange = viewModel::setPowerKw,
                            label = stringResource(R.string.spec_power),
                            unit = "kW",
                            modifier = Modifier.weight(1f),
                            integer = true,
                            error = if (state.powerKwInvalid) stringResource(R.string.form_invalid_number) else null,
                        )
                        FormNumberField(
                            value = state.powerPs,
                            onValueChange = viewModel::setPowerPs,
                            label = stringResource(R.string.spec_power),
                            unit = stringResource(R.string.unit_ps),
                            modifier = Modifier.weight(1f),
                            integer = true,
                            error = if (state.powerPsInvalid) stringResource(R.string.form_invalid_number) else null,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FormNumberField(
                            value = state.torqueNm,
                            onValueChange = viewModel::setTorque,
                            label = stringResource(R.string.spec_torque),
                            unit = "Nm",
                            modifier = Modifier.weight(1f),
                            integer = true,
                            error = if (state.torqueInvalid) stringResource(R.string.form_invalid_number) else null,
                        )
                        FormNumberField(
                            value = state.displacementCc,
                            onValueChange = viewModel::setDisplacement,
                            label = stringResource(R.string.spec_displacement),
                            unit = "cm³",
                            modifier = Modifier.weight(1f),
                            integer = true,
                            error = if (state.displacementInvalid) stringResource(R.string.form_invalid_number) else null,
                        )
                    }
                    FormTextField(
                        value = state.engine,
                        onValueChange = viewModel::setEngine,
                        label = stringResource(R.string.spec_engine),
                        error = if (state.engineTooLong) pluralStringResource(R.plurals.form_too_long, 200, 200) else null,
                    )
                }
            }
        }
    }
}

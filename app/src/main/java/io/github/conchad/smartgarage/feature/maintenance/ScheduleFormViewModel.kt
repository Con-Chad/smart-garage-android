package io.github.conchad.smartgarage.feature.maintenance

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.ScheduleInput
import io.github.conchad.smartgarage.core.data.SchedulePatch
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.MaintenanceItem
import io.github.conchad.smartgarage.core.model.MaintenanceTemplate
import io.github.conchad.smartgarage.core.model.Schedule
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.navigation.ScheduleDefaultsRoute
import io.github.conchad.smartgarage.ui.navigation.ScheduleFormRoute
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
import java.util.Locale
import javax.inject.Inject

/** The web's `MaintenanceCategory` names, in its order. */
val MAINTENANCE_CATEGORIES = listOf("ENGINE", "FILTERS", "FLUIDS", "BRAKES", "TIRES", "INSPECTION", "ADDITIVE", "OTHER")

/** A template to base a new schedule on; `template == null` is the web's "Custom service". */
data class TemplateOption(val template: MaintenanceTemplate?, val name: String)

/**
 * Adding or editing a service schedule (the web's "Add service" / "Edit intervals"). A new schedule starts from a
 * template or a custom name; intervals are typed in the display unit and months. Editing also exposes the baseline
 * the web's schedule page adjusts (last performed, odometer then), used until real records exist.
 */
data class ScheduleFormState(
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val templates: List<TemplateOption> = emptyList(),
    val templateId: String? = null,
    val editingId: String? = null,
    val name: String = "",
    val category: String = "OTHER",
    val intervalDistance: String = "",
    val intervalMonths: String = "",
    val cost: String = "",
    val notes: String = "",
    val lastPerformed: LocalDate? = null,
    val lastOdometer: String = "",
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
    val canEdit: Boolean get() = vehicle?.access?.canEdit == true
    val editing: Boolean get() = editingId != null
    val template: TemplateOption? get() = templates.firstOrNull { it.template?.id == templateId }
    val custom: Boolean get() = templateId == null

    val intervalKm: Int? get() = formatter.parseNumber(intervalDistance)?.let(formatter::kmFromDisplay)
    val months: Int? get() = formatter.parseNumber(intervalMonths)?.toInt()
    val costCents: Long? get() = formatter.parseNumber(cost)?.let(formatter::centsFromDisplay)
    val lastOdometerKm: Int? get() = formatter.parseNumber(lastOdometer)?.let(formatter::kmFromDisplay)

    val intervalKmInvalid: Boolean get() = intervalDistance.isNotBlank() && (intervalKm == null || intervalKm!! < 0 || intervalKm!! > 500_000)
    val monthsInvalid: Boolean get() = intervalMonths.isNotBlank() && (months == null || months!! < 0 || months!! > 120)
    val costInvalid: Boolean get() = cost.isNotBlank() && (costCents == null || costCents!! < 0)
    val lastOdometerInvalid: Boolean get() = lastOdometer.isNotBlank() && (lastOdometerKm == null || lastOdometerKm!! < 0)
    val nameTooLong: Boolean get() = name.length > 120
    val notesTooLong: Boolean get() = notes.length > 2000
    val needsName: Boolean get() = custom && name.isBlank()
    val anyInvalid: Boolean get() = intervalKmInvalid || monthsInvalid || costInvalid || lastOdometerInvalid || nameTooLong || notesTooLong

    val canSave: Boolean get() = canEdit && vehicleId != null && !needsName && !anyInvalid && !saving

    fun input(): ScheduleInput = ScheduleInput(
        templateId = templateId,
        customName = name.trim().ifEmpty { null },
        category = category.takeIf { custom },
        intervalKm = intervalKm,
        intervalMonths = months,
        estimatedCostCents = costCents,
        currency = formatter.currencyCode,
        notes = notes.trim().ifEmpty { null },
    )

    /** The schema takes `customName` and `notes` as plain strings, so a blank one is sent as "" rather than cleared. */
    fun patch(now: Instant = Instant.now()): SchedulePatch = SchedulePatch(
        customName = name.trim(),
        category = category,
        intervalKm = intervalKm,
        intervalMonths = months,
        estimatedCostCents = costCents,
        notes = notes.trim(),
        lastPerformedAt = lastPerformed?.let { formatter.instantFor(it, now).toString() },
        lastOdometerKm = lastOdometerKm,
        clear = buildSet {
            if (intervalKm == null) add("intervalKm")
            if (months == null) add("intervalMonths")
            if (costCents == null) add("estimatedCostCents")
            if (lastPerformed == null) add("lastPerformedAt")
            if (lastOdometerKm == null) add("lastOdometerKm")
        },
    )
}

@HiltViewModel
class ScheduleFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<ScheduleFormRoute>()
    private val german = Locale.getDefault().language == "de"
    private val form = MutableStateFlow(ScheduleFormState())
    val state: StateFlow<ScheduleFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val vehicles = garage.vehicles.first().filter { it.access.canEdit }.sortedWith(GarageMath.vehicleOrder)
            val templates = garage.templates.first()
                .map { TemplateOption(it, if (german) it.nameDe else it.nameEn) }
                .sortedBy { it.name.lowercase() }
            val schedule: Schedule? = route.scheduleId?.let { id -> garage.schedules.first().firstOrNull { it.id == id } }
            val vehicleId = (schedule?.vehicleId ?: route.vehicleId)?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id
            form.value = ScheduleFormState(
                vehicles = vehicles,
                vehicleId = vehicleId,
                templates = listOf(TemplateOption(null, "")) + templates,
                templateId = schedule?.templateId?.takeIf { id -> templates.any { it.template?.id == id } },
                editingId = schedule?.id,
                name = schedule?.customName.orEmpty(),
                category = schedule?.category ?: "OTHER",
                intervalDistance = schedule?.intervalKm?.let(fmt::distanceInput).orEmpty(),
                intervalMonths = schedule?.intervalMonths?.toString().orEmpty(),
                cost = schedule?.estimatedCostCents?.let(fmt::moneyInput).orEmpty(),
                notes = schedule?.notes.orEmpty(),
                lastPerformed = schedule?.lastPerformedAt?.toInstantOrNull()?.let(fmt::localDate),
                lastOdometer = schedule?.lastOdometerKm?.let(fmt::distanceInput).orEmpty(),
                formatter = fmt,
                loaded = true,
            )
        }
    }

    fun selectVehicle(id: String) = form.update { if (it.editing) it else it.copy(vehicleId = id, dirty = true) }

    /** A template brings its category along; "Custom service" keeps whatever was picked. */
    fun selectTemplate(id: String?) = form.update { s ->
        if (s.editing) return@update s
        val template = s.templates.firstOrNull { it.template?.id == id }?.template
        s.copy(templateId = template?.id, category = template?.category ?: s.category, dirty = true)
    }

    fun setName(text: String) = form.update { it.copy(name = text, dirty = true) }
    fun setCategory(value: String) = form.update { it.copy(category = value, dirty = true) }
    fun setIntervalDistance(text: String) = form.update { it.copy(intervalDistance = text, dirty = true) }
    fun setIntervalMonths(text: String) = form.update { it.copy(intervalMonths = text, dirty = true) }
    fun setCost(text: String) = form.update { it.copy(cost = text, dirty = true) }
    fun setNotes(text: String) = form.update { it.copy(notes = text, dirty = true) }
    fun setLastPerformed(date: LocalDate?) = form.update { it.copy(lastPerformed = date, dirty = true) }
    fun setLastOdometer(text: String) = form.update { it.copy(lastOdometer = text, dirty = true) }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        val vehicleId = s.vehicleId ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = s.editingId?.let { writer.updateSchedule(it, s.patch()) } ?: writer.addSchedule(vehicleId, s.input())
            val queued = report(result)
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }
}

/** The schedule's default parts and fluids (the web's "Manage defaults"): the same rows as a service record. */
data class ScheduleDefaultsState(
    val scheduleId: String? = null,
    val name: String = "",
    val vehicle: Vehicle? = null,
    val items: List<ItemDraft> = emptyList(),
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val canEdit: Boolean get() = vehicle?.access?.canEdit == true
    val itemsInvalid: Boolean get() = items.any { it.quantityInvalid(formatter) || it.costInvalid(formatter) }
    val canSave: Boolean get() = canEdit && scheduleId != null && !itemsInvalid && !saving

    fun itemsToSend(): List<MaintenanceItem> = items.filterNot { it.isBlank(formatter) }.map { it.toItem(formatter) }
}

@HiltViewModel
class ScheduleDefaultsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<ScheduleDefaultsRoute>()
    private val german = Locale.getDefault().language == "de"
    private val form = MutableStateFlow(ScheduleDefaultsState())
    val state: StateFlow<ScheduleDefaultsState> = form.asStateFlow()
    private var nextKey = 1L

    init {
        viewModelScope.launch {
            val fmt = garage.formatter.first()
            val schedule = garage.schedules.first().firstOrNull { it.id == route.scheduleId }
            val vehicle = schedule?.let { s -> garage.vehicles.first().firstOrNull { it.id == s.vehicleId } }
            val template = schedule?.templateId?.let { id -> garage.templates.first().firstOrNull { it.id == id } }
            form.value = ScheduleDefaultsState(
                scheduleId = schedule?.id,
                name = schedule?.displayName(template, german).orEmpty(),
                vehicle = vehicle,
                items = schedule?.itemDefaults.orEmpty().map { ItemDraft.of(it, nextKey++, fmt) },
                formatter = fmt,
                loaded = true,
            )
        }
    }

    fun addItem() = form.update { s -> s.copy(items = s.items + ItemDraft(nextKey++, MaintenanceItem(), "", "", ""), dirty = true) }
    fun removeItem(key: Long) = form.update { s -> s.copy(items = s.items.filterNot { it.key == key }, dirty = true) }
    fun setItemName(key: Long, text: String) = updateItem(key) { it.copy(name = text) }
    fun setItemQuantity(key: Long, text: String) = updateItem(key) { it.copy(quantity = text) }
    fun setItemCost(key: Long, text: String) = updateItem(key) { it.copy(cost = text) }

    private fun updateItem(key: Long, change: (ItemDraft) -> ItemDraft) = form.update { s ->
        s.copy(items = s.items.map { if (it.key == key) change(it) else it }, dirty = true)
    }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        val id = s.scheduleId ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val queued = report(writer.setScheduleItemDefaults(id, s.itemsToSend()))
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }
}

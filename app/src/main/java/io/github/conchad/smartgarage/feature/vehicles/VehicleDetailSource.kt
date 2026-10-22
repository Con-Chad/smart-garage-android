package io.github.conchad.smartgarage.feature.vehicles

import io.github.conchad.smartgarage.core.data.AppSettings
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.CustomField
import io.github.conchad.smartgarage.core.model.CustomFieldValue
import io.github.conchad.smartgarage.core.model.Expense
import io.github.conchad.smartgarage.core.model.FuelEntry
import io.github.conchad.smartgarage.core.model.Inspection
import io.github.conchad.smartgarage.core.model.InsurancePolicy
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.MaintenanceTemplate
import io.github.conchad.smartgarage.core.model.Modification
import io.github.conchad.smartgarage.core.model.Schedule
import io.github.conchad.smartgarage.core.model.Vehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the vehicle page is built from, for all vehicles of the active profile (D-A35). */
data class VehicleDetailInputs(
    val vehicles: List<Vehicle>,
    val schedules: List<Schedule>,
    val templates: List<MaintenanceTemplate>,
    val records: List<MaintenanceRecord>,
    val fuel: List<FuelEntry>,
    val expenses: List<Expense>,
    val inspections: List<Inspection>,
    val policies: List<InsurancePolicy>,
    val customFields: List<CustomField>,
    val customFieldValues: List<CustomFieldValue>,
    val modifications: List<Modification>,
    val formatter: Formatter,
    /** Settings → Fuel → Track fuel economy. */
    val trackFuelEconomy: Boolean,
    /** The signed-in account's name, the owner of own vehicles. */
    val accountName: String?,
)

/**
 * The vehicle page's inputs, combined once and shared (D-A35), so the page can open on its content in its very first
 * frame: [latest] is the last combination while anything is observing, read synchronously when the page's
 * ViewModel is created. Home and the Vehicles tab observe [keepWarm] while they are on screen, so a tap on a card
 * finds everything (records, expenses, custom fields and modifications included) already decoded in memory.
 * Like the resources it combines, it stops five seconds after its last observer leaves and drops its replay then,
 * so a page opened later never starts from stale data; it waits for the cache instead (spinner path).
 */
@Singleton
class VehicleDetailSource @Inject constructor(
    garage: GarageRepository,
    settings: AppSettings,
    profiles: ProfileStore,
    scope: CoroutineScope,
) {
    private val plan = combine(garage.vehicles, garage.schedules, garage.templates, ::Triple)
    private val history = combine(garage.records, garage.fuelEntries, garage.expenses, ::Triple)
    private val papers = combine(garage.inspections, garage.insurancePolicies, ::Pair)
    private val extras = combine(garage.customFields, garage.customFieldValues, garage.modifications, ::Triple)
    private val device = combine(garage.formatter, settings.fuel, profiles.state) { fmt, fuelSettings, p ->
        Triple(fmt, fuelSettings.trackFuelEconomy, p.active?.account?.greetingName)
    }

    val inputs: SharedFlow<VehicleDetailInputs> = combine(plan, history, papers, extras, device) { p, h, pa, e, d ->
        VehicleDetailInputs(
            vehicles = p.first, schedules = p.second, templates = p.third,
            records = h.first, fuel = h.second, expenses = h.third,
            inspections = pa.first, policies = pa.second,
            customFields = e.first, customFieldValues = e.second, modifications = e.third,
            formatter = d.first, trackFuelEconomy = d.second, accountName = d.third,
        )
    }.shareIn(
        scope,
        SharingStarted.WhileSubscribed(stopTimeoutMillis = GarageRepository.STOP_TIMEOUT_MS, replayExpirationMillis = 0),
        replay = 1,
    )

    /** The current inputs if something is observing them (and they have arrived), without suspending; else null. */
    val latest: VehicleDetailInputs? get() = inputs.replayCache.firstOrNull()

    /**
     * Emits once, at once, and keeps [inputs] live while collected. Screens that open the vehicle page fold it into
     * their state so the page's data is ready before the tap; it never delays or re-emits their own state.
     */
    val keepWarm: Flow<Unit> = inputs.map { }.onStart { emit(Unit) }.distinctUntilChanged()
}

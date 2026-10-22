package io.github.conchad.smartgarage.feature.fuel

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.ChartMath
import io.github.conchad.smartgarage.core.model.ChartPoint
import io.github.conchad.smartgarage.core.model.FuelEntry
import io.github.conchad.smartgarage.core.model.FuelSegment
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/** This year's totals for the summary card. */
data class FuelSummary(
    val averageConsumption: Double?,
    val spentCents: Long,
    val currency: String?,
    val liters: Double,
    val fillUps: Int,
)

/**
 * The charts above the log (D-A23), for one vehicle: consumption per fill-up (stored L/100 km) and the price per
 * liter (cents), oldest first. Null while more than one vehicle is in view: their lines would mean nothing together.
 */
data class FuelTrends(val consumption: List<ChartPoint>, val price: List<ChartPoint>)

data class FuelMonth(val month: YearMonth, val entries: List<FuelEntry>) {
    val totalCents: Long get() = entries.sumOf { it.totalCostCents }
}

data class FuelUiState(
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: String? = null,
    val months: List<FuelMonth> = emptyList(),
    val segments: Map<String, FuelSegment> = emptyMap(),
    val summary: FuelSummary? = null,
    val trends: FuelTrends? = null,
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
) {
    val vehicleById: Map<String, Vehicle> = vehicles.associateBy { it.id }

    /** Something to log a fill-up on: the FAB hides otherwise. */
    val canAdd: Boolean get() = vehicles.any { it.access.canEdit }

    fun entry(id: String): FuelEntry? = months.firstNotNullOfOrNull { month -> month.entries.firstOrNull { it.id == id } }
}

@HiltViewModel
class FuelViewModel @Inject constructor(garage: GarageRepository, control: SyncControl, writer: GarageWriter) : WritingViewModel(control, writer) {
    private val selected = MutableStateFlow<String?>(null)

    val state: StateFlow<FuelUiState> = combine(garage.vehicles, garage.fuelEntries, garage.formatter, selected) { allVehicles, all, fmt, vehicleId ->
        val vehicles = allVehicles.sortedWith(GarageMath.vehicleOrder)
        val filter = vehicleId?.takeIf { id -> vehicles.any { it.id == id } }
        val ids = vehicles.mapTo(HashSet()) { it.id }
        val entries = all
            .filter { it.vehicleId in ids && (filter == null || it.vehicleId == filter) }
            .sortedByDescending { it.filledAt.toInstantOrNull() ?: Instant.MIN }
        val zone = fmt.zone
        val yearStart = LocalDate.now(zone).withDayOfYear(1).atStartOfDay(zone).toInstant()
        val thisYear = entries.filter { it.filledAt.toInstantOrNull()?.isBefore(yearStart) == false }
        FuelUiState(
            vehicles = vehicles,
            selectedVehicleId = filter,
            months = entries
                .groupBy { entry -> entry.filledAt.toInstantOrNull()?.atZone(zone)?.let(YearMonth::from) ?: YearMonth.of(1970, 1) }
                .map { (month, list) -> FuelMonth(month, list) },
            // Segments need each vehicle's full history, so they're computed before any date filtering.
            segments = GarageMath.fuelSegments(entries),
            summary = FuelSummary(
                averageConsumption = GarageMath.averageConsumption(entries),
                spentCents = thisYear.sumOf { it.totalCostCents },
                currency = thisYear.firstOrNull()?.currency,
                liters = thisYear.sumOf { it.liters ?: 0.0 },
                fillUps = thisYear.size,
            ),
            trends = if (filter != null || vehicles.size == 1) FuelTrends(ChartMath.consumptionSeries(entries), ChartMath.priceSeries(entries)) else null,
            formatter = fmt,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FuelUiState())

    fun selectVehicle(id: String?) {
        selected.value = id
    }

    fun deleteFuelEntry(id: String) = perform { deleteFuelEntry(id) }
}

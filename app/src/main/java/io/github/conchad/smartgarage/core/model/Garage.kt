package io.github.conchad.smartgarage.core.model

import java.time.Instant

/** A schedule ready to show: resolved name, its vehicle, and how far away it is. */
data class DueItem(
    val schedule: Schedule,
    val vehicle: Vehicle,
    val name: String,
    /** Distance left until due; negative when overdue by distance. Null without a mileage interval. */
    val kmRemaining: Int?,
    val dueAt: Instant?,
) {
    val status: DueStatus get() = schedule.status
}

data class VehicleSummary(val vehicle: Vehicle, val overdue: Int, val dueSoon: Int)

data class VehicleDetail(
    val vehicle: Vehicle,
    val summary: VehicleSummary,
    val due: List<DueItem>,
    val records: List<MaintenanceRecord>,
    val fuel: List<FuelEntry>,
    val segments: Map<String, FuelSegment>,
    /** L/100 km over all segments; null with fewer than two fill-ups. */
    val averageConsumption: Double?,
    val inspections: List<Inspection>,
    val policies: List<InsurancePolicy>,
    /** Fuel, service records and other expenses since the start of the year, in cents. */
    val spentThisYearCents: Long,
)

data class Dated<T>(val item: T, val vehicle: Vehicle)

data class Dashboard(
    val vehicles: List<VehicleSummary>,
    /** Overdue first, then due soon; each ordered by whatever is closest. */
    val attention: List<DueItem>,
    val nextInspection: Dated<Inspection>?,
    /** The active policy ending soonest. */
    val insurance: Dated<InsurancePolicy>?,
    val recentFuel: List<Dated<FuelEntry>>,
    /** Segments of the recent fill-ups, for their detail sheets. */
    val fuelSegments: Map<String, FuelSegment> = emptyMap(),
)

object GarageMath {
    fun dueItems(
        schedules: List<Schedule>,
        vehicles: List<Vehicle>,
        templates: List<MaintenanceTemplate>,
        german: Boolean,
    ): List<DueItem> {
        val byId = vehicles.associateBy { it.id }
        val templateById = templates.associateBy { it.id }
        return schedules.mapNotNull { schedule ->
            val vehicle = byId[schedule.vehicleId] ?: return@mapNotNull null
            DueItem(
                schedule = schedule,
                vehicle = vehicle,
                name = schedule.displayName(schedule.templateId?.let(templateById::get), german),
                kmRemaining = schedule.nextDueOdometerKm?.let { it - vehicle.currentOdometerKm },
                dueAt = schedule.nextDueAt.toInstantOrNull(),
            )
        }.sortedWith(dueOrder)
    }

    /** Overdue → due soon → ok (as the web's maintenance list), then soonest date, then least distance left. */
    val dueOrder: Comparator<DueItem> = compareByDescending<DueItem> { it.status.ordinal }
        .thenBy(nullsLast()) { it.dueAt }
        .thenBy(nullsLast()) { it.kmRemaining }
        .thenBy { it.name }

    /** A service record's title, else its schedule's name; null when it has neither. */
    fun recordName(
        record: MaintenanceRecord,
        schedules: Map<String, Schedule>,
        templates: Map<String, MaintenanceTemplate>,
        german: Boolean,
    ): String? = record.title?.takeIf { it.isNotBlank() }
        ?: record.scheduleId?.let(schedules::get)?.let { it.displayName(it.templateId?.let(templates::get), german) }

    /** Own vehicles before shared ones, each by name, with their overdue and due-soon counts. */
    fun vehicleSummaries(vehicles: List<Vehicle>, due: List<DueItem>): List<VehicleSummary> {
        val counts = due.groupBy { it.vehicle.id }
        return vehicles.sortedWith(vehicleOrder).map { vehicle ->
            val mine = counts[vehicle.id].orEmpty()
            VehicleSummary(
                vehicle = vehicle,
                overdue = mine.count { it.status == DueStatus.OVERDUE },
                dueSoon = mine.count { it.status == DueStatus.DUE_SOON },
            )
        }
    }

    val vehicleOrder: Comparator<Vehicle> = compareBy<Vehicle>(
        { it.access.isShared },
        { listOfNotNull(it.make, it.model).joinToString(" ").lowercase().ifEmpty { it.displayName.lowercase() } },
        { it.year },
    )

    /** Everything the vehicle screen shows, newest first where it's a history. */
    fun vehicleDetail(
        vehicle: Vehicle,
        due: List<DueItem>,
        records: List<MaintenanceRecord>,
        fuel: List<FuelEntry>,
        expenses: List<Expense>,
        inspections: List<Inspection>,
        policies: List<InsurancePolicy>,
        yearStart: Instant,
    ): VehicleDetail {
        val mine = due.filter { it.vehicle.id == vehicle.id }
        val myFuel = fuel.filter { it.vehicleId == vehicle.id }
        val myRecords = records.filter { it.vehicleId == vehicle.id }
        val myExpenses = expenses.filter { it.vehicleId == vehicle.id }
        fun String.afterYearStart() = toInstantOrNull()?.let { !it.isBefore(yearStart) } == true
        // Expenses linked to a service record are the same money as the record, so they count once.
        val spent = myFuel.filter { it.filledAt.afterYearStart() }.sumOf { it.totalCostCents } +
            myRecords.filter { it.performedAt.afterYearStart() }.sumOf { it.costCents } +
            myExpenses.filter { it.maintenanceRecordId == null && it.occurredAt.afterYearStart() }.sumOf { it.amountCents }
        return VehicleDetail(
            vehicle = vehicle,
            summary = vehicleSummaries(listOf(vehicle), mine).single(),
            due = mine,
            records = myRecords.sortedByDescending { it.performedAt.toInstantOrNull() ?: Instant.MIN },
            fuel = myFuel.sortedByDescending { it.filledAt.toInstantOrNull() ?: Instant.MIN },
            segments = fuelSegments(myFuel),
            averageConsumption = averageConsumption(myFuel),
            inspections = inspections.filter { it.vehicleId == vehicle.id }.sortedBy { it.nextDueAt },
            policies = policies.filter { it.vehicleId == vehicle.id }.sortedByDescending { it.endDate },
            spentThisYearCents = spent,
        )
    }

    fun dashboard(
        vehicles: List<Vehicle>,
        schedules: List<Schedule>,
        templates: List<MaintenanceTemplate>,
        inspections: List<Inspection>,
        policies: List<InsurancePolicy>,
        fuel: List<FuelEntry>,
        german: Boolean,
        now: Instant = Instant.now(),
    ): Dashboard {
        val byId = vehicles.associateBy { it.id }
        val due = dueItems(schedules, vehicles, templates, german)
        return Dashboard(
            vehicles = vehicleSummaries(vehicles, due),
            attention = due.filter { it.status != DueStatus.OK },
            nextInspection = inspections
                .mapNotNull { inspection -> byId[inspection.vehicleId]?.let { Dated(inspection, it) } }
                .minByOrNull { it.item.nextDueAt.toInstantOrNull() ?: Instant.MAX },
            insurance = policies
                .filter { policy ->
                    val start = policy.startDate.toInstantOrNull() ?: Instant.MIN
                    val end = policy.endDate.toInstantOrNull() ?: Instant.MIN
                    !now.isBefore(start) && !now.isAfter(end)
                }
                .mapNotNull { policy -> byId[policy.vehicleId]?.let { Dated(policy, it) } }
                .minByOrNull { it.item.endDate.toInstantOrNull() ?: Instant.MAX },
            recentFuel = fuel
                .sortedByDescending { it.filledAt.toInstantOrNull() ?: Instant.MIN }
                .take(3)
                .mapNotNull { entry -> byId[entry.vehicleId]?.let { Dated(entry, it) } },
            fuelSegments = fuelSegments(fuel),
        )
    }

    /**
     * Fill-up segments, the web's `computeFuelAnalytics` rule: consecutive fill-ups of one vehicle, distance between
     * their odometers, liters of the later one. Keyed by the later entry's id.
     */
    fun fuelSegments(entries: List<FuelEntry>): Map<String, FuelSegment> {
        val result = mutableMapOf<String, FuelSegment>()
        entries.groupBy { it.vehicleId }.values.forEach { list ->
            val sorted = list.sortedBy { it.filledAt.toInstantOrNull() ?: Instant.MIN }
            sorted.zipWithNext().forEach { (prev, curr) ->
                val distance = (curr.odometerKm ?: return@forEach) - (prev.odometerKm ?: return@forEach)
                val liters = curr.liters ?: return@forEach
                if (distance > 0 && liters > 0) result[curr.id] = FuelSegment(distance, liters)
            }
        }
        return result
    }

    /** L/100 km per fill-up, keyed by entry id. */
    fun consumptionByEntry(entries: List<FuelEntry>): Map<String, Double> =
        fuelSegments(entries).mapValues { it.value.litersPer100Km }

    /** Average over all segments like the web: their liters over their distance, in L/100 km. */
    fun averageConsumption(entries: List<FuelEntry>): Double? {
        val segments = fuelSegments(entries).values
        val km = segments.sumOf { it.distanceKm }
        val liters = segments.sumOf { it.liters }
        return if (km > 0 && liters > 0) liters / km * 100 else null
    }
}

data class FuelSegment(val distanceKm: Int, val liters: Double) {
    val litersPer100Km: Double get() = liters / distanceKm * 100
}

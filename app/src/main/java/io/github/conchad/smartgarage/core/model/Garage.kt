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

data class Dated<T>(val item: T, val vehicle: Vehicle)

data class Dashboard(
    val vehicles: List<VehicleSummary>,
    /** Overdue first, then due soon; each ordered by whatever is closest. */
    val attention: List<DueItem>,
    val nextInspection: Dated<Inspection>?,
    /** The active policy ending soonest. */
    val insurance: Dated<InsurancePolicy>?,
    val recentFuel: List<Dated<FuelEntry>>,
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
        val counts = due.groupBy { it.vehicle.id }
        return Dashboard(
            vehicles = vehicles.sortedBy { it.displayName.lowercase() }.map { vehicle ->
                val mine = counts[vehicle.id].orEmpty()
                VehicleSummary(
                    vehicle = vehicle,
                    overdue = mine.count { it.status == DueStatus.OVERDUE },
                    dueSoon = mine.count { it.status == DueStatus.DUE_SOON },
                )
            },
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
        )
    }

    /**
     * Fuel economy per fill-up, the web's `computeFuelAnalytics` rule: consecutive fill-ups of one vehicle, distance
     * between their odometers, liters of the later one. Keyed by the later entry's id, in L/100 km.
     */
    fun consumptionByEntry(entries: List<FuelEntry>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        entries.groupBy { it.vehicleId }.values.forEach { list ->
            val sorted = list.sortedBy { it.filledAt.toInstantOrNull() ?: Instant.MIN }
            sorted.zipWithNext().forEach { (prev, curr) ->
                val distance = (curr.odometerKm ?: return@forEach) - (prev.odometerKm ?: return@forEach)
                val liters = curr.liters ?: return@forEach
                if (distance > 0 && liters > 0) result[curr.id] = liters / distance * 100
            }
        }
        return result
    }
}

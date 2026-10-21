package io.github.conchad.smartgarage.core.model

import java.time.Duration
import java.time.Instant

/** How soon something needs doing; the order Reminders groups by. */
enum class Urgency { OVERDUE, SOON, LATER }

/** One entry on the Reminders screen: a service, an inspection or an insurance policy ending. */
sealed interface Reminder {
    val id: String
    val vehicle: Vehicle
    val date: Instant?
    val urgency: Urgency

    data class Service(val item: DueItem) : Reminder {
        override val id get() = "s:${item.schedule.id}"
        override val vehicle get() = item.vehicle
        override val date get() = item.dueAt
        override val urgency get() = when (item.status) {
            DueStatus.OVERDUE -> Urgency.OVERDUE
            DueStatus.DUE_SOON -> Urgency.SOON
            DueStatus.OK -> Urgency.LATER
        }
    }

    data class InspectionDue(val inspection: Inspection, override val vehicle: Vehicle, override val urgency: Urgency) : Reminder {
        override val id get() = "i:${inspection.id}"
        override val date get() = inspection.nextDueAt.toInstantOrNull()
    }

    data class InsuranceEnds(val policy: InsurancePolicy, override val vehicle: Vehicle, override val urgency: Urgency) : Reminder {
        override val id get() = "p:${policy.id}"
        override val date get() = policy.endDate.toInstantOrNull()
    }
}

/** One thing that happened to a vehicle, for the History timeline. */
sealed interface TimelineEvent {
    val id: String
    val vehicleId: String
    val at: Instant

    data class Service(val record: MaintenanceRecord, override val at: Instant) : TimelineEvent {
        override val id get() = "r:${record.id}"
        override val vehicleId get() = record.vehicleId
    }

    data class Fuel(val entry: FuelEntry, override val at: Instant) : TimelineEvent {
        override val id get() = "f:${entry.id}"
        override val vehicleId get() = entry.vehicleId
    }

    data class Cost(val expense: Expense, override val at: Instant) : TimelineEvent {
        override val id get() = "e:${expense.id}"
        override val vehicleId get() = expense.vehicleId
    }

    data class Odometer(val log: OdometerLog, override val at: Instant) : TimelineEvent {
        override val id get() = "o:${log.id}"
        override val vehicleId get() = log.vehicleId
    }
}

object SectionMath {
    /** Insurance ending within this window counts as "soon". */
    private val INSURANCE_SOON: Duration = Duration.ofDays(30)

    /**
     * Everything coming up across the garage: every service schedule, every inspection, and active insurance
     * policies. Inspections are "soon" within their own reminder window, like the web's reminder queue.
     */
    fun reminders(
        due: List<DueItem>,
        inspections: List<Inspection>,
        policies: List<InsurancePolicy>,
        vehicles: List<Vehicle>,
        now: Instant = Instant.now(),
    ): List<Reminder> {
        val byId = vehicles.associateBy { it.id }
        val services = due.map(Reminder::Service)
        val checks = inspections.mapNotNull { inspection ->
            val vehicle = byId[inspection.vehicleId] ?: return@mapNotNull null
            val at = inspection.nextDueAt.toInstantOrNull()
            val urgency = when {
                at == null -> Urgency.LATER
                at.isBefore(now) -> Urgency.OVERDUE
                at.isBefore(now.plus(Duration.ofDays(7L * inspection.reminderWeeksBefore))) -> Urgency.SOON
                else -> Urgency.LATER
            }
            Reminder.InspectionDue(inspection, vehicle, urgency)
        }
        val insurance = policies.mapNotNull { policy ->
            val vehicle = byId[policy.vehicleId] ?: return@mapNotNull null
            val start = policy.startDate.toInstantOrNull() ?: return@mapNotNull null
            val end = policy.endDate.toInstantOrNull() ?: return@mapNotNull null
            if (now.isBefore(start) || now.isAfter(end)) return@mapNotNull null
            Reminder.InsuranceEnds(policy, vehicle, if (end.isBefore(now.plus(INSURANCE_SOON))) Urgency.SOON else Urgency.LATER)
        }
        return (services + checks + insurance).sortedWith(
            compareBy<Reminder> { it.urgency }.thenBy(nullsLast()) { it.date }.thenBy { it.id },
        )
    }

    /** Services, fill-ups, other costs and odometer readings, newest first. Costs filed under a service appear once. */
    fun timeline(
        records: List<MaintenanceRecord>,
        fuel: List<FuelEntry>,
        expenses: List<Expense>,
        odometer: List<OdometerLog>,
    ): List<TimelineEvent> = buildList {
        records.forEach { r -> r.performedAt.toInstantOrNull()?.let { add(TimelineEvent.Service(r, it)) } }
        fuel.forEach { f -> f.filledAt.toInstantOrNull()?.let { add(TimelineEvent.Fuel(f, it)) } }
        expenses.filter { it.maintenanceRecordId == null }
            .forEach { e -> e.occurredAt.toInstantOrNull()?.let { add(TimelineEvent.Cost(e, it)) } }
        odometer.forEach { o -> o.recordedAt.toInstantOrNull()?.let { add(TimelineEvent.Odometer(o, it)) } }
    }.sortedByDescending { it.at }

    /** Distance since the vehicle's previous reading, keyed by log id; absent for each vehicle's first reading. */
    fun odometerDeltas(logs: List<OdometerLog>): Map<String, Int> = buildMap {
        logs.groupBy { it.vehicleId }.values.forEach { list ->
            list.sortedBy { it.recordedAt.toInstantOrNull() ?: Instant.MIN }.zipWithNext().forEach { (prev, curr) ->
                put(curr.id, curr.odometerKm - prev.odometerKm)
            }
        }
    }
}

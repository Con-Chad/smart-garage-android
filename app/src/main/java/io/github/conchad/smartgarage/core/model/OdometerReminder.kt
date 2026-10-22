package io.github.conchad.smartgarage.core.model

import java.time.Duration
import java.time.Instant

/**
 * The monthly "update your mileage" nudge (D-A24), pure Kotlin so the rule is unit-tested.
 *
 * The last odometer evidence is the newest odometer log or fill-up with a reading; a vehicle with neither falls
 * back to [fallback] (its `updatedAt`, which the server set when the reading was entered on creation). The
 * reminder shows when that is more than [INTERVAL_DAYS] ago and "Later" wasn't tapped within the last
 * [INTERVAL_DAYS]. Only the age is decided here; the UI words it and gates it on `access.canEdit`.
 */
object OdometerReminder {
    const val INTERVAL_DAYS: Long = 30

    data class Reminder(val lastUpdate: Instant, val daysSince: Long)

    /** Newest reading evidence for [vehicleId], or null when there is none at all. */
    fun lastUpdate(vehicleId: String, logs: List<OdometerLog>, fuel: List<FuelEntry>): Instant? {
        val fromLogs = logs.filter { it.vehicleId == vehicleId }.mapNotNull { it.recordedAt.toInstantOrNull() }
        val fromFuel = fuel.filter { it.vehicleId == vehicleId && it.odometerKm != null }.mapNotNull { it.filledAt.toInstantOrNull() }
        return (fromLogs + fromFuel).maxOrNull()
    }

    /** The reminder to show, or null when the reading is recent enough, snoozed, or its age is unknown. */
    fun evaluate(
        vehicleId: String,
        logs: List<OdometerLog>,
        fuel: List<FuelEntry>,
        fallback: Instant?,
        now: Instant,
        snoozedAt: Instant?,
    ): Reminder? {
        val last = lastUpdate(vehicleId, logs, fuel) ?: fallback ?: return null
        val days = Duration.between(last, now).toDays()
        if (days <= INTERVAL_DAYS) return null
        if (snoozedAt != null && Duration.between(snoozedAt, now).toDays() < INTERVAL_DAYS && !snoozedAt.isAfter(now)) return null
        return Reminder(last, days)
    }
}

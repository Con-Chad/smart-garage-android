package io.github.conchad.smartgarage.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class SectionMathTest {
    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val mx5 = Vehicle(id = "v1", ownerUserId = "u", make = "Mazda", model = "MX-5")

    private fun due(id: String, status: String, dueAt: String?) =
        DueItem(Schedule(id = id, vehicleId = "v1", dueStatus = status, nextDueAt = dueAt), mx5, id, null, dueAt.toInstantOrNull())

    @Test
    fun `reminders group by urgency then date, with inspections inside their reminder window`() {
        val reminders = SectionMath.reminders(
            due = listOf(due("ok", "OK", "2027-03-01T00:00:00Z"), due("late", "OVERDUE", "2026-10-01T00:00:00Z")),
            inspections = listOf(
                Inspection(id = "hu", vehicleId = "v1", nextDueAt = "2026-11-10T00:00:00Z", reminderWeeksBefore = 4),
                Inspection(id = "au", vehicleId = "v1", nextDueAt = "2027-02-01T00:00:00Z", reminderWeeksBefore = 4),
                Inspection(id = "orphan", vehicleId = "gone", nextDueAt = "2026-10-01T00:00:00Z"),
            ),
            policies = listOf(
                InsurancePolicy(id = "ending", vehicleId = "v1", provider = "A", startDate = "2026-01-01T00:00:00Z", endDate = "2026-11-01T00:00:00Z"),
                InsurancePolicy(id = "expired", vehicleId = "v1", provider = "B", startDate = "2025-01-01T00:00:00Z", endDate = "2026-01-01T00:00:00Z"),
            ),
            vehicles = listOf(mx5),
            now = now,
        )

        assertThat(reminders.map { it.id to it.urgency }).containsExactly(
            "s:late" to Urgency.OVERDUE,
            "p:ending" to Urgency.SOON,
            "i:hu" to Urgency.SOON,
            "i:au" to Urgency.LATER,
            "s:ok" to Urgency.LATER,
        ).inOrder()
    }

    @Test
    fun `timeline is newest first and skips costs already filed under a service`() {
        val events = SectionMath.timeline(
            records = listOf(MaintenanceRecord(id = "r", vehicleId = "v1", performedAt = "2026-10-01T00:00:00Z")),
            fuel = listOf(FuelEntry(id = "f", vehicleId = "v1", filledAt = "2026-10-05T00:00:00Z")),
            expenses = listOf(
                Expense(id = "parking", vehicleId = "v1", occurredAt = "2026-10-03T00:00:00Z"),
                Expense(id = "service-cost", vehicleId = "v1", occurredAt = "2026-10-01T00:00:00Z", maintenanceRecordId = "r"),
            ),
            odometer = listOf(OdometerLog(id = "o", vehicleId = "v1", odometerKm = 1, recordedAt = "2026-10-07T00:00:00Z")),
        )

        assertThat(events.map { it.id }).containsExactly("o:o", "f:f", "e:parking", "r:r").inOrder()
    }

    @Test
    fun `odometer deltas compare each reading with the same vehicle's previous one`() {
        val logs = listOf(
            OdometerLog(id = "b", vehicleId = "v1", odometerKm = 1_500, recordedAt = "2026-10-10T00:00:00Z"),
            OdometerLog(id = "a", vehicleId = "v1", odometerKm = 1_000, recordedAt = "2026-10-01T00:00:00Z"),
            OdometerLog(id = "c", vehicleId = "v2", odometerKm = 9_000, recordedAt = "2026-10-05T00:00:00Z"),
        )

        assertThat(SectionMath.odometerDeltas(logs)).containsExactly("b", 500)
    }

    @Test
    fun `average consumption weighs segments by distance, like the web`() {
        val entries = listOf(
            FuelEntry(id = "a", vehicleId = "v1", filledAt = "2026-10-01T00:00:00Z", odometerKm = 1_000, liters = 40.0),
            FuelEntry(id = "b", vehicleId = "v1", filledAt = "2026-10-10T00:00:00Z", odometerKm = 1_500, liters = 30.0),
            FuelEntry(id = "c", vehicleId = "v1", filledAt = "2026-10-20T00:00:00Z", odometerKm = 1_600, liters = 10.0),
        )

        // (30 + 10) L over (500 + 100) km, not the mean of 6.0 and 10.0.
        assertThat(GarageMath.averageConsumption(entries)).isWithin(1e-9).of(40.0 / 600 * 100)
    }
}

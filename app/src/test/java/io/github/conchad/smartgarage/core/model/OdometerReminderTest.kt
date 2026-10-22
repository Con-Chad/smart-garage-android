package io.github.conchad.smartgarage.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class OdometerReminderTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))
    private fun log(vehicle: String, at: Instant) = OdometerLog(id = "l-$at", vehicleId = vehicle, odometerKm = 1, recordedAt = at.toString())
    private fun fill(vehicle: String, at: Instant, odometer: Int?) = FuelEntry(id = "f-$at", vehicleId = vehicle, filledAt = at.toString(), odometerKm = odometer)

    @Test
    fun `last update is the newest log or fill-up with a reading, for that vehicle only`() {
        val logs = listOf(log("v1", daysAgo(40)), log("v2", daysAgo(1)))
        val fuel = listOf(fill("v1", daysAgo(35), odometer = 1_000), fill("v1", daysAgo(2), odometer = null))

        assertThat(OdometerReminder.lastUpdate("v1", logs, fuel)).isEqualTo(daysAgo(35))
        assertThat(OdometerReminder.lastUpdate("v3", logs, fuel)).isNull()
    }

    @Test
    fun `reminds after thirty days and not before`() {
        assertThat(OdometerReminder.evaluate("v1", listOf(log("v1", daysAgo(30))), emptyList(), null, now, null)).isNull()
        val reminder = OdometerReminder.evaluate("v1", listOf(log("v1", daysAgo(45))), emptyList(), null, now, null)
        assertThat(reminder?.daysSince).isEqualTo(45)
    }

    @Test
    fun `snooze silences it for thirty days, then it comes back`() {
        val logs = listOf(log("v1", daysAgo(90)))
        assertThat(OdometerReminder.evaluate("v1", logs, emptyList(), null, now, snoozedAt = daysAgo(10))).isNull()
        assertThat(OdometerReminder.evaluate("v1", logs, emptyList(), null, now, snoozedAt = daysAgo(31))).isNotNull()
    }

    @Test
    fun `without any evidence the vehicle's own timestamp decides, and nothing at all means no reminder`() {
        assertThat(OdometerReminder.evaluate("v1", emptyList(), emptyList(), fallback = daysAgo(60), now = now, snoozedAt = null)?.daysSince).isEqualTo(60)
        assertThat(OdometerReminder.evaluate("v1", emptyList(), emptyList(), fallback = daysAgo(5), now = now, snoozedAt = null)).isNull()
        assertThat(OdometerReminder.evaluate("v1", emptyList(), emptyList(), fallback = null, now = now, snoozedAt = null)).isNull()
    }
}

package io.github.conchad.smartgarage.feature.vehicles

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Expense
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.Modification
import io.github.conchad.smartgarage.core.model.Vehicle
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** The vehicle page's state, built synchronously from in-memory inputs so the page opens on its content (D-A35). */
class VehicleDetailStateTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val mx5 = Vehicle(id = "v1", ownerUserId = "u", make = "Mazda", model = "MX-5", currentOdometerKm = 50_000)
    private val inputs = VehicleDetailInputs(
        vehicles = listOf(mx5),
        schedules = emptyList(),
        templates = emptyList(),
        records = listOf(MaintenanceRecord(id = "r1", vehicleId = "v1", performedAt = "2026-03-01T00:00:00Z", costCents = 10_000, title = "Oil")),
        fuel = emptyList(),
        expenses = listOf(Expense(id = "e1", vehicleId = "v1", occurredAt = "2026-04-01T00:00:00Z", amountCents = 500)),
        inspections = emptyList(),
        policies = emptyList(),
        customFields = emptyList(),
        customFieldValues = emptyList(),
        modifications = listOf(Modification(id = "m1", vehicleId = "v1", name = "Exhaust")),
        formatter = Formatter(null, Locale.US),
        trackFuelEconomy = false,
        accountName = "Connor",
    )

    @Test
    fun `a seeded state already carries the whole page`() {
        val state = vehicleDetailState("v1", inputs, german = false, now = now)

        assertThat(state.loaded).isTrue()
        assertThat(state.detail!!.vehicle).isEqualTo(mx5)
        assertThat(state.detail!!.records.map { it.id }).containsExactly("r1")
        assertThat(state.detail!!.spentThisYearCents).isEqualTo(10_500)
        assertThat(state.modifications.map { it.id }).containsExactly("m1")
        assertThat(state.ownerName).isEqualTo("Connor")
        assertThat(state.trackFuelEconomy).isFalse()
    }

    @Test
    fun `the live replay of the same inputs equals the seed, so the first frame is never replaced`() {
        // StateFlow drops a value equal to the current one; the seed and the replay must compare equal.
        assertThat(vehicleDetailState("v1", inputs, german = false, now = now))
            .isEqualTo(vehicleDetailState("v1", inputs, german = false, now = now))
    }

    @Test
    fun `a vehicle that is not there is loaded with nothing to show`() {
        val state = vehicleDetailState("gone", inputs, german = false, now = now)

        assertThat(state.loaded).isTrue()
        assertThat(state.detail).isNull()
    }
}

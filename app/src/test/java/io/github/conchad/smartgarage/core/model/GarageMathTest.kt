package io.github.conchad.smartgarage.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class GarageMathTest {
    private val mx5 = Vehicle(id = "v1", ownerUserId = "u", make = "Mazda", model = "MX-5", year = 2019, currentOdometerKm = 50_000)
    private val golf = Vehicle(id = "v2", ownerUserId = "u", make = "VW", model = "Golf", currentOdometerKm = 10_000)
    private val oil = MaintenanceTemplate(id = "t1", slug = "oil", nameEn = "Oil change", nameDe = "Ölwechsel")

    private fun schedule(id: String, vehicle: String, status: String, dueAt: String? = null, dueKm: Int? = null) =
        Schedule(id = id, vehicleId = vehicle, templateId = "t1", dueStatus = status, nextDueAt = dueAt, nextDueOdometerKm = dueKm)

    @Test
    fun `attention lists overdue before due soon and counts per vehicle`() {
        val schedules = listOf(
            schedule("ok", "v1", "OK"),
            schedule("soon", "v1", "DUE_SOON", dueAt = "2026-11-01T00:00:00Z"),
            schedule("late", "v2", "OVERDUE", dueKm = 9_000),
            schedule("orphan", "gone", "OVERDUE"),
        )

        val dashboard = GarageMath.dashboard(listOf(mx5, golf), schedules, listOf(oil), emptyList(), emptyList(), emptyList(), german = false)

        assertThat(dashboard.attention.map { it.schedule.id }).containsExactly("late", "soon").inOrder()
        assertThat(dashboard.attention.first().kmRemaining).isEqualTo(-1_000)
        assertThat(dashboard.attention.first().name).isEqualTo("Oil change")
        assertThat(dashboard.vehicles.map { Triple(it.vehicle.id, it.overdue, it.dueSoon) })
            .containsExactly(Triple("v1", 0, 1), Triple("v2", 1, 0)).inOrder()
    }

    @Test
    fun `template names follow the app language unless a custom name is set`() {
        assertThat(schedule("a", "v1", "OK").displayName(oil, german = true)).isEqualTo("Ölwechsel")
        assertThat(schedule("a", "v1", "OK").copy(customName = "Gear oil").displayName(oil, german = true)).isEqualTo("Gear oil")
    }

    @Test
    fun `insurance picks the active policy ending soonest`() {
        val now = Instant.parse("2026-10-21T00:00:00Z")
        fun policy(id: String, start: String, end: String) =
            InsurancePolicy(id = id, vehicleId = "v1", provider = id, startDate = start, endDate = end)
        val policies = listOf(
            policy("expired", "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
            policy("later", "2026-01-01T00:00:00Z", "2027-06-01T00:00:00Z"),
            policy("sooner", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z"),
        )

        val dashboard = GarageMath.dashboard(listOf(mx5), emptyList(), emptyList(), emptyList(), policies, emptyList(), false, now)

        assertThat(dashboard.insurance?.item?.id).isEqualTo("sooner")
    }

    @Test
    fun `consumption uses the distance since the previous fill-up of the same vehicle`() {
        val entries = listOf(
            FuelEntry(id = "a", vehicleId = "v1", filledAt = "2026-10-01T00:00:00Z", odometerKm = 1_000, liters = 40.0),
            FuelEntry(id = "b", vehicleId = "v1", filledAt = "2026-10-10T00:00:00Z", odometerKm = 1_500, liters = 30.0),
            FuelEntry(id = "c", vehicleId = "v2", filledAt = "2026-10-05T00:00:00Z", odometerKm = 1_200, liters = 20.0),
        )

        assertThat(GarageMath.consumptionByEntry(entries)).containsExactly("b", 6.0)
    }
}

package io.github.conchad.smartgarage.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

class ChartMathTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun fuel(id: String, at: String, km: Int?, liters: Double?, cents: Long = 6_000, vehicle: String = "v1") =
        FuelEntry(id = id, vehicleId = vehicle, filledAt = at, odometerKm = km, liters = liters, totalCostCents = cents)

    @Test
    fun `consumption series has one point per segment, oldest first, in liters per 100 km`() {
        val entries = listOf(
            fuel("c", "2026-10-20T00:00:00Z", 2_000, 25.0),
            fuel("a", "2026-10-01T00:00:00Z", 1_000, 40.0),
            fuel("b", "2026-10-10T00:00:00Z", 1_500, 30.0),
            fuel("no-odo", "2026-10-25T00:00:00Z", null, 20.0),
        )

        val series = ChartMath.consumptionSeries(entries)

        assertThat(series.map { it.id }).containsExactly("b", "c").inOrder()
        assertThat(series[0].value).isWithin(0.001).of(6.0)
        assertThat(series[1].value).isWithin(0.001).of(5.0)
    }

    @Test
    fun `price series is cents per liter and skips fill-ups without a volume or cost`() {
        val entries = listOf(
            fuel("b", "2026-10-10T00:00:00Z", null, 20.0, cents = 3_000),
            fuel("a", "2026-10-01T00:00:00Z", null, 40.0, cents = 7_000),
            fuel("free", "2026-10-12T00:00:00Z", null, 10.0, cents = 0),
            fuel("blank", "2026-10-13T00:00:00Z", null, null, cents = 5_000),
        )

        val series = ChartMath.priceSeries(entries)

        assertThat(series.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(series.map { it.value }).containsExactly(175.0, 150.0).inOrder()
    }

    @Test
    fun `monthly totals cover the whole window with zeros and drop older expenses`() {
        val now = Instant.parse("2026-10-21T12:00:00Z")
        fun expense(id: String, at: String, cents: Long) = Expense(id = id, vehicleId = "v1", occurredAt = at, amountCents = cents)
        val expenses = listOf(
            expense("this", "2026-10-03T10:00:00Z", 1_000),
            expense("also", "2026-10-30T10:00:00Z", 500),
            expense("earlier", "2026-07-15T10:00:00Z", 2_000),
            expense("too-old", "2025-10-31T10:00:00Z", 9_000),
            // Sep 30 22:30 UTC is already Oct 1 in Berlin; the server's zone decides the month.
            expense("edge", "2026-09-30T22:30:00Z", 7),
        )

        val totals = ChartMath.monthlyTotals(expenses, berlin, now)

        assertThat(totals).hasSize(12)
        assertThat(totals.first().month).isEqualTo(YearMonth.of(2025, 11))
        assertThat(totals.last().month).isEqualTo(YearMonth.of(2026, 10))
        assertThat(totals.last().cents).isEqualTo(1_507)
        assertThat(totals.first { it.month == YearMonth.of(2026, 7) }.cents).isEqualTo(2_000)
        assertThat(totals.count { it.cents == 0L }).isEqualTo(10)
    }

    @Test
    fun `category totals are largest first within the window`() {
        val now = Instant.parse("2026-10-21T12:00:00Z")
        fun expense(id: String, at: String, cents: Long, category: String) =
            Expense(id = id, vehicleId = "v1", occurredAt = at, amountCents = cents, category = category)
        val expenses = listOf(
            expense("a", "2026-10-03T10:00:00Z", 1_000, "TAX"),
            expense("b", "2026-09-03T10:00:00Z", 3_000, "REPAIR"),
            expense("c", "2026-08-03T10:00:00Z", 500, "TAX"),
            expense("old", "2024-08-03T10:00:00Z", 99_000, "INSURANCE"),
        )

        val totals = ChartMath.categoryTotals(expenses, berlin, now)

        assertThat(totals).containsExactly(CategoryTotal("REPAIR", 3_000), CategoryTotal("TAX", 1_500)).inOrder()
    }

    @Test
    fun `ticks are round numbers that enclose the data`() {
        assertThat(ChartMath.ticks(6.2, 8.9)).containsExactly(6.0, 7.0, 8.0, 9.0).inOrder()
        assertThat(ChartMath.ticks(0.0, 1_234.0, zeroBased = true)).containsExactly(0.0, 500.0, 1_000.0, 1_500.0).inOrder()
        assertThat(ChartMath.ticks(12.0, 14.0, zeroBased = true).first()).isEqualTo(0.0)
        // A flat series still gets a usable axis.
        assertThat(ChartMath.ticks(30.0, 30.0).size).isAtLeast(2)
        assertThat(ChartMath.ticks(0.0, 0.0, zeroBased = true)).containsExactly(0.0, 0.25, 0.5, 0.75, 1.0).inOrder()
    }
}

package io.github.conchad.smartgarage.core.model

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** One point of a chart: when it happened and its value in stored units; the screen converts for display. */
data class ChartPoint(val at: Instant, val value: Double, val id: String = "")

/** One month's total, oldest first; months without data carry zero so the bars keep their spacing. */
data class MonthTotal(val month: YearMonth, val cents: Long)

data class CategoryTotal(val category: String, val cents: Long)

/**
 * Series the charts draw, computed from the cached rows (D-A23). Pure Kotlin so the bucketing is unit-tested; the
 * values stay in stored units (L/100 km, cents per liter, km, cents) and the screen converts them through
 * `Formatter`, the same way the rows do.
 */
object ChartMath {
    /** The web's `consumptionHistory`: one point per fill-up that has a segment, oldest first, in L/100 km. */
    fun consumptionSeries(entries: List<FuelEntry>): List<ChartPoint> {
        val segments = GarageMath.fuelSegments(entries)
        return entries.asSequence()
            .mapNotNull { entry ->
                val at = entry.filledAt.toInstantOrNull() ?: return@mapNotNull null
                val segment = segments[entry.id] ?: return@mapNotNull null
                ChartPoint(at, segment.litersPer100Km, entry.id)
            }
            .sortedBy { it.at }
            .toList()
    }

    /** The web's `priceHistory`: cents per liter for each fill-up with a volume and a cost, oldest first. */
    fun priceSeries(entries: List<FuelEntry>): List<ChartPoint> = entries.asSequence()
        .mapNotNull { entry ->
            val at = entry.filledAt.toInstantOrNull() ?: return@mapNotNull null
            val liters = entry.liters?.takeIf { it > 0 } ?: return@mapNotNull null
            if (entry.totalCostCents <= 0) return@mapNotNull null
            ChartPoint(at, entry.totalCostCents / liters, entry.id)
        }
        .sortedBy { it.at }
        .toList()

    /** Odometer readings of one vehicle, oldest first, in km. */
    fun odometerSeries(logs: List<OdometerLog>): List<ChartPoint> = logs.asSequence()
        .mapNotNull { log -> log.recordedAt.toInstantOrNull()?.let { ChartPoint(it, log.odometerKm.toDouble(), log.id) } }
        .sortedBy { it.at }
        .toList()

    /**
     * The web's cost dashboard: totals for the last [months] calendar months ending with the one containing [now],
     * in [zone]; every month is present, zero when nothing was spent. Expenses outside the window are left out.
     */
    fun monthlyTotals(expenses: List<Expense>, zone: ZoneId, now: Instant, months: Int = 12): List<MonthTotal> {
        val last = YearMonth.from(now.atZone(zone))
        val first = last.minusMonths((months - 1).toLong())
        val sums = HashMap<YearMonth, Long>()
        expenses.forEach { expense ->
            val month = expense.occurredAt.toInstantOrNull()?.atZone(zone)?.let(YearMonth::from) ?: return@forEach
            if (month < first || month > last) return@forEach
            sums[month] = (sums[month] ?: 0L) + expense.amountCents
        }
        return (0 until months).map { i -> first.plusMonths(i.toLong()).let { MonthTotal(it, sums[it] ?: 0L) } }
    }

    /** Totals per category within the same window as [monthlyTotals], largest first. */
    fun categoryTotals(expenses: List<Expense>, zone: ZoneId, now: Instant, months: Int = 12): List<CategoryTotal> {
        val last = YearMonth.from(now.atZone(zone))
        val first = last.minusMonths((months - 1).toLong())
        return expenses
            .filter { expense ->
                val month = expense.occurredAt.toInstantOrNull()?.atZone(zone)?.let(YearMonth::from) ?: return@filter false
                month >= first && month <= last
            }
            .groupBy { it.category }
            .map { (category, list) -> CategoryTotal(category, list.sumOf { it.amountCents }) }
            .sortedWith(compareByDescending<CategoryTotal> { it.cents }.thenBy { it.category })
    }

    /**
     * Axis ticks for values between [min] and [max]: a "nice" step (1, 2, 2.5 or 5 × a power of ten) so the labels
     * read as round numbers, the first tick at or below [min] and the last at or above [max]. [zeroBased] forces the
     * axis to start at zero (bars). Always at least two ticks, even for a flat series.
     */
    fun ticks(min: Double, max: Double, zeroBased: Boolean = false, target: Int = 4): List<Double> {
        val low = if (zeroBased) minOf(0.0, min) else min
        var high = maxOf(max, low)
        if (high == low) high = if (low == 0.0) 1.0 else low + kotlin.math.abs(low) * 0.1
        val step = niceStep((high - low) / target)
        val start = floor(low / step) * step
        val end = ceil(high / step) * step
        val count = ((end - start) / step).toInt().coerceAtLeast(1)
        return (0..count).map { start + it * step }
    }

    private fun niceStep(raw: Double): Double {
        if (raw <= 0 || raw.isNaN() || raw.isInfinite()) return 1.0
        val magnitude = 10.0.pow(floor(log10(raw)))
        val fraction = raw / magnitude
        val nice = when {
            fraction <= 1 -> 1.0
            fraction <= 2 -> 2.0
            fraction <= 2.5 -> 2.5
            fraction <= 5 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }
}

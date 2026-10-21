package io.github.conchad.smartgarage.core.format

import io.github.conchad.smartgarage.core.data.DisplayPreferences
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns stored values (km, liters, cents, UTC instants) into display text using the server's preferences, the way
 * the web app does. Pure Kotlin, so the unit math is unit-tested.
 */
class Formatter(prefs: DisplayPreferences?, private val locale: Locale = Locale.getDefault()) {
    val miles: Boolean = prefs?.distanceUnit == "mi"
    val gallons: Boolean = prefs?.volumeUnit == "gal"
    private val currency: String = prefs?.currency ?: "EUR"
    val zone: ZoneId = prefs?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    private val integer = NumberFormat.getIntegerInstance(locale)
    private val oneDecimal = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }

    val distanceUnit: String get() = if (miles) "mi" else "km"
    val volumeUnit: String get() = if (gallons) "gal" else "L"
    /** Same labels as the web (`consumptionUnitLabel`): "MPG" for mi + gal, else e.g. "L/100 km". */
    val economyUnit: String get() = if (miles && gallons) "MPG" else "$volumeUnit/100 $distanceUnit"

    fun distanceValue(km: Number): Double = if (miles) km.toDouble() / KM_PER_MILE else km.toDouble()

    fun distance(km: Number): String = "${integer.format(distanceValue(km).roundToInt())} $distanceUnit"

    fun volume(liters: Double): String =
        "${oneDecimal.format(if (gallons) liters / LITERS_PER_GALLON else liters)} $volumeUnit"

    /** Price per volume unit, e.g. "$3.29/gal". */
    fun unitPrice(totalCents: Long, liters: Double, entryCurrency: String?): String? {
        if (liters <= 0) return null
        val perUnit = totalCents / 100.0 / (if (gallons) liters / LITERS_PER_GALLON else liters)
        return "${money(perUnit, entryCurrency, fractionDigits = if (gallons) 2 else 3)}/$volumeUnit"
    }

    /** Converts stored L/100 km like the web's `convertConsumption`; MPG is higher-is-better, the rest lower. */
    fun economyValue(litersPer100Km: Double): Double? {
        if (litersPer100Km <= 0) return null
        if (miles && gallons) return MPG_FACTOR / litersPer100Km
        val kmPer100Units = if (miles) 100 * KM_PER_MILE else 100.0
        val liters = litersPer100Km / 100 * kmPer100Units
        return if (gallons) liters / LITERS_PER_GALLON else liters
    }

    fun economy(litersPer100Km: Double): String? =
        economyValue(litersPer100Km)?.let { "${oneDecimal.format(it)} $economyUnit" }

    val higherEconomyIsBetter: Boolean get() = miles && gallons

    fun money(cents: Long, entryCurrency: String? = null): String = money(cents / 100.0, entryCurrency)

    fun money(amount: Double, entryCurrency: String?, fractionDigits: Int? = null): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(entryCurrency ?: currency) }
        fractionDigits?.let {
            format.minimumFractionDigits = it
            format.maximumFractionDigits = it
        }
        return format.format(amount)
    }

    fun date(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone).format(instant)

    /** "Oct 19" this year, "Dec 25, 2025" for other years. */
    fun shortDate(instant: Instant, today: LocalDate = LocalDate.now(zone)): String {
        val sameYear = instant.atZone(zone).year == today.year
        val pattern = when {
            locale.language == "de" -> if (sameYear) "d. MMM" else "d. MMM yyyy"
            else -> if (sameYear) "MMM d" else "MMM d, yyyy"
        }
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(instant)
    }

    /** Whole days from today (in the server's time zone) to [instant]; negative when it's in the past. */
    fun daysFromToday(instant: Instant, today: LocalDate = LocalDate.now(zone)): Long =
        ChronoUnit.DAYS.between(today, instant.atZone(zone).toLocalDate())

    companion object {
        const val KM_PER_MILE = 1.609344
        const val LITERS_PER_GALLON = 3.785411784
        /** Same constant as the web (`lib/regional/volume.ts`), so both show identical MPG. */
        const val MPG_FACTOR = 235.214583
    }
}

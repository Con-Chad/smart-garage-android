package io.github.conchad.smartgarage.core.format

import io.github.conchad.smartgarage.core.data.DisplayPreferences
import io.github.conchad.smartgarage.core.model.toInstantOrNull
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
import kotlin.math.roundToLong

/**
 * Turns stored values (km, liters, cents, UTC instants) into display text using the server's preferences, the way
 * the web app does. Pure Kotlin, so the unit math is unit-tested.
 */
class Formatter(prefs: DisplayPreferences?, private val locale: Locale = Locale.getDefault()) {
    val miles: Boolean = prefs?.distanceUnit == "mi"
    val gallons: Boolean = prefs?.volumeUnit == "gal"
    /** The server's preferred currency; new entries are stored in it. */
    val currencyCode: String = prefs?.currency ?: "EUR"
    private val currency: String get() = currencyCode
    val zone: ZoneId = prefs?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    private val integer = NumberFormat.getIntegerInstance(locale)
    private val oneDecimal = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    private val decimalSeparator: Char = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator

    // DateTimeFormatter is immutable and thread-safe; built once here rather than on every row of every list.
    private val mediumDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
    private val shortDateSameYear = DateTimeFormatter.ofPattern(if (locale.language == "de") "d. MMM" else "MMM d", locale).withZone(zone)
    private val shortDateOtherYear = DateTimeFormatter.ofPattern(if (locale.language == "de") "d. MMM yyyy" else "MMM d, yyyy", locale).withZone(zone)

    val distanceUnit: String get() = if (miles) "mi" else "km"
    val volumeUnit: String get() = if (gallons) "gal" else "L"
    /** Same labels as the web (`consumptionUnitLabel`): "MPG" for mi + gal, else e.g. "L/100 km". */
    val economyUnit: String get() = if (miles && gallons) "MPG" else "$volumeUnit/100 $distanceUnit"

    fun distanceValue(km: Number): Double = if (miles) km.toDouble() / KM_PER_MILE else km.toDouble()

    fun distance(km: Number): String = "${integer.format(distanceValue(km).roundToInt())} $distanceUnit"

    /** A distance already in the display unit (a chart axis), with grouping and the unit. */
    fun displayDistance(value: Double): String = "${integer.format(value.roundToInt())} $distanceUnit"

    /**
     * A custom field's stored text the way the web's `formatCustomFieldValue` shows it: yes/no for BOOLEAN, the
     * locale's date for DATE (an ISO instant or a plain `yyyy-MM-dd`), a grouped number plus unit for NUMBER, and
     * the text plus unit otherwise. Anything that doesn't parse is shown as typed.
     */
    fun customFieldValue(fieldType: String, unit: String?, value: String, yes: String, no: String): String {
        val text = when (fieldType) {
            "BOOLEAN" -> return if (value == "true") yes else no
            "DATE" -> value.toInstantOrNull()?.let(::date)
                ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()?.let { mediumDate.format(it) }
                ?: value
            "NUMBER" -> parseNumber(value)?.let { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }.format(it) } ?: value
            else -> value
        }
        return if (unit.isNullOrBlank()) text else "$text $unit"
    }

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

    fun date(instant: Instant): String = mediumDate.format(instant)

    /** "Oct 19" this year, "Dec 25, 2025" for other years. */
    fun shortDate(instant: Instant, today: LocalDate = LocalDate.now(zone)): String {
        val sameYear = instant.atZone(zone).year == today.year
        return (if (sameYear) shortDateSameYear else shortDateOtherYear).format(instant)
    }

    /** Whole days from today (in the server's time zone) to [instant]; negative when it's in the past. */
    fun daysFromToday(instant: Instant, today: LocalDate = LocalDate.now(zone)): Long =
        ChronoUnit.DAYS.between(today, instant.atZone(zone).toLocalDate())

    // ── Input: display → stored, for the forms (D-A20) ───────────────────────────────────────────────────────

    /** "$" for USD in the US, "€" in Germany; the code itself when the locale has no symbol for it. */
    val currencySymbol: String get() = runCatching { Currency.getInstance(currencyCode).getSymbol(locale) }.getOrDefault(currencyCode)

    /**
     * Parses what the user typed. A single "." or "," is the decimal mark whatever the locale, so "1,5" and "1.5"
     * both read as one and a half; when both appear, the last one is the decimal mark and the other is grouping.
     * Spaces and apostrophes are grouping. Null for anything that isn't a number.
     */
    fun parseNumber(text: String): Double? {
        val cleaned = text.trim().replace(" ", "").replace("\u00a0", "").replace("'", "")
        if (cleaned.isEmpty()) return null
        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')
        val decimal: Char? = when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) '.' else ','
            lastDot >= 0 -> if (cleaned.count { it == '.' } == 1) '.' else null
            lastComma >= 0 -> if (cleaned.count { it == ',' } == 1) ',' else null
            else -> null
        }
        val normalized = buildString {
            cleaned.forEach { c ->
                when {
                    c.isDigit() || c == '-' -> append(c)
                    c == decimal -> append('.')
                    c == '.' || c == ',' -> Unit
                    else -> return null
                }
            }
        }
        return normalized.toDoubleOrNull()
    }

    /** A distance typed in the display unit, as stored km. */
    fun kmFromDisplay(value: Double): Int = (if (miles) value * KM_PER_MILE else value).roundToInt()

    /** A volume typed in the display unit, as stored liters. */
    fun litersFromDisplay(value: Double): Double = if (gallons) value * LITERS_PER_GALLON else value

    /** An amount typed in the currency's major unit, as integer cents. */
    fun centsFromDisplay(value: Double): Long = (value * 100).roundToLong()

    /** Stored cents per liter as the price per display volume unit (a chart point), e.g. 175.3 c/L → 6.64 $/gal. */
    fun displayUnitPrice(centsPerLiter: Double): Double = centsPerLiter / 100.0 * (if (gallons) LITERS_PER_GALLON else 1.0)

    /** Price per display volume unit as a number (the form's "price per gal" field), or null without a volume. */
    fun unitPriceValue(totalCents: Long, liters: Double): Double? {
        if (liters <= 0) return null
        return totalCents / 100.0 / (if (gallons) liters / LITERS_PER_GALLON else liters)
    }

    /** A stored distance as editable text: display unit, no grouping, whole number. */
    fun distanceInput(km: Int): String = distanceValue(km).roundToInt().toString()

    /** A stored volume as editable text: display unit, up to three decimals, the locale's decimal mark. */
    fun volumeInput(liters: Double): String = inputNumber(if (gallons) liters / LITERS_PER_GALLON else liters, 3)

    /** Stored cents as editable text: two decimals, the locale's decimal mark, no symbol. */
    fun moneyInput(cents: Long): String = inputNumber(cents / 100.0, 2, minFraction = 2)

    /** A number as editable text: no grouping, the locale's decimal mark, trailing zeros dropped past [minFraction]. */
    fun inputNumber(value: Double, maxFraction: Int, minFraction: Int = 0): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = false
            minimumFractionDigits = minFraction
            maximumFractionDigits = maxFraction
        }
        return format.format(value)
    }

    /** The keyboard's decimal key; what [inputNumber] writes. */
    val decimalMark: Char get() = decimalSeparator

    fun today(): LocalDate = LocalDate.now(zone)

    fun localDate(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    /**
     * The instant to store for a date the user picked: [now] when it is today (the web and the server default
     * to the current time), else noon in the server's zone, which stays on that calendar day in any zone within
     * eleven hours of it, so neither this app nor the web shows the day before.
     */
    fun instantFor(date: LocalDate, now: Instant = Instant.now()): Instant =
        if (date == localDate(now)) now else date.atTime(12, 0).atZone(zone).toInstant()

    companion object {
        const val KM_PER_MILE = 1.609344
        const val LITERS_PER_GALLON = 3.785411784
        /** Same constant as the web (`lib/regional/volume.ts`), so both show identical MPG. */
        const val MPG_FACTOR = 235.214583
    }
}

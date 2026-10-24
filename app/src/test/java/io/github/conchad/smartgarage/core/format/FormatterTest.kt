package io.github.conchad.smartgarage.core.format

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

class FormatterTest {
    private val us = Formatter(DisplayPreferences(distanceUnit = "mi", volumeUnit = "gal", currency = "USD", timezone = "America/Chicago"), Locale.US)
    private val metric = Formatter(DisplayPreferences(distanceUnit = "km", volumeUnit = "L", currency = "EUR", timezone = "Europe/Berlin"), Locale.GERMANY)

    @Test
    fun `distance converts km to miles`() {
        assertThat(us.distance(160_934)).isEqualTo("100,000 mi")
        assertThat(metric.distance(12_345)).isEqualTo("12.345 km")
    }

    @Test
    fun `volume converts liters to gallons`() {
        assertThat(us.volume(37.85411784)).isEqualTo("10.0 gal")
        assertThat(metric.volume(42.0)).isEqualTo("42,0 L")
    }

    @Test
    fun `economy matches the web's units and constant`() {
        assertThat(us.economyUnit).isEqualTo("MPG")
        assertThat(us.economyValue(7.84048610)!!).isWithin(0.001).of(30.0)
        assertThat(us.higherEconomyIsBetter).isTrue()
        assertThat(metric.economyUnit).isEqualTo("L/100 km")
        assertThat(metric.economyValue(6.5)).isEqualTo(6.5)
        assertThat(metric.higherEconomyIsBetter).isFalse()
        assertThat(Formatter(DisplayPreferences(distanceUnit = "mi", volumeUnit = "L")).economyUnit).isEqualTo("L/100 mi")
        assertThat(us.economyValue(0.0)).isNull()
    }

    @Test
    fun `unit price per gallon`() {
        // $32.90 for 10 gal
        assertThat(us.unitPrice(3290, 37.85411784, "USD")).isEqualTo("$3.29/gal")
        assertThat(us.unitPrice(3290, 0.0, "USD")).isNull()
    }

    @Test
    fun `days from today use the server time zone`() {
        // 03:00 UTC on the 22nd is still the 21st in Chicago.
        val instant = Instant.parse("2026-10-22T03:00:00Z")
        assertThat(us.daysFromToday(instant, LocalDate.parse("2026-10-21"))).isEqualTo(0)
        assertThat(metric.daysFromToday(instant, LocalDate.parse("2026-10-21"))).isEqualTo(1)
    }

    @Test
    fun `a calendar day formats like the same instant in the server's zone`() {
        val day = LocalDate.parse("2026-11-15")
        assertThat(us.date(day)).isEqualTo(us.date(Instant.parse("2026-11-15T18:00:00Z")))
        assertThat(metric.date(day)).isEqualTo("15.11.2026")
    }

    @Test
    fun `date and time follow the locale and the server's zone (D-A70)`() {
        val instant = java.time.Instant.parse("2026-10-24T18:38:00Z")
        val en = us.dateTime(instant)
        assertThat(en).contains("Oct 24, 2026")
        assertThat(en).contains("1:38")
        val de = metric.dateTime(instant)
        assertThat(de).contains("24.10.2026")
        assertThat(de).contains("20:38")
    }

    @Test
    fun `short dates add the year only outside the current year`() {
        val today = LocalDate.parse("2026-10-21")
        assertThat(us.shortDate(Instant.parse("2026-03-05T18:00:00Z"), today)).isEqualTo("Mar 5")
        assertThat(us.shortDate(Instant.parse("2027-01-19T18:00:00Z"), today)).isEqualTo("Jan 19, 2027")
    }

    @Test
    fun `parses typed numbers with either decimal mark`() {
        assertThat(us.parseNumber("1.5")).isEqualTo(1.5)
        assertThat(us.parseNumber("1,5")).isEqualTo(1.5)
        assertThat(metric.parseNumber("1.234,56")).isEqualTo(1234.56)
        assertThat(us.parseNumber("1,234.56")).isEqualTo(1234.56)
        assertThat(us.parseNumber("1 234")).isEqualTo(1234.0)
        assertThat(us.parseNumber("12.345.678")).isEqualTo(12345678.0)
        assertThat(us.parseNumber(" 42 ")).isEqualTo(42.0)
        assertThat(us.parseNumber("")).isNull()
        assertThat(us.parseNumber("abc")).isNull()
        assertThat(us.parseNumber("1.2.3,4x")).isNull()
    }

    @Test
    fun `typed values convert to stored units`() {
        assertThat(us.kmFromDisplay(100_000.0)).isEqualTo(160_934)
        assertThat(metric.kmFromDisplay(12_345.0)).isEqualTo(12_345)
        assertThat(us.litersFromDisplay(10.0)).isWithin(1e-9).of(37.85411784)
        assertThat(metric.litersFromDisplay(42.0)).isEqualTo(42.0)
        assertThat(us.centsFromDisplay(3.29)).isEqualTo(329)
        assertThat(us.centsFromDisplay(0.1 + 0.2)).isEqualTo(30)
        assertThat(us.unitPriceValue(3290, 37.85411784)!!).isWithin(1e-6).of(3.29)
        assertThat(metric.unitPriceValue(6300, 35.0)!!).isWithin(1e-9).of(1.8)
        assertThat(metric.unitPriceValue(6300, 0.0)).isNull()
    }

    @Test
    fun `stored values become editable text without grouping`() {
        assertThat(us.distanceInput(160_934)).isEqualTo("100000")
        assertThat(metric.distanceInput(12_345)).isEqualTo("12345")
        assertThat(us.volumeInput(37.85411784)).isEqualTo("10")
        assertThat(metric.volumeInput(42.5)).isEqualTo("42,5")
        assertThat(us.moneyInput(3290)).isEqualTo("32.90")
        assertThat(metric.moneyInput(1234567)).isEqualTo("12345,67")
        assertThat(us.currencyCode).isEqualTo("USD")
        assertThat(us.currencySymbol).isEqualTo("$")
        assertThat(metric.currencySymbol).isEqualTo("€")
        assertThat(metric.decimalMark).isEqualTo(',')
    }

    @Test
    fun `a picked date is stored as now for today and noon otherwise`() {
        val now = Instant.parse("2026-10-21T20:30:00Z") // 15:30 in Chicago, 22:30 in Berlin
        assertThat(us.instantFor(LocalDate.parse("2026-10-21"), now)).isEqualTo(now)
        assertThat(us.instantFor(LocalDate.parse("2026-10-19"), now)).isEqualTo(Instant.parse("2026-10-19T17:00:00Z"))
        assertThat(metric.instantFor(LocalDate.parse("2026-10-19"), now)).isEqualTo(Instant.parse("2026-10-19T10:00:00Z"))
        assertThat(us.localDate(now)).isEqualTo(LocalDate.parse("2026-10-21"))
    }
}

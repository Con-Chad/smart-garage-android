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
    fun `short dates add the year only outside the current year`() {
        val today = LocalDate.parse("2026-10-21")
        assertThat(us.shortDate(Instant.parse("2026-03-05T18:00:00Z"), today)).isEqualTo("Mar 5")
        assertThat(us.shortDate(Instant.parse("2027-01-19T18:00:00Z"), today)).isEqualTo("Jan 19, 2027")
    }
}

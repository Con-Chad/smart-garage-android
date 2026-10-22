package io.github.conchad.smartgarage.core.format

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import org.junit.Test
import java.util.Locale

class CustomFieldFormatTest {
    private val us = Formatter(DisplayPreferences(distanceUnit = "mi", volumeUnit = "gal", currency = "USD", timezone = "America/Chicago"), Locale.US)
    private val de = Formatter(DisplayPreferences(distanceUnit = "km", volumeUnit = "L", currency = "EUR", timezone = "Europe/Berlin"), Locale.GERMANY)

    @Test
    fun `custom field values follow the web's formatting per type`() {
        assertThat(us.customFieldValue("BOOLEAN", null, "true", "Yes", "No")).isEqualTo("Yes")
        assertThat(us.customFieldValue("BOOLEAN", null, "false", "Yes", "No")).isEqualTo("No")
        assertThat(us.customFieldValue("NUMBER", "psi", "32.5", "Yes", "No")).isEqualTo("32.5 psi")
        assertThat(de.customFieldValue("NUMBER", null, "12345", "Ja", "Nein")).isEqualTo("12.345")
        assertThat(us.customFieldValue("DATE", null, "2026-03-05", "Yes", "No")).isEqualTo("Mar 5, 2026")
        assertThat(us.customFieldValue("DATE", null, "2026-03-05T12:00:00Z", "Yes", "No")).isEqualTo("Mar 5, 2026")
        assertThat(us.customFieldValue("TEXT", "mm", "245/40", "Yes", "No")).isEqualTo("245/40 mm")
    }

    @Test
    fun `values that don't parse are shown as typed`() {
        assertThat(us.customFieldValue("NUMBER", null, "n/a", "Yes", "No")).isEqualTo("n/a")
        assertThat(us.customFieldValue("DATE", null, "soon", "Yes", "No")).isEqualTo("soon")
    }

    @Test
    fun `display unit price converts cents per liter to the display volume unit`() {
        assertThat(us.displayUnitPrice(100.0)).isWithin(0.0001).of(3.785411784)
        assertThat(de.displayUnitPrice(175.3)).isWithin(0.0001).of(1.753)
    }

    @Test
    fun `display distance labels a chart axis in the display unit`() {
        assertThat(us.displayDistance(12345.6)).isEqualTo("12,346 mi")
        assertThat(de.displayDistance(1000.0)).isEqualTo("1.000 km")
    }
}

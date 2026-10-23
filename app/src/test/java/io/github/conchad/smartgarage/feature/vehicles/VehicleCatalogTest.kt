package io.github.conchad.smartgarage.feature.vehicles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VehicleCatalogTest {
    @Test
    fun blankQueryHasNoSuggestions() {
        assertEquals(emptyList<String>(), VehicleCatalog.suggestMakes(""))
        assertEquals(emptyList<String>(), VehicleCatalog.suggestMakes("  "))
        assertEquals(emptyList<String>(), VehicleCatalog.suggestModels("Dodge", ""))
        assertEquals(emptyList<String>(), VehicleCatalog.suggestYears("abc"))
    }

    @Test
    fun singleLetterListsTheMakesStartingWithIt() {
        assertEquals(listOf("Volkswagen", "Volvo"), VehicleCatalog.suggestMakes("V"))
        val c = VehicleCatalog.suggestMakes("c")
        assertEquals(listOf("Cadillac", "Chevrolet", "Chrysler", "Citroën", "Cupra"), c)
    }

    @Test
    fun makeMatchingIgnoresCasePunctuationAndAccents() {
        assertEquals(listOf("Mercedes-Benz"), VehicleCatalog.suggestMakes("mercedes benz"))
        assertEquals(listOf("Mercedes-Benz"), VehicleCatalog.suggestMakes("benz"))
        assertEquals(listOf("Škoda"), VehicleCatalog.suggestMakes("skod"))
        assertEquals(listOf("Land Rover"), VehicleCatalog.suggestMakes("rover"))
        assertEquals(listOf("Rolls-Royce"), VehicleCatalog.suggestMakes("rollsroyce"))
    }

    @Test
    fun catalogCoversTheCommonMakes() {
        val makes = VehicleCatalog.makes
        assertTrue(makes.size in 60..90)
        assertEquals(makes.size, makes.toSet().size)
        listOf("Ford", "Chevrolet", "Toyota", "Honda", "Dodge", "Ram", "Jeep", "Tesla", "BMW", "Volkswagen").forEach {
            assertTrue(it, it in makes)
        }
        VehicleCatalog.models.forEach { (make, models) ->
            assertTrue(make, models.isNotEmpty())
            assertEquals(make, models.size, models.toSet().size)
        }
    }

    @Test
    fun modelsFollowTheMakeAsTyped() {
        val dodge = VehicleCatalog.modelsFor(" dodge ")
        assertTrue(dodge.containsAll(listOf("Charger", "Challenger", "Durango", "Journey", "Dart", "Grand Caravan", "Ram 1500")))
        assertEquals(dodge.sortedBy { it.lowercase() }, dodge)
        assertTrue("1500" in VehicleCatalog.modelsFor("Ram"))
        assertEquals(emptyList<String>(), VehicleCatalog.modelsFor("Dod"))
        assertEquals(emptyList<String>(), VehicleCatalog.modelsFor("Yugo"))
        assertEquals("Mercedes-Benz", VehicleCatalog.canonicalMake("mercedes benz"))
        assertNull(VehicleCatalog.canonicalMake(""))
    }

    @Test
    fun modelSuggestionsStayWithinTheMake() {
        assertEquals(listOf("Challenger", "Charger"), VehicleCatalog.suggestModels("Dodge", "ch"))
        assertEquals(listOf("Ram 1500", "Ram 2500", "Ram 3500"), VehicleCatalog.suggestModels("Dodge", "ram"))
        assertEquals(listOf("F-150", "F-150 Lightning"), VehicleCatalog.suggestModels("Ford", "f150"))
        assertEquals(listOf("FJ Cruiser", "Land Cruiser"), VehicleCatalog.suggestModels("Toyota", "cruiser"))
        assertEquals(emptyList<String>(), VehicleCatalog.suggestModels("Unknown Motors", "c"))
    }

    @Test
    fun changingTheMakeKeepsOrClearsTheModel() {
        assertTrue(VehicleCatalog.keepsModel("Dodge", "Charger"))
        assertTrue(VehicleCatalog.keepsModel("dodge", "charger"))
        assertFalse(VehicleCatalog.keepsModel("Ford", "Charger"))
        // Still typing the make, or a make that isn't listed: leave the model alone.
        assertTrue(VehicleCatalog.keepsModel("Fo", "Charger"))
        assertTrue(VehicleCatalog.keepsModel("Yugo", "GV"))
        assertTrue(VehicleCatalog.keepsModel("Ford", ""))
    }

    @Test
    fun yearsRunFromNextYearDownTo1950() {
        val today = LocalDate.of(2026, 10, 23)
        val years = VehicleCatalog.years(today)
        assertEquals("2027", years.first())
        assertEquals("1950", years.last())
        assertEquals(2027 - 1950 + 1, years.size)
        assertEquals(listOf("2027", "2026", "2025", "2024", "2023", "2022", "2021", "2020"), VehicleCatalog.suggestYears("202", today))
        assertEquals(listOf("1999"), VehicleCatalog.suggestYears("1999", today))
        assertEquals(emptyList<String>(), VehicleCatalog.suggestYears("1890", today))
    }
}

package io.github.conchad.smartgarage.ui.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InsuranceProvidersTest {
    private fun suggest(q: String) = InsuranceProviders.suggest(q)

    @Test
    fun blankQueryHasNoSuggestions() {
        assertEquals(emptyList<String>(), suggest(""))
        assertEquals(emptyList<String>(), suggest("  "))
        assertEquals(emptyList<String>(), suggest("+-"))
    }

    @Test
    fun matchesNameStartCaseInsensitively() {
        assertEquals(listOf("Progressive"), suggest("Pro"))
        assertEquals(listOf("Progressive"), suggest("pRoG"))
        assertEquals(listOf("GEICO"), suggest("gei"))
    }

    @Test
    fun singleLetterRanksNameStartsAlphabeticallyIgnoringThe() {
        assertEquals(listOf("GAINSCO", "GEICO", "The General", "Generali", "Grange", "Grundy"), suggest("G"))
    }

    @Test
    fun genFindsTheGeneralAndGenerali() {
        assertEquals(listOf("The General", "Generali"), suggest("gen"))
    }

    @Test
    fun nameStartBeforeWordStart() {
        assertEquals(listOf("Farm Bureau", "Farmers", "State Farm", "Texas Farm Bureau"), suggest("farm"))
        assertEquals(listOf("Farm Bureau", "Texas Farm Bureau"), suggest("farm bu"))
    }

    @Test
    fun ignoresPunctuationAndLeadingThe() {
        assertEquals(listOf("The General", "Generali"), suggest("The Gene"))
        assertEquals(listOf("Generali"), suggest("the generali"))
        assertEquals(listOf("The Hartford"), suggest("hartford"))
        assertEquals(listOf("R+V"), suggest("r+v"))
        assertEquals(listOf("R+V"), suggest("rv"))
        assertEquals(listOf("R+V"), suggest("R V"))
        assertEquals(listOf("HUK-COBURG"), suggest("huk coburg"))
        assertEquals(listOf("HUK-COBURG"), suggest("hukcoburg"))
        assertEquals(listOf("HUK-COBURG"), suggest("coburg"))
        assertEquals(listOf("Auto-Owners"), suggest("auto own"))
    }

    @Test
    fun typingTheAloneStillFindsTheNames() {
        assertEquals(listOf("The General", "The Hanover", "The Hartford"), suggest("the"))
        val t = suggest("t")
        assertEquals(listOf("Texas Farm Bureau", "Travelers"), t.take(2))
        assertTrue(t.containsAll(listOf("The General", "The Hanover", "The Hartford")))
    }

    @Test
    fun midWordDoesNotMatch() {
        assertEquals(emptyList<String>(), suggest("eico"))
        assertEquals(emptyList<String>(), suggest("oot"))
    }

    @Test
    fun unknownNameHasNoSuggestions() {
        assertEquals(emptyList<String>(), suggest("Zurich Kleinversicherer"))
    }

    @Test
    fun listIsDistinctAndCoversEveryAppKeyword() {
        val names = InsuranceProviders.names
        assertEquals(names.size, names.map { it.lowercase() }.distinct().size)
        assertTrue(names.size >= 55)
        // Every insurer app in InsuranceApps resolves from some listed name, so suggestions can show its icon.
        InsuranceApps.table.forEach { entry ->
            assertTrue(entry.packageName, names.any { InsuranceApps.packageFor(it) == entry.packageName })
        }
    }
}

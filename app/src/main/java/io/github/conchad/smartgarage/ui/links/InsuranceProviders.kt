package io.github.conchad.smartgarage.ui.links

import java.util.Locale

/**
 * The built-in insurer names the insurance form suggests as the user types (D-A50). One list for the app: the
 * form's autocomplete reads [names]; the icon beside a suggestion comes from [InsuranceApps.packageFor] on the
 * same name (so every [InsuranceApps] keyword has a matching name here). The policy's `provider` stays free text:
 * a name that isn't on the list loads and saves unchanged.
 *
 * Matching ([suggest]) is case-insensitive and ignores punctuation and a leading "The": the query must match the
 * start of the name or the start of any word in it. Name-start matches rank first, then word-start matches,
 * alphabetical (ignoring "The") within each. Pure Kotlin, so it is unit-tested on the JVM.
 */
object InsuranceProviders {
    /** Major US auto insurers, then major German ones (the app ships de strings). Spelled and cased as the brands. */
    val names: List<String> = listOf(
        // US
        "AAA",
        "Alfa Insurance",
        "Allstate",
        "American Family",
        "Amica",
        "Arbella",
        "Auto-Owners",
        "Branch",
        "Bristol West",
        "Chubb",
        "Cincinnati Insurance",
        "Clearcover",
        "COUNTRY Financial",
        "Dairyland",
        "Direct Auto",
        "Elephant",
        "Encompass",
        "Erie Insurance",
        "Esurance",
        "Farm Bureau",
        "Farmers",
        "Foremost",
        "GAINSCO",
        "GEICO",
        "Grange",
        "Grundy",
        "Hagerty",
        "Horace Mann",
        "Infinity",
        "Kemper",
        "Lemonade",
        "Liberty Mutual",
        "MAPFRE",
        "Mercury Insurance",
        "Metromile",
        "Nationwide",
        "NJM",
        "Plymouth Rock",
        "Progressive",
        "Root",
        "Safeco",
        "Sentry",
        "Shelter",
        "State Farm",
        "Texas Farm Bureau",
        "The General",
        "The Hanover",
        "The Hartford",
        "Travelers",
        "USAA",
        "Wawanesa",
        "Westfield",
        // Germany
        "Allianz",
        "AXA",
        "DEVK",
        "ERGO",
        "Generali",
        "HDI",
        "HUK-COBURG",
        "R+V",
        "Signal Iduna",
        "VHV",
    )

    private class Indexed(val name: String, val words: List<String>, val full: String) {
        /** The words joined by single spaces, and with no spaces ("huk coburg", "hukcoburg"). */
        val joined = words.joinToString(" ")
        val compact = words.joinToString("")
    }

    private val index: List<Indexed> by lazy { names.map { Indexed(it, coreWords(it), words(it).joinToString(" ")) } }

    /**
     * The names matching [query], best first; empty for a blank query. Tier 0: the name (without "The", punctuation
     * folded to spaces) starts with the query, also with spaces dropped ("rv" finds "R+V"). Tier 1: some later word
     * starts the match ("farm" finds "State Farm"), or the name's own "The" does ("th" finds "The General").
     * Alphabetical by the folded name within a tier.
     */
    fun suggest(query: String): List<String> {
        val q = coreWords(query)
        if (q.isEmpty()) return emptyList()
        val joined = q.joinToString(" ")
        val compact = q.joinToString("")
        return index.mapNotNull { entry ->
            val tier = when {
                entry.joined.startsWith(joined) || entry.compact.startsWith(compact) -> 0
                (1 until entry.words.size).any { i -> entry.words.drop(i).joinToString(" ").startsWith(joined) } ||
                    entry.full.startsWith(joined) -> 1
                else -> return@mapNotNull null
            }
            Triple(tier, entry.joined, entry.name)
        }.sortedWith(compareBy({ it.first }, { it.second }, { it.third })).map { it.third }
    }

    /**
     * Lower-case words with punctuation as separators and a leading "the" dropped, unless "the" is all there is
     * (someone typing "The" is on the way to "The General"; matching it against nothing would hide everything).
     */
    internal fun coreWords(text: String): List<String> {
        val words = words(text)
        return if (words.size > 1 && words.first() == "the") words.drop(1) else words
    }

    private fun words(text: String): List<String> = text.lowercase(Locale.ROOT).split(NON_WORD).filter { it.isNotEmpty() }

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}

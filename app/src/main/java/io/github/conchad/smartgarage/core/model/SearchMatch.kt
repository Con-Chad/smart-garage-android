package io.github.conchad.smartgarage.core.model

import java.util.Locale

/**
 * Text search over cached rows (D-A23): the query is split into words, each word must appear somewhere in the row's
 * text, case- and accent-insensitive. Done in the ViewModel, never in composition. Pure Kotlin, unit-tested.
 */
object SearchMatch {
    /** The query's words, folded; empty for a blank query, which matches everything. */
    fun terms(query: String): List<String> = query.split(Regex("\\s+")).map(::fold).filter { it.isNotEmpty() }

    /** True when every term appears in at least one of [fields]. */
    fun matches(terms: List<String>, fields: List<String?>): Boolean {
        if (terms.isEmpty()) return true
        val haystack = fields.asSequence().filterNotNull().map(::fold).toList()
        return terms.all { term -> haystack.any { it.contains(term) } }
    }

    /** A service record: its display name, vendor, note and every part's name, brand, product and part number. */
    fun record(record: MaintenanceRecord, name: String?, terms: List<String>): Boolean = matches(
        terms,
        listOf(name, record.title, record.vendorName, record.note) + record.items.flatMap { listOf(it.name, it.brand, it.productName, it.partNumber, it.specification) },
    )

    fun fuel(entry: FuelEntry, terms: List<String>): Boolean = matches(terms, listOf(entry.stationName, entry.note))

    fun expense(expense: Expense, terms: List<String>): Boolean = matches(terms, listOf(expense.description, expense.category))

    fun odometer(log: OdometerLog, terms: List<String>): Boolean = matches(terms, listOf(log.note))

    /** A note: title, content and tags. */
    fun note(note: Note, terms: List<String>): Boolean = matches(terms, listOf(note.title, note.content) + note.tags)

    /** Lower-case without diacritics, so "Öl" matches "ol" and "Ol". */
    fun fold(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .trim()
}

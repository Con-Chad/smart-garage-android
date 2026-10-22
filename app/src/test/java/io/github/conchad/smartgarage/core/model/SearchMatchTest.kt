package io.github.conchad.smartgarage.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchMatchTest {
    private val record = MaintenanceRecord(
        id = "r1",
        vehicleId = "v1",
        performedAt = "2026-10-01T00:00:00Z",
        vendorName = "Müller Autoservice",
        note = "Replaced with the OEM part",
        items = listOf(MaintenanceItem(name = "Oil filter", brand = "Mann", partNumber = "HU 719/7 x")),
    )

    @Test
    fun `a blank query matches everything`() {
        assertThat(SearchMatch.terms("   ")).isEmpty()
        assertThat(SearchMatch.record(record, null, SearchMatch.terms(""))).isTrue()
    }

    @Test
    fun `records match on name, vendor, note and parts, ignoring case and accents`() {
        assertThat(SearchMatch.record(record, "Oil change", SearchMatch.terms("oil CHANGE"))).isTrue()
        assertThat(SearchMatch.record(record, null, SearchMatch.terms("muller"))).isTrue()
        assertThat(SearchMatch.record(record, null, SearchMatch.terms("oem"))).isTrue()
        assertThat(SearchMatch.record(record, null, SearchMatch.terms("719/7"))).isTrue()
        assertThat(SearchMatch.record(record, null, SearchMatch.terms("mann"))).isTrue()
        assertThat(SearchMatch.record(record, null, SearchMatch.terms("brake"))).isFalse()
    }

    @Test
    fun `every word must match somewhere`() {
        assertThat(SearchMatch.record(record, "Oil change", SearchMatch.terms("oil mann"))).isTrue()
        assertThat(SearchMatch.record(record, "Oil change", SearchMatch.terms("oil bosch"))).isFalse()
    }

    @Test
    fun `notes match on title, content and tags`() {
        val note = Note(id = "n", title = "Track day", content = "Torque lug nuts to 80 ft-lb.", tags = listOf("checklist"), updatedAt = "")
        assertThat(SearchMatch.note(note, SearchMatch.terms("track"))).isTrue()
        assertThat(SearchMatch.note(note, SearchMatch.terms("lug nuts"))).isTrue()
        assertThat(SearchMatch.note(note, SearchMatch.terms("#checklist".trimStart('#')))).isTrue()
        assertThat(SearchMatch.note(note, SearchMatch.terms("brakes"))).isFalse()
    }
}

package io.github.conchad.smartgarage.ui.links

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CalendarLinksTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `the server's next due date wins and is read in the server's zone`() {
        // 23:30 UTC on the 14th is already the 15th in Berlin.
        val date = CalendarLinks.scheduleDueDate("2026-11-14T23:30:00Z", "2026-05-01T12:00:00Z", 6, berlin)
        assertThat(date).isEqualTo(LocalDate.of(2026, 11, 15))
    }

    @Test
    fun `without a next due date the last service plus the interval is used`() {
        assertThat(CalendarLinks.scheduleDueDate(null, "2026-05-01T12:00:00Z", 6, berlin)).isEqualTo(LocalDate.of(2026, 11, 1))
        assertThat(CalendarLinks.scheduleDueDate(null, "2026-05-01T12:00:00Z", null, berlin)).isNull()
        assertThat(CalendarLinks.scheduleDueDate(null, "2026-05-01T12:00:00Z", 0, berlin)).isNull()
        assertThat(CalendarLinks.scheduleDueDate(null, null, 6, berlin)).isNull()
        assertThat(CalendarLinks.scheduleDueDate("not a date", null, 6, berlin)).isNull()
    }

    @Test
    fun `month arithmetic clamps to the end of a shorter month`() {
        assertThat(CalendarLinks.nextDueAfter(LocalDate.of(2026, 8, 31), 6)).isEqualTo(LocalDate.of(2027, 2, 28))
    }

    @Test
    fun `an all-day event spans midnight UTC to midnight UTC of the next day`() {
        val (begin, end) = CalendarLinks.allDayMillis(LocalDate.of(2026, 11, 15))
        assertThat(begin).isEqualTo(1_794_700_800_000L)
        assertThat(end - begin).isEqualTo(24 * 60 * 60 * 1000L)
    }

    @Test
    fun `description drops blank lines`() {
        assertThat(CalendarLinks.description("2019 Mazda MX-5", null, "  ", "Every 10,000 km")).isEqualTo("2019 Mazda MX-5\nEvery 10,000 km")
        assertThat(CalendarLinks.description(null, "")).isNull()
    }
}

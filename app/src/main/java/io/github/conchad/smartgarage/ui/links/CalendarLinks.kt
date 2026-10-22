package io.github.conchad.smartgarage.ui.links

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An all-day reminder for a due date, handed to whatever calendar app the user has (D-A25). Only the day matters:
 * a due date is a day, not a time, and an all-day event needs no time zone to read right in any calendar.
 */
data class CalendarEvent(val title: String, val description: String?, val date: LocalDate)

/** The date math and intent for "Add to calendar". The pure parts are unit-tested; the intent is built once, here. */
object CalendarLinks {

    /**
     * When a schedule is next due: the server's `nextDueAt` when it has one, else the last service plus the month
     * interval. Null when neither is known (a distance-only interval has no date).
     */
    fun scheduleDueDate(nextDueAt: String?, lastPerformedAt: String?, intervalMonths: Int?, zone: ZoneId): LocalDate? {
        nextDueAt.toInstantOrNull()?.let { return it.atZone(zone).toLocalDate() }
        val last = lastPerformedAt.toInstantOrNull()?.atZone(zone)?.toLocalDate() ?: return null
        return nextDueAfter(last, intervalMonths)
    }

    /** [performed] plus the month interval; null without a positive interval. */
    fun nextDueAfter(performed: LocalDate, intervalMonths: Int?): LocalDate? =
        intervalMonths?.takeIf { it > 0 }?.let { performed.plusMonths(it.toLong()) }

    /**
     * Begin and end of an all-day event in the form `CalendarContract` expects: midnight UTC of the day and of the
     * day after, whatever the device's zone.
     */
    fun allDayMillis(date: LocalDate): Pair<Long, Long> {
        val begin = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return begin to end
    }

    /** The description's lines joined; blank lines dropped so a missing note leaves no gap. */
    fun description(vararg lines: String?): String? =
        lines.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }

    /**
     * `ACTION_INSERT` on the calendar provider: Android shows the user's calendar apps (or the only one) with the event
     * prefilled; nothing is written without them saving it, so no permission is needed.
     */
    fun insertIntent(event: CalendarEvent): Intent {
        val (begin, end) = allDayMillis(event.date)
        return Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, event.description)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
    }

    /** Opens the calendar's new-event screen; false when no app on the phone handles it. */
    fun open(context: Context, event: CalendarEvent): Boolean = try {
        context.startActivity(insertIntent(event))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** "Oil change due — 2019 Mazda MX-5", with the vehicle and whatever [details] are known in the description. */
@Composable
fun dueCalendarEvent(name: String, vehicle: Vehicle, date: LocalDate, vararg details: String?): CalendarEvent = CalendarEvent(
    title = stringResource(R.string.calendar_title_due, name, vehicle.displayName),
    description = CalendarLinks.description(vehicle.displayName, *details),
    date = date,
)

/** "Allianz renewal — 2019 Mazda MX-5" for a policy's end date. */
@Composable
fun renewalCalendarEvent(provider: String, vehicle: Vehicle, date: LocalDate, vararg details: String?): CalendarEvent = CalendarEvent(
    title = stringResource(R.string.calendar_title_renewal, provider, vehicle.displayName),
    description = CalendarLinks.description(vehicle.displayName, *details),
    date = date,
)

/**
 * "Add to calendar" for a screen with a snackbar: returns a function that opens the calendar and, when no calendar
 * app exists, says so in [snackbar].
 */
@Composable
fun rememberAddToCalendar(snackbar: SnackbarHostState): (CalendarEvent) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val missing = stringResource(R.string.calendar_no_app)
    return remember(context, snackbar, missing) {
        { event -> if (!CalendarLinks.open(context, event)) scope.launch { snackbar.showSnackbar(missing) } }
    }
}

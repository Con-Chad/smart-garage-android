package io.github.conchad.smartgarage.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.DueItem
import kotlin.math.absoluteValue

/** "1,200 mi overdue · 12 days overdue" or "in 800 mi · in 18 days"; whichever limits the schedule has. */
@Composable
fun dueText(item: DueItem, fmt: Formatter, separator: String = " · "): String {
    val parts = buildList {
        item.kmRemaining?.let { km ->
            add(
                if (km < 0) stringResource(R.string.due_overdue_by, fmt.distance(km.absoluteValue))
                else stringResource(R.string.due_in, fmt.distance(km)),
            )
        }
        item.dueAt?.let { add(dueDays(fmt.daysFromToday(it).toInt())) }
    }
    return parts.joinToString(separator).ifEmpty { stringResource(R.string.due_no_interval) }
}

/** "today", "in 5 days" or "3 days overdue" for a due date. */
@Composable
fun dueDays(days: Int): String = when {
    days == 0 -> stringResource(R.string.due_today)
    days > 0 -> pluralStringResource(R.plurals.due_in_days, days, days)
    else -> pluralStringResource(R.plurals.due_days_overdue, -days, -days)
}

/** "today", "in 5 days" or "3 days ago" for a date that isn't a deadline. */
@Composable
fun relativeDays(days: Int): String = when {
    days == 0 -> stringResource(R.string.due_today)
    days > 0 -> pluralStringResource(R.plurals.due_in_days, days, days)
    else -> pluralStringResource(R.plurals.due_days_ago, -days, -days)
}

package io.github.conchad.smartgarage.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.DueItem
import kotlin.math.absoluteValue

/** "1,200 mi overdue · 12 days overdue" or "in 800 mi · in 3 weeks"; whichever limits the schedule has. */
@Composable
fun dueText(item: DueItem, fmt: Formatter): String {
    val parts = buildList {
        item.kmRemaining?.let { km ->
            add(
                if (km < 0) stringResource(R.string.due_overdue_by, fmt.distance(km.absoluteValue))
                else stringResource(R.string.due_in, fmt.distance(km)),
            )
        }
        item.dueAt?.let { add(relativeDays(fmt.daysFromToday(it).toInt())) }
    }
    return parts.joinToString(" · ").ifEmpty { stringResource(R.string.due_no_interval) }
}

/** "today", "in 5 days", "3 days ago"-style text for a day offset. */
@Composable
fun relativeDays(days: Int): String = when {
    days == 0 -> stringResource(R.string.due_today)
    days > 0 -> pluralStringResource(R.plurals.due_in_days, days, days)
    else -> pluralStringResource(R.plurals.due_days_ago, -days, -days)
}

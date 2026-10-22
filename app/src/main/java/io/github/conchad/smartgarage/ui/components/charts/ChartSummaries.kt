package io.github.conchad.smartgarage.ui.components.charts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.ChartPoint

/**
 * What TalkBack reads for a line chart: the title, how many points over which dates, and the latest, lowest and
 * highest values. [points] are in display units already.
 */
@Composable
fun lineSummary(title: String, points: List<ChartPoint>, fmt: Formatter, format: (Double) -> String): String {
    if (points.isEmpty()) return title
    return stringResource(
        R.string.chart_summary_line,
        title,
        pluralStringResource(R.plurals.chart_points, points.size, points.size),
        fmt.date(points.first().at),
        fmt.date(points.last().at),
        format(points.last().value),
        format(points.minOf { it.value }),
        format(points.maxOf { it.value }),
    )
}

/** What TalkBack reads for a bar chart of months: the span, the total and the highest month. */
@Composable
fun barSummary(title: String, labels: List<String>, values: List<Double>, format: (Double) -> String): String {
    if (values.isEmpty()) return title
    val top = values.indices.maxBy { values[it] }
    return stringResource(
        R.string.chart_summary_bars,
        title,
        pluralStringResource(R.plurals.chart_months, values.size, values.size),
        labels.first(),
        labels.last(),
        format(values.sum()),
        format(values[top]),
        labels[top],
    )
}

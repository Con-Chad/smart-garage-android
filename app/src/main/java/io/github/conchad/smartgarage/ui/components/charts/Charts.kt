package io.github.conchad.smartgarage.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import io.github.conchad.smartgarage.core.model.ChartMath
import io.github.conchad.smartgarage.ui.glass.glass
import kotlin.math.ceil

/**
 * Charts drawn in-house with `Canvas` (D-A23): no library, no touch handling. Values arrive already converted to
 * display units; [formatValue] writes the axis labels the way the rows do (`Formatter`). Colors come from the
 * theme, so dynamic color and dark mode apply; text is measured in sp, so font scale 1.3 just makes the axis wider.
 * TalkBack reads the chart as one image whose description is [summary]; the drawing itself has no semantics.
 */
@Composable
fun LineChart(
    values: List<Double>,
    formatValue: (Double) -> String,
    summary: String,
    modifier: Modifier = Modifier,
    startLabel: String? = null,
    endLabel: String? = null,
    color: Color = glass.sheen,
    zeroBased: Boolean = false,
    plotHeight: Dp = 150.dp,
) {
    val labelStyle = axisStyle()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val labelHeight = labelLineHeight(labelStyle)
    val measurer = rememberTextMeasurer()
    val ticks = remember(values, zeroBased) {
        ChartMath.ticks(values.minOrNull() ?: 0.0, values.maxOrNull() ?: 0.0, zeroBased = zeroBased)
    }
    val tickLabels = remember(ticks, formatValue) { ticks.map(formatValue) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(plotHeight + labelHeight + AXIS_GAP)
            .clearAndSetSemantics {
                role = Role.Image
                contentDescription = summary
            },
    ) {
        val frame = frame(measurer, labelStyle, tickLabels, labelHeight.toPx())
        drawGrid(frame, ticks, tickLabels, measurer, labelStyle, gridColor)
        drawXLabels(frame, startLabel, endLabel, measurer, labelStyle)
        if (values.isEmpty()) return@Canvas
        val lo = ticks.first()
        val hi = ticks.last()
        val step = if (values.size > 1) frame.plot.width / (values.size - 1) else 0f
        val points = values.mapIndexed { i, v ->
            Offset(
                x = frame.plot.left + if (values.size > 1) i * step else frame.plot.width / 2,
                y = frame.plot.bottom - ((v - lo) / (hi - lo)).toFloat() * frame.plot.height,
            )
        }
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (points.size <= MAX_DOTS) {
            points.forEach { drawCircle(color, radius = 3.dp.toPx(), center = it) }
        }
    }
}

/** Bars for buckets (months); [labels] sit under their bars, thinned out until they fit at the current font size. */
@Composable
fun BarChart(
    values: List<Double>,
    labels: List<String>,
    formatValue: (Double) -> String,
    summary: String,
    modifier: Modifier = Modifier,
    color: Color = glass.sheen,
    plotHeight: Dp = 150.dp,
) {
    val labelStyle = axisStyle()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val labelHeight = labelLineHeight(labelStyle)
    val measurer = rememberTextMeasurer()
    val ticks = remember(values) { ChartMath.ticks(0.0, values.maxOrNull() ?: 0.0, zeroBased = true) }
    val tickLabels = remember(ticks, formatValue) { ticks.map(formatValue) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(plotHeight + labelHeight + AXIS_GAP)
            .clearAndSetSemantics {
                role = Role.Image
                contentDescription = summary
            },
    ) {
        val frame = frame(measurer, labelStyle, tickLabels, labelHeight.toPx())
        drawGrid(frame, ticks, tickLabels, measurer, labelStyle, gridColor)
        if (values.isEmpty()) return@Canvas
        val hi = ticks.last()
        val slot = frame.plot.width / values.size
        val gap = (slot * 0.25f).coerceAtMost(8.dp.toPx())
        val barWidth = (slot - gap).coerceAtLeast(1f)
        val radius = CornerRadius(3.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = (v / hi).toFloat().coerceIn(0f, 1f) * frame.plot.height
            if (h <= 0f) return@forEachIndexed
            drawRoundRect(
                color,
                topLeft = Offset(frame.plot.left + i * slot + gap / 2, frame.plot.bottom - h),
                size = Size(barWidth, h),
                cornerRadius = radius,
            )
        }
        // Every n-th label, where n is the smallest count whose labels don't overlap at this font size.
        val widest = labels.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0
        val every = if (widest == 0) 1 else ceil((widest + 8.dp.toPx()) / slot).toInt().coerceAtLeast(1)
        labels.forEachIndexed { i, label ->
            if ((labels.size - 1 - i) % every != 0) return@forEachIndexed
            val layout = measurer.measure(label, labelStyle)
            val x = (frame.plot.left + i * slot + slot / 2 - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(x, frame.plot.bottom + AXIS_GAP.toPx()))
        }
    }
}

/** A chart's title and an optional line under it ("Last 12 months"), in the tone the group cards use. */
@Composable
fun ChartTitle(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = glass.textSecondary) }
    }
}

/** Where a chart would be when there isn't enough data: the reason, in the secondary color. */
@Composable
fun ChartPlaceholder(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = glass.textSecondary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private val AXIS_GAP = 6.dp
private const val MAX_DOTS = 40

@Composable
private fun axisStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(color = glass.textSecondary)

@Composable
private fun labelLineHeight(style: TextStyle): Dp = with(LocalDensity.current) {
    (if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.4f).toDp()
}

private class ChartFrame(val plot: androidx.compose.ui.geometry.Rect, val axisWidth: Float)

/** The plot rectangle: the y-axis labels take their widest width on the left, the x labels one line at the bottom. */
private fun DrawScope.frame(measurer: TextMeasurer, style: TextStyle, tickLabels: List<String>, labelHeight: Float): ChartFrame {
    val axisWidth = (tickLabels.maxOfOrNull { measurer.measure(it, style).size.width } ?: 0) + AXIS_GAP.toPx()
    val top = labelHeight / 2
    val bottom = size.height - labelHeight - AXIS_GAP.toPx()
    return ChartFrame(androidx.compose.ui.geometry.Rect(axisWidth, top, size.width - 4.dp.toPx(), bottom), axisWidth)
}

private fun DrawScope.drawGrid(
    frame: ChartFrame,
    ticks: List<Double>,
    tickLabels: List<String>,
    measurer: TextMeasurer,
    style: TextStyle,
    gridColor: Color,
) {
    val lo = ticks.first()
    val hi = ticks.last()
    ticks.forEachIndexed { i, tick ->
        val y = frame.plot.bottom - ((tick - lo) / (hi - lo)).toFloat() * frame.plot.height
        drawLine(gridColor, Offset(frame.plot.left, y), Offset(frame.plot.right, y), strokeWidth = 1.dp.toPx())
        val layout: TextLayoutResult = measurer.measure(tickLabels[i], style)
        drawText(layout, topLeft = Offset(frame.axisWidth - AXIS_GAP.toPx() - layout.size.width, y - layout.size.height / 2))
    }
}

private fun DrawScope.drawXLabels(frame: ChartFrame, start: String?, end: String?, measurer: TextMeasurer, style: TextStyle) {
    val y = frame.plot.bottom + AXIS_GAP.toPx()
    start?.let { drawText(measurer.measure(it, style), topLeft = Offset(frame.plot.left, y)) }
    end?.let {
        val layout = measurer.measure(it, style)
        drawText(layout, topLeft = Offset(frame.plot.right - layout.size.width, y))
    }
}

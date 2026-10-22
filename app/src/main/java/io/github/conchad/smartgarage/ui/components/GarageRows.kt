package io.github.conchad.smartgarage.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.DueItem
import io.github.conchad.smartgarage.core.model.DueStatus
import io.github.conchad.smartgarage.core.model.FuelEntry
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.forms.PendingMark
import io.github.conchad.smartgarage.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import java.time.Instant

/** Title above a group, with an optional text action such as "See all". */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (onAction != null) {
            TextButton(onClick = onAction) { Text(action ?: stringResource(R.string.action_see_all)) }
        }
    }
}

/**
 * The one card for a group of rows, on every screen: data rows separated by [RowDivider] under a [GroupHeader], and
 * settings-style `ListItem`s under a [SectionLabel]. One tone (`surfaceContainer`) so cards read the same on the
 * Dashboard, the lists and Settings; on the dark theme the lower tones all but vanish against the background.
 */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) { Column(content = content) }
}

@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/**
 * Label on the left, value on the right; for detail sheets and spec lists. A long value (notes, a long product name)
 * goes under its label instead, so neither side gets squeezed into breaking words. The threshold shrinks with the
 * font scale, since the row doesn't get any wider when the text does. TalkBack reads label and value as one item.
 */
@Composable
fun DetailRow(label: String, value: String?, modifier: Modifier = Modifier) {
    if (value.isNullOrBlank()) return
    val labelStyle = MaterialTheme.typography.bodyMedium
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val stackAfter = (STACK_AFTER / LocalDensity.current.fontScale).toInt()
    val rowModifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}
    if (value.length > stackAfter || '\n' in value) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = labelStyle, color = labelColor)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    } else {
        Row(rowModifier, verticalAlignment = Alignment.Top) {
            Text(label, style = labelStyle, color = labelColor, modifier = Modifier.weight(1f).padding(end = 16.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End)
        }
    }
}

private const val STACK_AFTER = 22

/** A schedule with its status dot, how far away it is and (optionally) its vehicle. */
@Composable
fun DueRow(item: DueItem, fmt: Formatter, onClick: () -> Unit, showVehicle: Boolean = true) {
    val colors = LocalStatusColors.current
    val tint = when (item.status) {
        DueStatus.OVERDUE -> colors.overdue
        DueStatus.DUE_SOON -> colors.dueSoon
        DueStatus.OK -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = if (item.status == DueStatus.OK) colors.ok else tint,
            modifier = Modifier.size(10.dp),
        ) {}
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // Due text first, in the status color; the vehicle after it in the usual secondary color.
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            val due = dueText(item, fmt)
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Medium)) { append(due) }
                    if (showVehicle) withStyle(SpanStyle(color = secondary)) { append(" · ${item.vehicle.shortName}") }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One fill-up: vehicle (or station) on top, date and station below, cost and volume on the right. [economy] is the
 * formatted consumption since the previous fill-up, when known.
 */
@Composable
fun FuelRow(entry: FuelEntry, vehicle: Vehicle?, fmt: Formatter, onClick: () -> Unit, economy: String? = null, pending: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                vehicle?.displayName ?: entry.stationName ?: "—",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    entry.filledAt.toInstantOrNull()?.let(fmt::shortDate),
                    entry.stationName.takeIf { vehicle != null },
                    economy,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (pending) PendingMark(Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt.money(entry.totalCostCents, entry.currency), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End)
            entry.liters?.let {
                Text(
                    fmt.volume(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** "All vehicles" plus one chip per vehicle; hidden when there is only one vehicle. */
@Composable
fun VehicleFilterRow(vehicles: List<Vehicle>, selectedId: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    if (vehicles.size < 2) return
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(selected = selectedId == null, onClick = { onSelect(null) }, label = { Text(stringResource(R.string.filter_all_vehicles)) })
        }
        items(vehicles, key = { it.id }) { vehicle ->
            FilterChip(
                selected = selectedId == vehicle.id,
                onClick = { onSelect(vehicle.id) },
                label = { Text(vehicle.shortName, maxLines = 1) },
            )
        }
    }
}

/** "MX-5" rather than "2019 Mazda MX-5", for chips. */
val Vehicle.shortName: String get() = model ?: make ?: displayName

/** The current time, updated every [periodMillis], so "updated 3 min ago" stays true while the screen is open. */
@Composable
fun rememberNow(periodMillis: Long = 30_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            delay(periodMillis)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** "just now" under a minute, else the platform's localized "5 minutes ago". */
@Composable
fun relativeTime(instant: Instant, now: Long = rememberNow()): String {
    val millis = instant.toEpochMilli()
    return if (now - millis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.time_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}

/** HU and AU are German inspections; English shows what they are. */
@Composable
fun inspectionLabel(type: String): String = when (type) {
    "HU" -> stringResource(R.string.inspection_hu)
    "AU" -> stringResource(R.string.inspection_au)
    else -> type
}

package io.github.conchad.smartgarage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.DueItem
import io.github.conchad.smartgarage.core.model.DueStatus
import io.github.conchad.smartgarage.core.model.FuelEntry
import io.github.conchad.smartgarage.core.model.FuelSegment
import io.github.conchad.smartgarage.core.model.MaintenanceItem
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.components.forms.PendingMark
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import java.text.NumberFormat

/** Bottom sheet frame: icon, title and subtitle, then scrollable content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(icon, size = 48)
                Column(Modifier.padding(start = 16.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Rows of a detail sheet end with its actions (D-A20): the primary verb as a filled button, Edit outlined, Delete as
 * a text button in the error color; or a [ViewOnlyNote] on a vehicle the user can't edit. Each is 48 dp tall.
 */
@Composable
fun SheetActions(
    vehicle: Vehicle?,
    primary: Pair<String, () -> Unit>? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** A second, less common verb ("Default parts"), outlined and full width between the primary and Edit/Delete. */
    secondary: Pair<String, () -> Unit>? = null,
    /** Delete's label when it isn't a plain delete ("Stop reminders"). */
    deleteLabel: String = stringResource(R.string.action_delete),
    /** "Add to calendar" for the item's due date (D-A25); a reminder is not an edit, so viewers get it too. */
    calendar: Pair<String, () -> Unit>? = null,
) {
    val canEdit = vehicle?.access?.canEdit ?: true
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        calendar?.let { (dateText, action) -> AddToCalendarButton(dateText, action) }
        if (!canEdit) {
            ViewOnlyNote(vehicle?.owner?.name, Modifier.padding(vertical = 8.dp))
            return@Column
        }
        primary?.let { (label, action) ->
            Button(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
        }
        secondary?.let { (label, action) ->
            OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onEdit?.let {
                OutlinedButton(onClick = it, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(stringResource(R.string.action_edit)) }
            }
            onDelete?.let {
                TextButton(
                    onClick = it,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(deleteLabel, textAlign = TextAlign.Center) }
            }
        }
    }
}

/** "Delete this fill-up?" before [onConfirm]; the row vanishes at once and the delete queues (D-A16). */
@Composable
fun DeleteConfirm(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(title, body, stringResource(R.string.action_delete), onConfirm, onDismiss, destructive = true)
}

/**
 * A schedule: status, due, interval, cost, notes and default parts. With [onLogService] the sheet has its actions;
 * [onDelete] is the web's "deactivate": the schedule stops reminding, its records stay, so the confirmation says so.
 */
@Composable
fun ScheduleSheet(
    item: DueItem,
    fmt: Formatter,
    onDismiss: () -> Unit,
    onLogService: (() -> Unit)? = null,
    pending: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onEditDefaults: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Opens the calendar with the due date prefilled (D-A25); only offered when the schedule has a date. */
    onAddToCalendar: (() -> Unit)? = null,
) {
    val schedule = item.schedule
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    DetailSheet(Icons.Outlined.Build, item.name, item.vehicle.displayName, onDismiss) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            StatusChip(
                item.status,
                when (item.status) {
                    DueStatus.OVERDUE -> stringResource(R.string.status_overdue)
                    DueStatus.DUE_SOON -> stringResource(R.string.status_due_soon)
                    DueStatus.OK -> stringResource(R.string.status_ok)
                },
            )
        }
        DetailRow(stringResource(R.string.schedule_when), dueText(item, fmt))
        DetailRow(stringResource(R.string.schedule_next_due_date), item.dueAt?.let(fmt::date))
        DetailRow(stringResource(R.string.schedule_next_due_odometer), schedule.nextDueOdometerKm?.let(fmt::distance))
        DetailRow(stringResource(R.string.schedule_interval), intervalText(schedule.intervalKm, schedule.intervalMonths, fmt))
        DetailRow(
            stringResource(R.string.schedule_last_done),
            listOfNotNull(schedule.lastPerformedAt.toInstantOrNull()?.let(fmt::date), schedule.lastOdometerKm?.let(fmt::distance))
                .joinToString(" · ").ifEmpty { null },
        )
        DetailRow(stringResource(R.string.schedule_estimated_cost), schedule.estimatedCostCents?.let { fmt.money(it, schedule.currency) })
        DetailRow(stringResource(R.string.field_notes), schedule.notes)
        if (schedule.itemDefaults.isNotEmpty()) {
            GroupHeader(stringResource(R.string.schedule_parts))
            schedule.itemDefaults.forEach { ItemRow(it, fmt, schedule.currency) }
        }
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        val calendar = item.dueAt?.let { due -> onAddToCalendar?.let { fmt.date(due) to it } }
        if (onLogService != null || onEdit != null || onDelete != null || calendar != null) {
            SheetActions(
                item.vehicle,
                primary = onLogService?.let { stringResource(R.string.action_log_service) to it },
                secondary = onEditDefaults?.let { stringResource(R.string.schedule_defaults_action) to it },
                onEdit = onEdit,
                onDelete = onDelete?.let { { confirmDelete = true } },
                deleteLabel = stringResource(R.string.schedule_deactivate),
                calendar = calendar,
            )
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.schedule_deactivate_title, item.name),
            body = stringResource(R.string.schedule_deactivate_body),
            confirmLabel = stringResource(R.string.schedule_deactivate),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
}

@Composable
fun RecordSheet(
    record: MaintenanceRecord,
    name: String,
    vehicle: Vehicle?,
    fmt: Formatter,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** Opens the camera for a receipt that is filed under this record (D-A23). */
    onAttachReceipt: (() -> Unit)? = null,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    DetailSheet(
        Icons.Outlined.Build,
        name,
        listOfNotNull(record.performedAt.toInstantOrNull()?.let(fmt::date), vehicle?.displayName).joinToString(" · "),
        onDismiss,
    ) {
        DetailRow(stringResource(R.string.field_cost), fmt.money(record.costCents, record.currency))
        DetailRow(stringResource(R.string.field_odometer), record.odometerKm?.let(fmt::distance))
        DetailRow(stringResource(R.string.field_vendor), record.vendorName)
        DetailRow(stringResource(R.string.field_notes), record.note)
        if (record.items.isNotEmpty()) {
            GroupHeader(pluralStringResource(R.plurals.record_items, record.items.size, record.items.size))
            record.items.forEach { ItemRow(it, fmt, record.currency) }
        }
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (onEdit != null || onDelete != null || onAttachReceipt != null) {
            SheetActions(
                vehicle,
                secondary = onAttachReceipt?.let { stringResource(R.string.action_attach_receipt) to it },
                onEdit = onEdit,
                onDelete = onDelete?.let { { confirmDelete = true } },
            )
        }
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirm(
            title = stringResource(R.string.record_delete_title),
            body = stringResource(R.string.record_delete_body),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
fun FuelSheet(
    entry: FuelEntry,
    vehicle: Vehicle?,
    segment: FuelSegment?,
    fmt: Formatter,
    onDismiss: () -> Unit,
    pending: Boolean = false,
    onDelete: (() -> Unit)? = null,
    /** False hides the odometer, distance and consumption rows (Settings → Fuel → Track fuel economy, D-A25). */
    showEconomy: Boolean = true,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    DetailSheet(
        Icons.Outlined.LocalGasStation,
        fmt.money(entry.totalCostCents, entry.currency),
        listOfNotNull(entry.filledAt.toInstantOrNull()?.let(fmt::date), vehicle?.displayName).joinToString(" · "),
        onDismiss,
    ) {
        DetailRow(stringResource(R.string.fuel_station), entry.stationName)
        DetailRow(stringResource(R.string.fuel_volume), entry.liters?.let(fmt::volume))
        DetailRow(stringResource(R.string.fuel_unit_price), entry.liters?.let { fmt.unitPrice(entry.totalCostCents, it, entry.currency) })
        if (showEconomy) {
            DetailRow(stringResource(R.string.field_odometer), entry.odometerKm?.let(fmt::distance))
            DetailRow(stringResource(R.string.fuel_distance), segment?.let { fmt.distance(it.distanceKm) })
            DetailRow(stringResource(R.string.fuel_economy), segment?.let { fmt.economy(it.litersPer100Km) })
        }
        DetailRow(stringResource(R.string.field_notes), entry.note)
        if (pending) PendingMark(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (onDelete != null) {
            SheetActions(vehicle, onDelete = { confirmDelete = true })
        }
    }
    if (confirmDelete && onDelete != null) {
        DeleteConfirm(
            title = stringResource(R.string.fuel_delete_title),
            body = stringResource(R.string.fuel_delete_body),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A part or fluid: name, then brand, part number and specification; quantity and cost on the right. */
@Composable
private fun ItemRow(item: MaintenanceItem, fmt: Formatter, currency: String) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.name ?: item.productName ?: item.category.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                item.brand,
                item.productName.takeIf { item.name != null },
                item.partNumber,
                item.specification,
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
            item.costCents?.let { Text(fmt.money(it, currency), style = MaterialTheme.typography.bodyLarge) }
            item.quantity?.let { quantity ->
                Text(
                    listOfNotNull(NumberFormat.getNumberInstance().format(quantity), item.customUnit ?: item.unit?.let { unitLabel(it) }).joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Every 5,000 mi or 6 months". */
@Composable
fun intervalText(km: Int?, months: Int?, fmt: Formatter): String? {
    val distance = km?.let(fmt::distance)
    val time = months?.let { pluralStringResource(R.plurals.interval_months, it, it) }
    return when {
        distance != null && time != null -> stringResource(R.string.interval_both, distance, time)
        distance != null -> stringResource(R.string.interval_one, distance)
        time != null -> stringResource(R.string.interval_one, time)
        else -> null
    }
}

/** Short label for a `MaintenanceItemUnit`. */
@Composable
fun unitLabel(unit: String): String = when (unit) {
    "LITERS" -> "L"
    "MILLILITERS" -> "ml"
    "QUARTS" -> "qt"
    "GALLONS" -> "gal"
    "FLUID_OUNCES" -> "fl oz"
    "PIECES" -> stringResource(R.string.unit_pieces)
    "SETS" -> stringResource(R.string.unit_sets)
    "KG" -> "kg"
    "GRAMS" -> "g"
    "POUNDS" -> "lb"
    "OUNCES" -> "oz"
    else -> unit.lowercase()
}

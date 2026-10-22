package io.github.conchad.smartgarage.feature.sections

import android.text.format.Formatter as FileSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Reminder
import io.github.conchad.smartgarage.core.model.TimelineEvent
import io.github.conchad.smartgarage.core.model.Urgency
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.DetailRow
import io.github.conchad.smartgarage.ui.components.DetailSheet
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.RecordSheet
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.shortName
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.dueText
import io.github.conchad.smartgarage.ui.components.documentCategoryLabel
import io.github.conchad.smartgarage.ui.components.expenseCategoryLabel
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.wishlistCategoryLabel
import io.github.conchad.smartgarage.ui.components.wishlistStatusLabel
import io.github.conchad.smartgarage.ui.navigation.Section
import java.time.YearMonth
import java.time.ZoneId

@Composable
fun SectionScreen(section: Section, onBack: () -> Unit, onOpenVehicle: (String) -> Unit) {
    when (section) {
        Section.Reminders -> RemindersScreen(onBack, onOpenVehicle)
        Section.History -> HistoryScreen(onBack)
        Section.Odometer -> OdometerScreen(onBack)
        Section.Expenses -> ExpensesScreen(onBack)
        Section.Documents -> DocumentsScreen(onBack)
        Section.Notes -> NotesScreen(onBack)
        Section.Wishlist -> WishlistScreen(onBack)
    }
}

// Reminders ------------------------------------------------------------------------------------------------------

@Composable
private fun RemindersScreen(onBack: () -> Unit, onOpenVehicle: (String) -> Unit, viewModel: RemindersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var openSchedule by rememberSaveable { mutableStateOf<String?>(null) }
    SectionScaffold(
        title = stringResource(R.string.nav_reminders),
        onBack = onBack,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty(),
        emptyIcon = Icons.Outlined.NotificationsActive,
        emptyTitle = stringResource(R.string.reminders_empty_title),
        emptyBody = stringResource(R.string.reminders_empty_body),
    ) {
        state.data.groupBy { it.urgency }.forEach { (urgency, reminders) ->
            item(key = urgency.name) {
                GroupHeader(
                    when (urgency) {
                        Urgency.OVERDUE -> stringResource(R.string.status_overdue)
                        Urgency.SOON -> stringResource(R.string.reminders_soon)
                        Urgency.LATER -> stringResource(R.string.maintenance_later)
                    },
                )
            }
            item(key = "${urgency.name}-list") {
                GroupCard {
                    reminders.forEachIndexed { index, reminder ->
                        if (index > 0) RowDivider()
                        when (reminder) {
                            is Reminder.Service -> SectionRow(
                                icon = Icons.Outlined.Build,
                                title = reminder.item.name,
                                detail = listOfNotNull(dueText(reminder.item, fmt), reminder.vehicle.shortName).joinToString(" · "),
                                onClick = { openSchedule = reminder.item.schedule.id },
                            )
                            is Reminder.InspectionDue -> SectionRow(
                                icon = Icons.Outlined.EventAvailable,
                                title = inspectionLabel(reminder.inspection.type),
                                detail = listOfNotNull(reminder.date?.let { dueDays(fmt.daysFromToday(it).toInt()) }, reminder.vehicle.shortName)
                                    .joinToString(" · "),
                                onClick = { onOpenVehicle(reminder.vehicle.id) },
                            )
                            is Reminder.InsuranceEnds -> SectionRow(
                                icon = Icons.Outlined.Shield,
                                title = stringResource(
                                    if (reminder.policy.autoRenew) R.string.reminders_insurance_renews else R.string.reminders_insurance_ends,
                                    reminder.policy.provider,
                                ),
                                detail = listOfNotNull(reminder.date?.let { dueDays(fmt.daysFromToday(it).toInt()) }, reminder.vehicle.shortName)
                                    .joinToString(" · "),
                                onClick = { onOpenVehicle(reminder.vehicle.id) },
                            )
                        }
                    }
                }
            }
        }
    }
    openSchedule?.let { id ->
        state.data.filterIsInstance<Reminder.Service>().firstOrNull { it.item.schedule.id == id }?.let {
            ScheduleSheet(it.item, fmt, onDismiss = { openSchedule = null })
        }
    }
}

// History --------------------------------------------------------------------------------------------------------

@Composable
private fun HistoryScreen(onBack: () -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    SectionScaffold(
        title = stringResource(R.string.nav_history),
        onBack = onBack,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.events.isEmpty(),
        emptyIcon = Icons.Outlined.History,
        emptyTitle = stringResource(R.string.history_empty_title),
        emptyBody = stringResource(R.string.history_empty_body),
    ) {
        byMonth(state.data.events, fmt.zone) { it.at }.forEach { (month, events) ->
            item(key = month.toString()) { GroupHeader(monthLabel(month)) }
            item(key = "$month-list") {
                GroupCard {
                    events.forEachIndexed { index, event ->
                        if (index > 0) RowDivider()
                        val vehicle = state.vehicleById[event.vehicleId]?.shortName?.takeIf { showVehicle }
                        val date = fmt.shortDate(event.at)
                        when (event) {
                            is TimelineEvent.Service -> SectionRow(
                                icon = Icons.Outlined.Build,
                                title = state.data.recordNames[event.record.id] ?: stringResource(R.string.record_untitled),
                                detail = listOfNotNull(date, vehicle, event.record.vendorName).joinToString(" · "),
                                onClick = { open = event.id },
                                trailing = fmt.money(event.record.costCents, event.record.currency),
                            )
                            is TimelineEvent.Fuel -> SectionRow(
                                icon = Icons.Outlined.LocalGasStation,
                                title = stringResource(R.string.history_fill_up),
                                detail = listOfNotNull(date, vehicle, event.entry.stationName).joinToString(" · "),
                                onClick = { open = event.id },
                                trailing = fmt.money(event.entry.totalCostCents, event.entry.currency),
                                trailingDetail = event.entry.liters?.let(fmt::volume),
                            )
                            is TimelineEvent.Cost -> SectionRow(
                                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                                title = event.expense.description?.takeIf { it.isNotBlank() } ?: expenseCategoryLabel(event.expense.category),
                                detail = listOfNotNull(date, vehicle, expenseCategoryLabel(event.expense.category)).joinToString(" · "),
                                onClick = null,
                                trailing = fmt.money(event.expense.amountCents, event.expense.currency),
                            )
                            is TimelineEvent.Odometer -> SectionRow(
                                icon = Icons.Outlined.Speed,
                                title = stringResource(R.string.history_odometer, fmt.distance(event.log.odometerKm)),
                                detail = listOfNotNull(date, vehicle, event.log.note).joinToString(" · "),
                                onClick = null,
                            )
                        }
                    }
                }
            }
        }
    }
    open?.let { id ->
        when (val event = state.data.events.firstOrNull { it.id == id }) {
            is TimelineEvent.Service -> RecordSheet(
                event.record,
                state.data.recordNames[event.record.id] ?: stringResource(R.string.record_untitled),
                state.vehicleById[event.vehicleId],
                fmt,
                onDismiss = { open = null },
            )
            is TimelineEvent.Fuel -> FuelSheet(event.entry, state.vehicleById[event.vehicleId], state.data.segments[event.entry.id], fmt, onDismiss = { open = null })
            else -> Unit
        }
    }
}

// Odometer -------------------------------------------------------------------------------------------------------

@Composable
private fun OdometerScreen(onBack: () -> Unit, viewModel: OdometerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    SectionScaffold(
        title = stringResource(R.string.nav_odometer),
        onBack = onBack,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.logs.isEmpty(),
        emptyIcon = Icons.Outlined.Speed,
        emptyTitle = stringResource(R.string.odometer_empty_title),
        emptyBody = stringResource(R.string.odometer_empty_body),
    ) {
        byMonth(state.data.logs, fmt.zone) { it.recordedAt.toInstantOrNull() }.forEach { (month, logs) ->
            item(key = month.toString()) { GroupHeader(monthLabel(month)) }
            item(key = "$month-list") {
                GroupCard {
                    logs.forEachIndexed { index, log ->
                        if (index > 0) RowDivider()
                        SectionRow(
                            icon = Icons.Outlined.Speed,
                            title = fmt.distance(log.odometerKm),
                            detail = listOfNotNull(
                                log.recordedAt.toInstantOrNull()?.let(fmt::shortDate),
                                state.vehicleById[log.vehicleId]?.shortName?.takeIf { showVehicle },
                                log.note,
                            ).joinToString(" · "),
                            onClick = null,
                            trailing = state.data.deltas[log.id]?.let { delta ->
                                if (delta >= 0) "+${fmt.distance(delta)}" else "−${fmt.distance(-delta)}"
                            },
                        )
                    }
                }
            }
        }
    }
}

// Expenses -------------------------------------------------------------------------------------------------------

@Composable
private fun ExpensesScreen(onBack: () -> Unit, viewModel: ExpensesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    SectionScaffold(
        title = stringResource(R.string.nav_expenses),
        onBack = onBack,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty(),
        emptyIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
        emptyTitle = stringResource(R.string.expenses_empty_title),
        emptyBody = stringResource(R.string.expenses_empty_body),
    ) {
        byMonth(state.data, fmt.zone) { it.occurredAt.toInstantOrNull() }.forEach { (month, expenses) ->
            item(key = month.toString()) {
                GroupHeader("${monthLabel(month)} · ${fmt.money(expenses.sumOf { it.amountCents }, expenses.first().currency)}")
            }
            item(key = "$month-list") {
                GroupCard {
                    expenses.forEachIndexed { index, expense ->
                        if (index > 0) RowDivider()
                        val category = expenseCategoryLabel(expense.category)
                        SectionRow(
                            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                            title = expense.description?.takeIf { it.isNotBlank() } ?: category,
                            detail = listOfNotNull(
                                expense.occurredAt.toInstantOrNull()?.let(fmt::shortDate),
                                category.takeIf { !expense.description.isNullOrBlank() },
                                state.vehicleById[expense.vehicleId]?.shortName?.takeIf { showVehicle },
                            ).joinToString(" · "),
                            onClick = null,
                            trailing = fmt.money(expense.amountCents, expense.currency),
                        )
                    }
                }
            }
        }
    }
}

// Documents ------------------------------------------------------------------------------------------------------

@Composable
private fun DocumentsScreen(onBack: () -> Unit, viewModel: DocumentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val open by viewModel.open.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val context = LocalContext.current
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    SectionScaffold(
        title = stringResource(R.string.nav_documents),
        onBack = onBack,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty(),
        emptyIcon = Icons.Outlined.Description,
        emptyTitle = stringResource(R.string.documents_empty_title),
        emptyBody = stringResource(R.string.documents_empty_body),
    ) {
        item { GroupHeader(pluralStringResource(R.plurals.documents_count, state.data.size, state.data.size)) }
        item {
            GroupCard {
                state.data.forEachIndexed { index, document ->
                    if (index > 0) RowDivider()
                    val loading = (open as? DocumentOpen.Loading)?.documentId == document.id
                    SectionRow(
                        icon = when {
                            document.isImage -> Icons.Outlined.Image
                            document.mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
                            else -> Icons.Outlined.Description
                        },
                        title = document.displayName,
                        detail = listOfNotNull(
                            document.category?.let { documentCategoryLabel(it) }
                                ?: stringResource(R.string.documents_vehicle_photo).takeIf { document.purpose == "VEHICLE_IMAGE" },
                            state.vehicleById[document.vehicleId]?.shortName?.takeIf { showVehicle },
                            document.createdAt.toInstantOrNull()?.let(fmt::shortDate),
                            FileSize.formatShortFileSize(context, document.sizeBytes),
                        ).joinToString(" · "),
                        onClick = { viewModel.open(document) },
                        trailing = if (loading) "…" else null,
                    )
                }
            }
        }
    }
    when (val current = open) {
        is DocumentOpen.Image -> ImageViewer(current, onDismiss = viewModel::dismiss)
        DocumentOpen.Failed -> InfoDialog(stringResource(R.string.documents_failed), viewModel::dismiss)
        DocumentOpen.NoViewer -> InfoDialog(stringResource(R.string.documents_no_viewer), viewModel::dismiss)
        else -> Unit
    }
}

@Composable
private fun ImageViewer(image: DocumentOpen.Image, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = image.file,
                contentDescription = image.document.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 8.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }
        }
    }
}

@Composable
private fun InfoDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        text = { Text(text) },
    )
}

// Notes ----------------------------------------------------------------------------------------------------------

@Composable
private fun NotesScreen(onBack: () -> Unit, viewModel: NotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    SectionScaffold(
        title = stringResource(R.string.nav_notes),
        onBack = onBack,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty(),
        emptyIcon = Icons.AutoMirrored.Outlined.StickyNote2,
        emptyTitle = stringResource(R.string.notes_empty_title),
        emptyBody = stringResource(R.string.notes_empty_body),
    ) {
        state.data.groupBy { it.isPinned }.toSortedMap(compareByDescending { it }).forEach { (pinned, notes) ->
            item(key = "p$pinned") { GroupHeader(stringResource(if (pinned) R.string.notes_pinned else R.string.notes_all)) }
            item(key = "p$pinned-list") {
                GroupCard {
                    notes.forEachIndexed { index, note ->
                        if (index > 0) RowDivider()
                        SectionRow(
                            icon = if (note.isPinned) Icons.Outlined.PushPin else Icons.AutoMirrored.Outlined.StickyNote2,
                            title = note.title,
                            detail = note.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
                            onClick = { open = note.id },
                            trailingDetail = note.updatedAt.toInstantOrNull()?.let(fmt::shortDate),
                        )
                    }
                }
            }
        }
    }
    state.data.firstOrNull { it.id == open }?.let { note ->
        DetailSheet(
            Icons.AutoMirrored.Outlined.StickyNote2,
            note.title,
            listOfNotNull(state.vehicleById[note.vehicleId]?.displayName, note.updatedAt.toInstantOrNull()?.let(fmt::date)).joinToString(" · "),
            onDismiss = { open = null },
        ) {
            if (note.tags.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    note.tags.forEach { tag ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                            Text("#$tag", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
            Text(note.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
        }
    }
}

// Wishlist -------------------------------------------------------------------------------------------------------

private val wishlistOrder = listOf("PLANNED", "ORDERED", "IDEA", "DONE")

@Composable
private fun WishlistScreen(onBack: () -> Unit, viewModel: WishlistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    SectionScaffold(
        title = stringResource(R.string.nav_wishlist),
        onBack = onBack,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty(),
        emptyIcon = Icons.Outlined.ShoppingBag,
        emptyTitle = stringResource(R.string.wishlist_empty_title),
        emptyBody = stringResource(R.string.wishlist_empty_body),
    ) {
        state.data.groupBy { it.status }
            .toSortedMap(compareBy { wishlistOrder.indexOf(it).let { i -> if (i < 0) wishlistOrder.size else i } })
            .forEach { (status, items) ->
                item(key = status) { GroupHeader("${wishlistStatusLabel(status)} · ${items.size}") }
                item(key = "$status-list") {
                    GroupCard {
                        items.forEachIndexed { index, item ->
                            if (index > 0) RowDivider()
                            SectionRow(
                                icon = Icons.Outlined.ShoppingBag,
                                title = item.title,
                                detail = listOfNotNull(
                                    wishlistCategoryLabel(item.category),
                                    state.vehicleById[item.vehicleId]?.shortName?.takeIf { showVehicle },
                                    item.targetDate.toInstantOrNull()?.let(fmt::shortDate),
                                ).joinToString(" · "),
                                onClick = { open = item.id },
                                trailing = item.estimatedCostCents?.let { estimate(fmt, it, item.currency) },
                            )
                        }
                    }
                }
            }
    }
    state.data.firstOrNull { it.id == open }?.let { item ->
        val uriHandler = LocalUriHandler.current
        DetailSheet(
            Icons.Outlined.ShoppingBag,
            item.title,
            listOfNotNull(wishlistStatusLabel(item.status), state.vehicleById[item.vehicleId]?.displayName).joinToString(" · "),
            onDismiss = { open = null },
        ) {
            DetailRow(stringResource(R.string.wishlist_category), wishlistCategoryLabel(item.category))
            DetailRow(stringResource(R.string.wishlist_estimate), item.estimatedCostCents?.let { estimate(fmt, it, item.currency) })
            DetailRow(stringResource(R.string.wishlist_target), item.targetDate.toInstantOrNull()?.let(fmt::date))
            DetailRow(stringResource(R.string.field_notes), item.description)
            item.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { url ->
                FilledTonalButton(
                    onClick = { runCatching { uriHandler.openUri(url) } },
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.wishlist_open_link), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Estimates are usually round numbers; "$2,500" reads better than "$2,500.00". */
private fun estimate(fmt: Formatter, cents: Long, currency: String): String =
    fmt.money(cents / 100.0, currency, fractionDigits = if (cents % 100 == 0L) 0 else null)

/** Groups newest-first items by calendar month in the server's time zone, keeping their order. */
private fun <T> byMonth(items: List<T>, zone: ZoneId, at: (T) -> java.time.Instant?): Map<YearMonth, List<T>> =
    items.groupBy { item -> at(item)?.atZone(zone)?.let(YearMonth::from) ?: YearMonth.of(1970, 1) }

package io.github.conchad.smartgarage.feature.sections

import android.text.format.Formatter as FileSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.ChartPoint
import io.github.conchad.smartgarage.core.model.Reminder
import io.github.conchad.smartgarage.core.model.TimelineEvent
import io.github.conchad.smartgarage.core.model.Urgency
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.DocumentSheet
import io.github.conchad.smartgarage.ui.components.ExpenseSheet
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.NoteSheet
import io.github.conchad.smartgarage.ui.components.RecordSheet
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.SearchField
import io.github.conchad.smartgarage.ui.components.WishlistSheet
import io.github.conchad.smartgarage.ui.components.charts.BarChart
import io.github.conchad.smartgarage.ui.components.charts.ChartPlaceholder
import io.github.conchad.smartgarage.ui.components.charts.ChartTitle
import io.github.conchad.smartgarage.ui.components.charts.barSummary
import io.github.conchad.smartgarage.ui.components.documentCategoryLabel
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.dueText
import io.github.conchad.smartgarage.ui.components.expenseCategoryLabel
import io.github.conchad.smartgarage.feature.fuel.ValueChart
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.components.inspectionLabel
import io.github.conchad.smartgarage.ui.components.shortName
import io.github.conchad.smartgarage.ui.components.wishlistCategoryLabel
import io.github.conchad.smartgarage.ui.components.wishlistEstimate
import io.github.conchad.smartgarage.ui.components.wishlistStatusLabel
import io.github.conchad.smartgarage.ui.navigation.Section
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where the sections send the user: the vehicle screen and the editors (CP11 phase B). */
class SectionActions(
    val onOpenVehicle: (vehicleId: String) -> Unit,
    val onAddSchedule: (vehicleId: String?) -> Unit,
    val onEditSchedule: (scheduleId: String) -> Unit,
    val onEditScheduleDefaults: (scheduleId: String) -> Unit,
    val onLogService: (scheduleId: String) -> Unit,
    val onAddExpense: (vehicleId: String?) -> Unit,
    val onAddDocument: (vehicleId: String?) -> Unit,
    /** The camera, pre-linked to a service record (D-A23). */
    val onAttachReceipt: (vehicleId: String, recordId: String) -> Unit,
    val onNewNote: (vehicleId: String?) -> Unit,
    val onEditNote: (noteId: String) -> Unit,
    val onAddWishlistItem: (vehicleId: String?) -> Unit,
    val onEditWishlistItem: (itemId: String) -> Unit,
)

@Composable
fun SectionScreen(section: Section, onBack: () -> Unit, actions: SectionActions) {
    when (section) {
        Section.Reminders -> RemindersScreen(onBack, actions)
        Section.History -> HistoryScreen(onBack, actions)
        Section.Odometer -> OdometerScreen(onBack)
        Section.Expenses -> ExpensesScreen(onBack, actions)
        Section.Documents -> DocumentsScreen(onBack, actions)
        Section.Notes -> NotesScreen(onBack, actions)
        Section.Wishlist -> WishlistScreen(onBack, actions)
    }
}

// Reminders ------------------------------------------------------------------------------------------------------

@Composable
private fun RemindersScreen(onBack: () -> Unit, actions: SectionActions, viewModel: RemindersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var openSchedule by rememberSaveable { mutableStateOf<String?>(null) }
    val addSchedule = { actions.onAddSchedule(state.selectedVehicleId) }
    SectionScaffold(
        title = stringResource(R.string.nav_reminders),
        onBack = onBack,
        viewModel = viewModel,
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
        actions = {
            // A schedule is a setting, not a log entry, so it hangs off the bar rather than a FAB (D-A20).
            if (state.loaded && state.canEditAny) {
                IconButton(onClick = addSchedule) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.action_add_schedule))
                }
            }
        },
        emptyAction = if (state.loaded && state.canEditAny) {
            { Button(onClick = addSchedule) { Text(stringResource(R.string.action_add_schedule)) } }
        } else {
            null
        },
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
                                pending = reminder.item.schedule.id in pendingIds,
                            )
                            is Reminder.InspectionDue -> SectionRow(
                                icon = Icons.Outlined.EventAvailable,
                                title = inspectionLabel(reminder.inspection.type),
                                detail = listOfNotNull(reminder.date?.let { dueDays(fmt.daysFromToday(it).toInt()) }, reminder.vehicle.shortName)
                                    .joinToString(" · "),
                                onClick = { actions.onOpenVehicle(reminder.vehicle.id) },
                                pending = reminder.inspection.id in pendingIds,
                            )
                            is Reminder.InsuranceEnds -> SectionRow(
                                icon = Icons.Outlined.Shield,
                                title = stringResource(
                                    if (reminder.policy.autoRenew) R.string.reminders_insurance_renews else R.string.reminders_insurance_ends,
                                    reminder.policy.provider,
                                ),
                                detail = listOfNotNull(reminder.date?.let { dueDays(fmt.daysFromToday(it).toInt()) }, reminder.vehicle.shortName)
                                    .joinToString(" · "),
                                onClick = { actions.onOpenVehicle(reminder.vehicle.id) },
                                pending = reminder.policy.id in pendingIds,
                            )
                        }
                    }
                }
            }
        }
    }
    openSchedule?.let { id ->
        state.data.filterIsInstance<Reminder.Service>().firstOrNull { it.item.schedule.id == id }?.let { reminder ->
            val item = reminder.item
            ScheduleSheet(
                item,
                fmt,
                onDismiss = { openSchedule = null },
                onLogService = {
                    openSchedule = null
                    actions.onLogService(item.schedule.id)
                },
                pending = item.schedule.id in pendingIds,
                onEdit = {
                    openSchedule = null
                    actions.onEditSchedule(item.schedule.id)
                },
                onEditDefaults = {
                    openSchedule = null
                    actions.onEditScheduleDefaults(item.schedule.id)
                },
                onDelete = {
                    openSchedule = null
                    viewModel.deleteSchedule(item.schedule.id)
                },
            )
        }
    }
}

// History --------------------------------------------------------------------------------------------------------

@Composable
private fun HistoryScreen(onBack: () -> Unit, actions: SectionActions, viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    val searching = state.query.isNotBlank() && state.data.unfiltered > 0
    SectionScaffold(
        title = stringResource(R.string.nav_history),
        onBack = onBack,
        viewModel = viewModel,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.events.isEmpty(),
        emptyIcon = if (searching) Icons.Outlined.Search else Icons.Outlined.History,
        emptyTitle = stringResource(if (searching) R.string.search_no_matches_title else R.string.history_empty_title),
        emptyBody = stringResource(if (searching) R.string.search_no_matches_body else R.string.history_empty_body),
        filters = {
            if (state.loaded && state.data.unfiltered > 0 || state.query.isNotEmpty()) {
                SearchField(state.query, viewModel::search, stringResource(R.string.search_history_hint), Modifier.padding(bottom = 8.dp))
            }
        },
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
                                pending = event.record.id in pendingIds,
                            )
                            is TimelineEvent.Fuel -> SectionRow(
                                icon = Icons.Outlined.LocalGasStation,
                                title = stringResource(R.string.history_fill_up),
                                detail = listOfNotNull(date, vehicle, event.entry.stationName).joinToString(" · "),
                                onClick = { open = event.id },
                                trailing = fmt.money(event.entry.totalCostCents, event.entry.currency),
                                trailingDetail = event.entry.liters?.let(fmt::volume),
                                pending = event.entry.id in pendingIds,
                            )
                            is TimelineEvent.Cost -> SectionRow(
                                icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                                title = event.expense.description?.takeIf { it.isNotBlank() } ?: expenseCategoryLabel(event.expense.category),
                                detail = listOfNotNull(date, vehicle, expenseCategoryLabel(event.expense.category)).joinToString(" · "),
                                onClick = null,
                                trailing = fmt.money(event.expense.amountCents, event.expense.currency),
                                pending = event.expense.id in pendingIds,
                            )
                            is TimelineEvent.Odometer -> SectionRow(
                                icon = Icons.Outlined.Speed,
                                title = stringResource(R.string.history_odometer, fmt.distance(event.log.odometerKm)),
                                detail = listOfNotNull(date, vehicle, event.log.note).joinToString(" · "),
                                onClick = null,
                                pending = event.log.id in pendingIds,
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
                pending = event.record.id in pendingIds,
                onAttachReceipt = {
                    open = null
                    actions.onAttachReceipt(event.vehicleId, event.record.id)
                }.takeIf { state.vehicleById[event.vehicleId]?.access?.canEdit == true },
            )
            is TimelineEvent.Fuel -> FuelSheet(
                event.entry,
                state.vehicleById[event.vehicleId],
                state.data.segments[event.entry.id],
                fmt,
                onDismiss = { open = null },
                pending = event.entry.id in pendingIds,
            )
            else -> Unit
        }
    }
}

// Odometer -------------------------------------------------------------------------------------------------------

@Composable
private fun OdometerScreen(onBack: () -> Unit, viewModel: OdometerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    SectionScaffold(
        title = stringResource(R.string.nav_odometer),
        onBack = onBack,
        viewModel = viewModel,
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
        item(key = "chart") { OdometerChart(state.data.series, state.vehicles.size, fmt) }
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
                            pending = log.id in pendingIds,
                        )
                    }
                }
            }
        }
    }
}

/** The web's "Odometer over time" (D-A23): the one vehicle's readings in the display unit; a line needs two of them. */
@Composable
private fun OdometerChart(series: List<ChartPoint>?, vehicleCount: Int, fmt: Formatter) {
    val title = stringResource(R.string.odometer_chart_readings)
    Column {
        GroupHeader(title)
        GroupCard {
            when {
                series == null -> ChartPlaceholder(stringResource(if (vehicleCount > 1) R.string.chart_pick_vehicle else R.string.chart_needs_readings))
                series.size < 2 -> ChartPlaceholder(stringResource(R.string.chart_needs_readings))
                else -> {
                    val points = series.map { it.copy(value = fmt.distanceValue(it.value)) }
                    ValueChart(points, fmt, title, format = fmt::displayDistance)
                }
            }
        }
    }
}

// Expenses -------------------------------------------------------------------------------------------------------

@Composable
private fun ExpensesScreen(onBack: () -> Unit, actions: SectionActions, viewModel: ExpensesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    val add = { actions.onAddExpense(state.selectedVehicleId) }
    SectionScaffold(
        title = stringResource(R.string.nav_expenses),
        onBack = onBack,
        viewModel = viewModel,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty,
        emptyIcon = Icons.AutoMirrored.Outlined.ReceiptLong,
        emptyTitle = stringResource(R.string.expenses_empty_title),
        emptyBody = stringResource(R.string.expenses_empty_body),
        floatingActionButton = {
            if (state.loaded && state.canEditAny) AddFab(stringResource(R.string.action_add_expense), onClick = add)
        },
    ) {
        item(key = "overview") { ExpensesOverview(state.data, fmt) }
        byMonth(state.data.expenses, fmt.zone) { it.occurredAt.toInstantOrNull() }.forEach { (month, expenses) ->
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
                            onClick = { open = expense.id },
                            trailing = fmt.money(expense.amountCents, expense.currency),
                            pending = expense.id in pendingIds,
                        )
                    }
                }
            }
        }
    }
    state.data.expenses.firstOrNull { it.id == open }?.let { expense ->
        ExpenseSheet(
            expense,
            state.vehicleById[expense.vehicleId],
            fmt,
            onDismiss = { open = null },
            pending = expense.id in pendingIds,
            onDelete = {
                open = null
                viewModel.deleteExpense(expense.id)
            },
        )
    }
}

/**
 * The web's cost dashboard (D-A23): a bar per month for the last twelve, and the same window split by category with
 * a proportional bar each. Money is shown in the currency most of the expenses use.
 */
@Composable
private fun ExpensesOverview(data: ExpensesData, fmt: Formatter) {
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLL", Locale.getDefault()) }
    val money: (Double) -> String = { fmt.money((it * 100).toLong(), data.currency) }
    val trendTitle = stringResource(R.string.expenses_chart_monthly)
    Column {
        GroupHeader(stringResource(R.string.expenses_overview))
        GroupCard {
            ChartTitle(trendTitle, detail = pluralStringResource(R.plurals.expenses_last_months, data.months.size, data.months.size))
            if (data.months.all { it.cents == 0L }) {
                ChartPlaceholder(stringResource(R.string.chart_needs_expenses))
            } else {
                val labels = data.months.map { it.month.format(monthFormat).replaceFirstChar { c -> c.uppercase() } }
                val values = data.months.map { it.cents / 100.0 }
                BarChart(
                    values = values,
                    labels = labels,
                    formatValue = money,
                    summary = barSummary(trendTitle, labels, values, money),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
            if (data.categories.isNotEmpty()) {
                ChartTitle(stringResource(R.string.expenses_by_category))
                val total = data.categories.sumOf { it.cents }.coerceAtLeast(1)
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    data.categories.forEach { row ->
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(expenseCategoryLabel(row.category), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Text(fmt.money(row.cents, data.currency), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(Modifier.fillMaxWidth().height(6.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.fillMaxWidth(fraction = (row.cents.toFloat() / total).coerceIn(0.02f, 1f)).height(6.dp),
                                ) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

// Documents ------------------------------------------------------------------------------------------------------

@Composable
private fun DocumentsScreen(onBack: () -> Unit, actions: SectionActions, viewModel: DocumentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val opening by viewModel.open.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    val add = { actions.onAddDocument(state.selectedVehicleId) }
    SectionScaffold(
        title = stringResource(R.string.nav_documents),
        onBack = onBack,
        viewModel = viewModel,
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
        floatingActionButton = {
            if (state.loaded && state.canEditAny) {
                AddFab(stringResource(R.string.action_add_document), onClick = add, icon = Icons.Outlined.UploadFile)
            }
        },
    ) {
        item { GroupHeader(pluralStringResource(R.plurals.documents_count, state.data.size, state.data.size)) }
        item {
            GroupCard {
                state.data.forEachIndexed { index, document ->
                    if (index > 0) RowDivider()
                    val loading = (opening as? DocumentOpen.Loading)?.documentId == document.id
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
                        onClick = { open = document.id },
                        trailing = if (loading) "…" else null,
                        pending = document.id in pendingIds,
                    )
                }
            }
        }
    }
    state.data.firstOrNull { it.id == open }?.let { document ->
        DocumentSheet(
            document,
            state.vehicleById[document.vehicleId],
            fmt,
            onDismiss = { open = null },
            onOpen = {
                open = null
                viewModel.open(document)
            },
            pending = document.id in pendingIds,
            onDelete = {
                open = null
                viewModel.deleteDocument(document.id)
            },
        )
    }
    when (val current = opening) {
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
private fun NotesScreen(onBack: () -> Unit, actions: SectionActions, viewModel: NotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val add = { actions.onNewNote(state.selectedVehicleId) }
    val narrowed = (state.query.isNotBlank() || state.data.selectedTag != null) && state.data.unfiltered > 0
    SectionScaffold(
        title = stringResource(R.string.nav_notes),
        onBack = onBack,
        viewModel = viewModel,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty,
        emptyIcon = if (narrowed) Icons.Outlined.Search else Icons.AutoMirrored.Outlined.StickyNote2,
        emptyTitle = stringResource(if (narrowed) R.string.search_no_matches_title else R.string.notes_empty_title),
        emptyBody = stringResource(if (narrowed) R.string.search_no_matches_body else R.string.notes_empty_body),
        floatingActionButton = { if (state.loaded) AddFab(stringResource(R.string.action_new_note), onClick = add) },
        filters = {
            if (state.loaded && state.data.unfiltered > 0 || state.query.isNotEmpty()) {
                SearchField(state.query, viewModel::search, stringResource(R.string.search_notes_hint))
            }
            if (state.data.tags.isNotEmpty()) {
                // Every tag in view; one at a time, tapping the active one clears it.
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                ) {
                    items(state.data.tags, key = { it }) { tag ->
                        val selected = state.data.selectedTag == tag
                        FilterChip(selected = selected, onClick = { viewModel.selectTag(if (selected) null else tag) }, label = { Text("#$tag", maxLines = 1) })
                    }
                }
            }
        },
    ) {
        state.data.notes.groupBy { it.isPinned }.toSortedMap(compareByDescending { it }).forEach { (pinned, notes) ->
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
                            pending = note.id in pendingIds,
                        )
                    }
                }
            }
        }
    }
    state.data.notes.firstOrNull { it.id == open }?.let { note ->
        NoteSheet(
            note,
            state.vehicleById[note.vehicleId],
            fmt,
            onDismiss = { open = null },
            pending = note.id in pendingIds,
            onTogglePin = {
                open = null
                viewModel.setPinned(note, !note.isPinned)
            },
            onEdit = {
                open = null
                actions.onEditNote(note.id)
            },
            onDelete = {
                open = null
                viewModel.deleteNote(note.id)
            },
        )
    }
}

// Wishlist -------------------------------------------------------------------------------------------------------

private val wishlistOrder = listOf("PLANNED", "ORDERED", "IDEA", "DONE")

@Composable
private fun WishlistScreen(onBack: () -> Unit, actions: SectionActions, viewModel: WishlistViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    val add = { actions.onAddWishlistItem(state.selectedVehicleId) }
    SectionScaffold(
        title = stringResource(R.string.nav_wishlist),
        onBack = onBack,
        viewModel = viewModel,
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
        floatingActionButton = { if (state.loaded) AddFab(stringResource(R.string.action_add_wishlist_item), onClick = add) },
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
                                trailing = item.estimatedCostCents?.let { wishlistEstimate(fmt, it, item.currency) },
                                pending = item.id in pendingIds,
                            )
                        }
                    }
                }
            }
    }
    state.data.firstOrNull { it.id == open }?.let { item ->
        WishlistSheet(
            item,
            state.vehicleById[item.vehicleId],
            fmt,
            onDismiss = { open = null },
            pending = item.id in pendingIds,
            onEdit = {
                open = null
                actions.onEditWishlistItem(item.id)
            },
            onDelete = {
                open = null
                viewModel.deleteWishlistItem(item.id)
            },
        )
    }
}

/** Groups newest-first items by calendar month in the server's time zone, keeping their order. */
private fun <T> byMonth(items: List<T>, zone: ZoneId, at: (T) -> java.time.Instant?): Map<YearMonth, List<T>> =
    items.groupBy { item -> at(item)?.atZone(zone)?.let(YearMonth::from) ?: YearMonth.of(1970, 1) }

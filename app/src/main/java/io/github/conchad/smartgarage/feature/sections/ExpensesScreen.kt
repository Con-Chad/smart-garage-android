package io.github.conchad.smartgarage.feature.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.ExpenseSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.charts.BarChart
import io.github.conchad.smartgarage.ui.components.charts.ChartPlaceholder
import io.github.conchad.smartgarage.ui.components.charts.ChartTitle
import io.github.conchad.smartgarage.ui.components.charts.barSummary
import io.github.conchad.smartgarage.ui.components.expenseCategoryLabel
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.components.shortName
import io.github.conchad.smartgarage.ui.glass.glass
import androidx.compose.foundation.shape.CircleShape
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ExpensesScreen(onBack: () -> Unit, actions: SectionActions, viewModel: ExpensesViewModel = hiltViewModel()) {
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
        floatingActionButton = {
            if (state.loaded && state.canEditAny) AddFab(stringResource(R.string.action_add_expense), onClick = add)
        },
    ) {
        item(key = "overview") { ExpensesOverview(state.data, fmt) }
        byMonth(state.data.expenses, fmt.zone) { it.occurredAt.toInstantOrNull() }.forEach { (month, expenses) ->
            item(key = month.toString(), contentType = "header") {
                GroupHeader("${monthLabel(month)} · ${fmt.money(expenses.sumOf { it.amountCents }, expenses.first().currency)}")
            }
            item(key = "$month-list", contentType = "group") {
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
                            // A glass track with a sheen fill (D-A41), not opaque Material bars.
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(glass.pill)) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction = (row.cents.toFloat() / total).coerceIn(0.02f, 1f))
                                        .height(6.dp)
                                        .clip(CircleShape)
                                        .background(glass.sheen),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

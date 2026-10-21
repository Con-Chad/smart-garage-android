package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.MaintenanceRecord
import io.github.conchad.smartgarage.core.model.Specs
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.AppScaffold
import io.github.conchad.smartgarage.ui.components.DetailRow
import io.github.conchad.smartgarage.ui.components.DueRow
import io.github.conchad.smartgarage.ui.components.EmptyState
import io.github.conchad.smartgarage.ui.components.FuelRow
import io.github.conchad.smartgarage.ui.components.FuelSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.RecordSheet
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.ScheduleSheet
import io.github.conchad.smartgarage.ui.components.SheetTarget
import io.github.conchad.smartgarage.ui.components.rememberSheetTarget
import io.github.conchad.smartgarage.ui.components.VehiclePhoto
import io.github.conchad.smartgarage.ui.components.VehicleStatusChip
import io.github.conchad.smartgarage.ui.components.bodyTypeLabel
import io.github.conchad.smartgarage.ui.components.driveTypeLabel
import io.github.conchad.smartgarage.ui.components.dueDays
import io.github.conchad.smartgarage.ui.components.enumLabel
import io.github.conchad.smartgarage.ui.components.fuelTypeLabel
import io.github.conchad.smartgarage.ui.components.inspectionLabel

@Composable
fun VehicleDetailScreen(onBack: () -> Unit, viewModel: VehicleDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detail = state.detail
    val listState = rememberLazyListState()
    // The heading below the photo names the vehicle; the bar takes the name over once that scrolls away.
    val headingGone by remember { derivedStateOf { listState.firstVisibleItemIndex >= 2 } }
    AppScaffold(title = if (headingGone) detail?.vehicle?.displayName.orEmpty() else "", onBack = onBack) { padding ->
        when {
            detail != null -> VehicleDetailContent(
                detail,
                state,
                listState,
                PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            )
            state.loaded -> EmptyState(
                icon = Icons.Outlined.DirectionsCar,
                title = stringResource(R.string.vehicle_gone_title),
                body = stringResource(R.string.vehicle_gone_body),
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun VehicleDetailContent(
    detail: VehicleDetail,
    state: VehicleDetailUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
) {
    val fmt = state.formatter
    val vehicle = detail.vehicle
    var open by rememberSheetTarget()
    var allFuel by rememberSaveable { mutableStateOf(false) }
    var allRecords by rememberSaveable { mutableStateOf(false) }

    LazyColumn(state = listState, contentPadding = contentPadding) {
        item {
            VehiclePhoto(
                vehicle,
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f),
                shape = MaterialTheme.shapes.extraLarge,
                iconSize = 72.dp,
            )
        }
        item { Heading(vehicle, detail, fmt) }
        item { Stats(detail, fmt) }

        item { GroupHeader(stringResource(R.string.vehicle_service)) }
        item {
            if (detail.due.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_schedules))
            } else {
                GroupCard {
                    detail.due.forEachIndexed { index, item ->
                        if (index > 0) RowDivider()
                        DueRow(item, fmt, onClick = { open = SheetTarget.Schedule(item.schedule.id) }, showVehicle = false)
                    }
                }
            }
        }

        if (detail.inspections.isNotEmpty() || detail.policies.isNotEmpty()) {
            item { GroupHeader(stringResource(R.string.vehicle_papers)) }
            item {
                GroupCard {
                    detail.inspections.forEachIndexed { index, inspection ->
                        if (index > 0) RowDivider()
                        val due = inspection.nextDueAt.toInstantOrNull()
                        PaperRow(
                            Icons.Outlined.EventAvailable,
                            inspectionLabel(inspection.type),
                            listOfNotNull(due?.let(fmt::date), due?.let { dueDays(fmt.daysFromToday(it).toInt()) }).joinToString(" · "),
                        )
                    }
                    detail.policies.forEachIndexed { index, policy ->
                        if (index > 0 || detail.inspections.isNotEmpty()) RowDivider()
                        PaperRow(
                            Icons.Outlined.Shield,
                            policy.provider,
                            listOfNotNull(
                                policy.endDate.toInstantOrNull()?.let { stringResource(R.string.dashboard_insurance_until, fmt.date(it)) },
                                fmt.money(policy.premiumCents, policy.currency).let { stringResource(R.string.insurance_premium, it) },
                            ).joinToString(" · "),
                        )
                    }
                }
            }
        }

        item {
            GroupHeader(
                stringResource(R.string.nav_fuel),
                action = if (allFuel) stringResource(R.string.action_show_less) else stringResource(R.string.action_show_all, detail.fuel.size),
                onAction = { allFuel = !allFuel }.takeIf { detail.fuel.size > PREVIEW },
            )
        }
        item {
            if (detail.fuel.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_fuel))
            } else {
                GroupCard {
                    detail.fuel.take(if (allFuel) Int.MAX_VALUE else PREVIEW).forEachIndexed { index, entry ->
                        if (index > 0) RowDivider()
                        FuelRow(
                            entry,
                            vehicle = null,
                            fmt = fmt,
                            onClick = { open = SheetTarget.Fuel(entry.id) },
                            economy = detail.segments[entry.id]?.let { fmt.economy(it.litersPer100Km) },
                        )
                    }
                }
            }
        }

        item {
            GroupHeader(
                stringResource(R.string.vehicle_history),
                action = if (allRecords) stringResource(R.string.action_show_less) else stringResource(R.string.action_show_all, detail.records.size),
                onAction = { allRecords = !allRecords }.takeIf { detail.records.size > PREVIEW },
            )
        }
        item {
            if (detail.records.isEmpty()) {
                EmptyGroup(stringResource(R.string.vehicle_no_records))
            } else {
                GroupCard {
                    detail.records.take(if (allRecords) Int.MAX_VALUE else PREVIEW).forEachIndexed { index, record ->
                        if (index > 0) RowDivider()
                        RecordRow(record, state.recordName(record), vehicle = null, fmt) { open = SheetTarget.Record(record.id) }
                    }
                }
            }
        }

        specs(vehicle, fmt)
    }

    when (val target = open) {
        is SheetTarget.Schedule -> detail.due.firstOrNull { it.schedule.id == target.id }?.let { ScheduleSheet(it, fmt) { open = null } }
        is SheetTarget.Record -> detail.records.firstOrNull { it.id == target.id }?.let {
            RecordSheet(it, state.recordName(it) ?: stringResource(R.string.record_untitled), vehicle, fmt) { open = null }
        }
        is SheetTarget.Fuel -> detail.fuel.firstOrNull { it.id == target.id }?.let {
            FuelSheet(it, vehicle, detail.segments[it.id], fmt) { open = null }
        }
        null -> Unit
    }
}

@Composable
private fun Heading(vehicle: Vehicle, detail: VehicleDetail, fmt: Formatter) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(vehicle.displayName, style = MaterialTheme.typography.headlineSmall)
        val line = listOfNotNull(vehicle.licensePlate, vehicle.color).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vehicle.access.isShared) SharedBy(vehicle.owner?.name)
        Row(Modifier.padding(top = 8.dp)) { VehicleStatusChip(detail.summary) }
    }
}

@Composable
private fun Stats(detail: VehicleDetail, fmt: Formatter) {
    StatStrip(
        listOf(
            stringResource(R.string.field_odometer) to fmt.distance(detail.vehicle.currentOdometerKm),
            stringResource(R.string.fuel_avg_economy) to (detail.averageConsumption?.let { fmt.economy(it) } ?: "—"),
            stringResource(R.string.vehicle_spent_this_year) to fmt.money(detail.spentThisYearCents, detail.vehicle.purchaseCurrency),
        ),
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
    )
}

/**
 * Up to three figures side by side, value above label. Values stay on one line and shrink to fit rather than wrap;
 * labels may take two lines.
 */
@Composable
fun StatStrip(stats: List<Pair<String, String>>, modifier: Modifier = Modifier, title: String? = null) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface)
            stats.forEach { (label, value) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(value, style = style, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize))
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
      }
    }
}

@Composable
private fun PaperRow(icon: ImageVector, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A past service: name, then date, vehicle and odometer; cost on the right. */
@Composable
fun RecordRow(record: MaintenanceRecord, name: String?, vehicle: Vehicle?, fmt: Formatter, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                name ?: stringResource(R.string.record_untitled),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    record.performedAt.toInstantOrNull()?.let(fmt::shortDate),
                    vehicle?.displayName,
                    record.odometerKm?.let(fmt::distance),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(fmt.money(record.costCents, record.currency), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun EmptyGroup(text: String) {
    GroupCard {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

private fun LazyListScope.specs(vehicle: Vehicle, fmt: Formatter) {
    item { GroupHeader(stringResource(R.string.vehicle_details)) }
    item {
        GroupCard(Modifier.padding(bottom = 8.dp)) {
            val specs: Specs? = vehicle.specs
            DetailRow(stringResource(R.string.spec_engine), specs?.engineDescription)
            DetailRow(
                stringResource(R.string.spec_power),
                listOfNotNull(specs?.powerKw?.let { "$it kW" }, specs?.powerPs?.let { "$it ${stringResource(R.string.unit_ps)}" })
                    .joinToString(" / ").ifEmpty { null },
            )
            DetailRow(stringResource(R.string.spec_torque), specs?.torqueNm?.let { "$it Nm" })
            DetailRow(stringResource(R.string.spec_displacement), specs?.displacementCc?.let { "$it cm³" })
            DetailRow(stringResource(R.string.spec_fuel), specs?.fuelType?.let { fuelTypeLabel(it) })
            DetailRow(stringResource(R.string.spec_transmission), specs?.transmissionTypes?.joinToString { enumLabel(it) }?.ifEmpty { null })
            DetailRow(stringResource(R.string.spec_drive), specs?.driveType?.let { driveTypeLabel(it) })
            DetailRow(stringResource(R.string.spec_body), specs?.bodyType?.let { bodyTypeLabel(it) })
            DetailRow(stringResource(R.string.field_vin), vehicle.vin)
            DetailRow(stringResource(R.string.field_plate), vehicle.licensePlate)
            DetailRow(stringResource(R.string.field_color), vehicle.color)
            DetailRow(stringResource(R.string.field_purchased), vehicle.purchaseDate.toInstantOrNull()?.let(fmt::date))
            DetailRow(stringResource(R.string.field_purchase_price), vehicle.purchasePriceCents?.let { fmt.money(it, vehicle.purchaseCurrency) })
            DetailRow(stringResource(R.string.field_notes), vehicle.notes)
        }
    }
}

private const val PREVIEW = 3

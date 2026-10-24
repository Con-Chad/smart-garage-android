package io.github.conchad.smartgarage.feature.vehicles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Modification
import io.github.conchad.smartgarage.core.model.Specs
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.VehicleDetail
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.DetailRow
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.bodyTypeLabel
import io.github.conchad.smartgarage.ui.components.driveTypeLabel
import io.github.conchad.smartgarage.ui.components.enumLabel
import io.github.conchad.smartgarage.ui.components.fuelTypeLabel
import io.github.conchad.smartgarage.ui.components.modificationCategoryLabel
import io.github.conchad.smartgarage.ui.glass.glass

/** One drop-down under "Vehicle details": a stable key for its saved open/closed state, the header, and the rows. */
internal class DetailSection(val key: String, val title: String, val content: @Composable () -> Unit)

/**
 * The groups under "Vehicle details" (note 15) that have anything to show, in reading order: registration (plate,
 * HSN/TSN, inspection sticker), VIN, purchase (the colour is under "At a glance" since D-A70), specs, tyres, modifications, the owner's custom fields and the
 * notes. Tyres are only a group when the owner keeps a tyre custom field or modification; there is no tyre model to
 * invent one from.
 */
@Composable
internal fun detailSections(vehicle: Vehicle, detail: VehicleDetail, state: VehicleDetailUiState, fmt: Formatter): List<DetailSection> {
    val specs: Specs? = vehicle.specs
    val yes = stringResource(R.string.yes)
    val no = stringResource(R.string.no)
    val registration = listOfNotNull(
        vehicle.licensePlate?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.field_plate) to it },
        listOfNotNull(vehicle.hsn, vehicle.tsn).joinToString(" / ").ifEmpty { null }?.let { stringResource(R.string.vehicle_form_hsn_tsn) to it },
        detail.inspections.firstNotNullOfOrNull { it.stickerNumber?.takeIf(String::isNotBlank) }?.let { stringResource(R.string.inspection_sticker) to it },
    )
    val vin = vehicle.vin?.takeIf { it.isNotBlank() }
    val purchase = listOfNotNull(
        vehicle.purchaseDate.toInstantOrNull()?.let { stringResource(R.string.field_purchased) to fmt.date(it) },
        vehicle.purchasePriceCents?.let { stringResource(R.string.field_purchase_price) to fmt.money(it, vehicle.purchaseCurrency) },
    )
    val specRows = listOfNotNull(
        specs?.engineDescription?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.spec_engine) to it },
        listOfNotNull(specs?.powerKw?.let { "$it kW" }, specs?.powerPs?.let { "$it ${stringResource(R.string.unit_ps)}" })
            .joinToString(" / ").ifEmpty { null }?.let { stringResource(R.string.spec_power) to it },
        specs?.torqueNm?.let { stringResource(R.string.spec_torque) to "$it Nm" },
        specs?.displacementCc?.let { stringResource(R.string.spec_displacement) to "$it cm³" },
        specs?.fuelType?.let { stringResource(R.string.spec_fuel) to fuelTypeLabel(it) },
        specs?.transmissionTypes?.joinToString { enumLabel(it) }?.ifEmpty { null }?.let { stringResource(R.string.spec_transmission) to it },
        specs?.driveType?.let { stringResource(R.string.spec_drive) to driveTypeLabel(it) },
        specs?.bodyType?.let { stringResource(R.string.spec_body) to bodyTypeLabel(it) },
    )
    val tires = state.tireField?.let { (field, value) -> fmt.customFieldValue(field.fieldType, field.unit, value, yes, no) }
        ?: state.tireModification?.let { mod ->
            listOfNotNull(mod.name.ifBlank { null }, mod.installedAt.toInstantOrNull()?.let { stringResource(R.string.modification_installed, fmt.date(it)) })
                .joinToString(" · ").ifEmpty { null }
        }
    val custom = state.otherCustomFields.map { (field, value) -> field.label to fmt.customFieldValue(field.fieldType, field.unit, value, yes, no) }
    val notes = vehicle.notes?.takeIf { it.isNotBlank() }

    return listOfNotNull(
        registration.takeIf { it.isNotEmpty() }?.let { rows -> DetailSection("registration", stringResource(R.string.vehicle_registration)) { DetailRows(rows) } },
        vin?.let { DetailSection("vin", stringResource(R.string.field_vin)) { ValueBlock(it) } },
        purchase.takeIf { it.isNotEmpty() }?.let { rows -> DetailSection("purchase", stringResource(R.string.vehicle_purchase)) { DetailRows(rows) } },
        specRows.takeIf { it.isNotEmpty() }?.let { rows -> DetailSection("specs", stringResource(R.string.vehicle_specs)) { DetailRows(rows) } },
        tires?.let { DetailSection("tires", stringResource(R.string.vehicle_tires)) { ValueBlock(it) } },
        state.modifications.takeIf { it.isNotEmpty() }?.let { mods ->
            DetailSection("modifications", stringResource(R.string.vehicle_modifications)) {
                mods.forEachIndexed { index, mod ->
                    if (index > 0) RowDivider()
                    ModificationRow(mod, fmt)
                }
            }
        },
        custom.takeIf { it.isNotEmpty() }?.let { rows -> DetailSection("custom", stringResource(R.string.vehicle_custom_fields)) { DetailRows(rows) } },
        notes?.let { DetailSection("notes", stringResource(R.string.field_notes)) { ValueBlock(it) } },
    )
}

@Composable
private fun DetailRows(rows: List<Pair<String, String>>) {
    rows.forEach { (label, value) -> DetailRow(label, value) }
}

/** A group whose header is the label: the value alone, full width, so a VIN or a note has the room. */
@Composable
private fun ValueBlock(value: String) {
    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(16.dp))
}

@Composable
private fun ModificationRow(mod: Modification, fmt: Formatter) {
    val tokens = glass
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Build, contentDescription = null, tint = tokens.sheen, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(mod.name.ifBlank { modificationCategoryLabel(mod.category) }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    modificationCategoryLabel(mod.category),
                    mod.installedAt.toInstantOrNull()?.let { stringResource(R.string.modification_installed, fmt.date(it)) },
                    mod.costCents?.let { fmt.money(it, mod.currency) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            mod.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        val gains = listOfNotNull(
            mod.addedPowerPs?.let { "+$it ${stringResource(R.string.unit_ps)}" },
            mod.addedPowerKw?.let { "+$it kW" },
            mod.addedTorqueNm?.let { "+$it Nm" },
        )
        if (gains.isNotEmpty()) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
                gains.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary) }
            }
        }
    }
}

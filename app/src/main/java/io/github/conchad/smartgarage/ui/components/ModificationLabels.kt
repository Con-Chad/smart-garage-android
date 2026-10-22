package io.github.conchad.smartgarage.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.conchad.smartgarage.R

/** The web's `vehicles.modificationCategories`; an unknown value from a newer server falls back to its name. */
@Composable
fun modificationCategoryLabel(value: String): String = when (value) {
    "STAGE_1" -> stringResource(R.string.mod_category_stage_1)
    "STAGE_2" -> stringResource(R.string.mod_category_stage_2)
    "STAGE_3" -> stringResource(R.string.mod_category_stage_3)
    "STAGE_4" -> stringResource(R.string.mod_category_stage_4)
    "INTAKE" -> stringResource(R.string.mod_category_intake)
    "INTERCOOLER" -> stringResource(R.string.mod_category_intercooler)
    "DOWNPIPE" -> stringResource(R.string.mod_category_downpipe)
    "EXHAUST" -> stringResource(R.string.mod_category_exhaust)
    "TURBOCHARGER" -> stringResource(R.string.mod_category_turbocharger)
    "FUEL_PUMP_HP" -> stringResource(R.string.mod_category_fuel_pump_hp)
    "FUEL_PUMP_LP" -> stringResource(R.string.mod_category_fuel_pump_lp)
    "INJECTORS" -> stringResource(R.string.mod_category_injectors)
    "SUSPENSION" -> stringResource(R.string.mod_category_suspension)
    "BRAKES" -> stringResource(R.string.mod_category_brakes)
    "WHEELS" -> stringResource(R.string.mod_category_wheels)
    "ECU_TUNE" -> stringResource(R.string.mod_category_ecu_tune)
    "TRANSMISSION_TUNE" -> stringResource(R.string.mod_category_transmission_tune)
    "CUSTOM" -> stringResource(R.string.mod_category_custom)
    else -> enumLabel(value)
}

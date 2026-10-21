package io.github.conchad.smartgarage.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.conchad.smartgarage.R

// Labels for the enums the web translates (vehicles.*Types, expenses.categories, wishlist.*, documents.categories). An unknown value
// from a newer server falls back to a readable form of its name.

@Composable
fun bodyTypeLabel(value: String): String = when (value) {
    "SEDAN" -> stringResource(R.string.spec_body_sedan)
    "HATCHBACK" -> stringResource(R.string.spec_body_hatchback)
    "WAGON" -> stringResource(R.string.spec_body_wagon)
    "COUPE" -> stringResource(R.string.spec_body_coupe)
    "CONVERTIBLE" -> stringResource(R.string.spec_body_convertible)
    "SUV" -> stringResource(R.string.spec_body_suv)
    "CROSSOVER" -> stringResource(R.string.spec_body_crossover)
    "VAN" -> stringResource(R.string.spec_body_van)
    "PICKUP" -> stringResource(R.string.spec_body_pickup)
    "OTHER" -> stringResource(R.string.spec_body_other)
    else -> enumLabel(value)
}

@Composable
fun driveTypeLabel(value: String): String = when (value) {
    "FWD" -> stringResource(R.string.spec_drive_fwd)
    "RWD" -> stringResource(R.string.spec_drive_rwd)
    "AWD" -> stringResource(R.string.spec_drive_awd)
    "FOUR_WD" -> stringResource(R.string.spec_drive_four_wd)
    "OTHER" -> stringResource(R.string.spec_drive_other)
    else -> enumLabel(value)
}

@Composable
fun fuelTypeLabel(value: String): String = when (value) {
    "PETROL" -> stringResource(R.string.spec_fuel_petrol)
    "DIESEL" -> stringResource(R.string.spec_fuel_diesel)
    "ELECTRIC" -> stringResource(R.string.spec_fuel_electric)
    "HYBRID" -> stringResource(R.string.spec_fuel_hybrid)
    "PLUGIN_HYBRID" -> stringResource(R.string.spec_fuel_plugin_hybrid)
    "LPG" -> stringResource(R.string.spec_fuel_lpg)
    "CNG" -> stringResource(R.string.spec_fuel_cng)
    "OTHER" -> stringResource(R.string.spec_fuel_other)
    else -> enumLabel(value)
}

@Composable
fun expenseCategoryLabel(value: String): String = when (value) {
    "MAINTENANCE" -> stringResource(R.string.expense_maintenance)
    "FUEL" -> stringResource(R.string.expense_fuel)
    "INSURANCE" -> stringResource(R.string.expense_insurance)
    "TAX" -> stringResource(R.string.expense_tax)
    "REGISTRATION" -> stringResource(R.string.expense_registration)
    "REPAIR" -> stringResource(R.string.expense_repair)
    "ACCESSORY" -> stringResource(R.string.expense_accessory)
    "OTHER" -> stringResource(R.string.expense_other)
    else -> enumLabel(value)
}

@Composable
fun wishlistStatusLabel(value: String): String = when (value) {
    "IDEA" -> stringResource(R.string.wish_status_idea)
    "PLANNED" -> stringResource(R.string.wish_status_planned)
    "ORDERED" -> stringResource(R.string.wish_status_ordered)
    "DONE" -> stringResource(R.string.wish_status_done)
    else -> enumLabel(value)
}

@Composable
fun wishlistCategoryLabel(value: String): String = when (value) {
    "VEHICLE" -> stringResource(R.string.wish_category_vehicle)
    "PART" -> stringResource(R.string.wish_category_part)
    "ACCESSORY" -> stringResource(R.string.wish_category_accessory)
    "OTHER" -> stringResource(R.string.wish_category_other)
    else -> enumLabel(value)
}

@Composable
fun documentCategoryLabel(value: String): String = when (value) {
    "INVOICE" -> stringResource(R.string.doc_category_invoice)
    "INSPECTION" -> stringResource(R.string.doc_category_inspection)
    "REGISTRATION" -> stringResource(R.string.doc_category_registration)
    "INSURANCE" -> stringResource(R.string.doc_category_insurance)
    "OTHER" -> stringResource(R.string.doc_category_other)
    else -> enumLabel(value)
}

/** "MANUAL" → "Manual", "PLUGIN_HYBRID" → "Plugin hybrid"; short abbreviations stay as they are. */
fun enumLabel(value: String): String =
    if (value.length <= 3) value else value.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

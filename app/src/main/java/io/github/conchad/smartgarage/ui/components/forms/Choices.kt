package io.github.conchad.smartgarage.ui.components.forms

import io.github.conchad.smartgarage.core.model.Vehicle

/** A [FormPicker] option for a vehicle that may be "none" (general notes, wishlist items without a car). */
data class VehicleChoice(val vehicle: Vehicle?, val label: String)

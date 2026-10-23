package io.github.conchad.smartgarage.ui.components.forms

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.AuroraTintResolver
import io.github.conchad.smartgarage.ui.glass.CarPaints
import io.github.conchad.smartgarage.ui.glass.VehicleTint
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.pressScale
import io.github.conchad.smartgarage.ui.glass.rememberPressFeedback

/** One paint in the picker: the English name that is stored (so the server keeps the same free text as before). */
private class CarColor(val stored: String, @StringRes val label: Int, val argb: Int)

private val CarColors = listOf(
    CarColor("Black", R.string.car_color_black, CarPaints.BLACK),
    CarColor("White", R.string.car_color_white, CarPaints.WHITE),
    CarColor("Silver", R.string.car_color_silver, CarPaints.SILVER),
    CarColor("Grey", R.string.car_color_grey, CarPaints.GREY),
    CarColor("Blue", R.string.car_color_blue, CarPaints.BLUE),
    CarColor("Red", R.string.car_color_red, CarPaints.RED),
    CarColor("Green", R.string.car_color_green, CarPaints.GREEN),
    CarColor("Yellow", R.string.car_color_yellow, CarPaints.YELLOW),
    CarColor("Orange", R.string.car_color_orange, CarPaints.ORANGE),
    CarColor("Brown", R.string.car_color_brown, CarPaints.BROWN),
    CarColor("Beige", R.string.car_color_beige, CarPaints.BEIGE),
    CarColor("Gold", R.string.car_color_gold, CarPaints.GOLD),
    CarColor("Purple", R.string.car_color_purple, CarPaints.PURPLE),
)

/**
 * The vehicle's colour as one choice (D-A52): a car has one paint, so instead of free text it is a grid of swatches,
 * each with its name under it; tapping one selects it (radio semantics, one selected), tapping the selected one
 * clears the field. The stored value stays a plain string: the English name of the swatch ("Blue"), which
 * [VehicleTint] already understands for the aurora. A saved value that is none of the swatches ("Forest Green",
 * "Nardo grau") is kept and shown first as its own selected swatch, so editing a car never rewrites it unasked.
 * Matching is case-insensitive and also accepts the localised name ("Schwarz").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CarColorPicker(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val tokens = glass
    val trimmed = value.trim()
    val labels = CarColors.map { stringResource(it.label) }
    val selectedIndex = CarColors.indices.firstOrNull { i ->
        CarColors[i].stored.equals(trimmed, ignoreCase = true) || labels[i].equals(trimmed, ignoreCase = true)
    }
    val custom = trimmed.takeIf { it.isNotEmpty() && selectedIndex == null }
    val shownName = when {
        custom != null -> custom
        selectedIndex != null -> labels[selectedIndex]
        else -> stringResource(R.string.car_color_none)
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "$label · $shownName",
            style = MaterialTheme.typography.labelLarge,
            color = tokens.textSecondary,
        )
        Text(stringResource(R.string.car_color_hint), style = MaterialTheme.typography.bodySmall, color = tokens.textSecondary)
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup()) {
            if (custom != null) {
                PaintSwatch(
                    argb = VehicleTint.argb(custom) ?: VehicleTint.STEEL,
                    name = custom,
                    selected = true,
                    onClick = { onValueChange("") },
                )
            }
            CarColors.forEachIndexed { i, paint ->
                val selected = i == selectedIndex
                PaintSwatch(
                    argb = paint.argb,
                    name = labels[i],
                    selected = selected,
                    onClick = { onValueChange(if (selected) "" else paint.stored) },
                )
            }
        }
    }
}

/** A paint swatch with its name beneath: 40 dp disc inside a 64 × 72 dp target, a sheen ring and check when chosen. */
@Composable
private fun PaintSwatch(argb: Int, name: String, selected: Boolean, onClick: () -> Unit) {
    val tokens = glass
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    Column(
        Modifier
            .width(64.dp)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = name
                role = Role.RadioButton
                this.selected = selected
            }
            .padding(vertical = 4.dp)
            .pressScale(press),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(Color(argb), CircleShape)
                .border(if (selected) 2.5.dp else 1.dp, if (selected) tokens.sheen else tokens.pillBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (AuroraTintResolver.isPale(argb)) tokens.onPale else tokens.onPhoto,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            name,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = if (selected) tokens.textPrimary else tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

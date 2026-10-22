package io.github.conchad.smartgarage.ui.components.forms

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.format.Formatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// The form kit (D-A20). Fields hold plain strings that live in the ViewModel; units are shown as a suffix in the
// user's display unit and converted to stored units (km, liters, cents) by the ViewModel through Formatter. Every
// field has a visible label, an optional supporting line and an error line, so TalkBack reads the same things a
// sighted user sees. All fields are at least 56 dp tall (M3 text field) and fill the width.

/** One-line text: station, vendor, part name. */
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    capitalizeWords: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        isError = error != null,
        supportingText = supportingText(error, supporting),
        keyboardOptions = KeyboardOptions(
            capitalization = if (capitalizeWords) KeyboardCapitalization.Words else KeyboardCapitalization.Sentences,
            imeAction = imeAction,
        ),
    )
}

/**
 * A number in the user's display unit, with the unit as a suffix ("mi", "gal"). [large] is for the two or three
 * numbers a gas-station form is about: bigger text, easier to read at arm's length. [integer] hides the decimal key.
 */
@Composable
fun FormNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    prefix: String? = null,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    large: Boolean = false,
    integer: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) {
    val style: TextStyle = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() || it == '.' || it == ',' || it == ' ' }) },
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        textStyle = style.copy(textAlign = TextAlign.End),
        isError = error != null,
        supportingText = supportingText(error, supporting),
        prefix = prefix?.let { { Text(it, style = style) } },
        suffix = unit?.let { { Text(it, style = style, modifier = Modifier.padding(start = 4.dp)) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = imeAction),
    )
}

/** Amount of money in the server's currency; the symbol leads the number the way the formatter prints it. */
@Composable
fun FormMoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fmt: Formatter,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    large: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) = FormNumberField(
    value, onValueChange, label, modifier,
    prefix = fmt.currencySymbol,
    supporting = supporting,
    error = error,
    enabled = enabled,
    large = large,
    imeAction = imeAction,
)

/** A distance in the display unit (mi or km), whole numbers only. */
@Composable
fun FormDistanceField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fmt: Formatter,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    large: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) = FormNumberField(
    value, onValueChange, label, modifier,
    unit = fmt.distanceUnit,
    supporting = supporting,
    error = error,
    enabled = enabled,
    large = large,
    integer = true,
    imeAction = imeAction,
)

/** A volume in the display unit (gal or L). */
@Composable
fun FormVolumeField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    fmt: Formatter,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    large: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
) = FormNumberField(
    value, onValueChange, label, modifier,
    unit = fmt.volumeUnit,
    supporting = supporting,
    error = error,
    enabled = enabled,
    large = large,
    imeAction = imeAction,
)

/** Multi-line notes; plain text here, Markdown on the web. Grows with its content from three lines. */
@Composable
fun FormNotesField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.field_notes),
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth().heightIn(min = 112.dp),
        enabled = enabled,
        minLines = 3,
        isError = error != null,
        supportingText = supportingText(error, supporting),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default,
        ),
    )
}

/**
 * A calendar date, shown the way the formatter prints dates and picked with the M3 date picker. The picker works
 * on the server's time zone (the day the user means is the day the web shows); the ViewModel turns the date into
 * an instant with [Formatter.instantFor].
 */
@Composable
fun FormDateField(
    date: LocalDate,
    onDateChange: (LocalDate) -> Unit,
    label: String,
    fmt: Formatter,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    allowFuture: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    // A read-only text field can't be clicked; its press interactions open the picker instead.
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) open = true }
    }
    val text = fmt.date(date.atStartOfDay(fmt.zone).toInstant())
    OutlinedTextField(
        value = text,
        onValueChange = {},
        readOnly = true,
        enabled = enabled,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
        isError = error != null,
        supportingText = supportingText(error, supporting),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().semantics { role = Role.Button },
    )
    if (open) {
        val today = fmt.today()
        // The picker's millis are UTC midnight of the picked day; the day is what matters, not the instant.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = if (allowFuture) DatePickerDefaults.AllDates else object : SelectableDates {
                private val limit = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= limit
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state) }
    }
}

/** A choice from a short list (vehicle, schedule): an exposed dropdown with the same frame as the text fields. */
@Composable
fun <T> FormPicker(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    placeholder: String? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let(optionLabel) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            isError = error != null,
            supportingText = supportingText(error, supporting),
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Label (and optional explanation) on the left, a switch on the right; the whole row toggles. */
@Composable
fun FormSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun supportingText(error: String?, supporting: String?): (@Composable () -> Unit)? = when {
    error != null -> { { Text(error) } }
    supporting != null -> { { Text(supporting) } }
    else -> null
}

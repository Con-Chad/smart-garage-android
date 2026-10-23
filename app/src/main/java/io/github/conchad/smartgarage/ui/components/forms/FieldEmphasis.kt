package io.github.conchad.smartgarage.ui.components.forms

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassTextFieldColors

/**
 * Emphasized form fields (D-A62): a form that wraps its fields in [EmphasizedFormFields] gets a stronger unfocused
 * outline ([io.github.conchad.smartgarage.ui.glass.GlassTokens.fieldBorderStrong]) and the field text, label and
 * supporting line [FIELD_TEXT_BOOST] larger. Only the Add / Edit vehicle form opts in; every other form keeps the
 * default frame.
 */
internal val LocalFormFieldEmphasis = staticCompositionLocalOf { false }

/** The extra text size of an emphasized field over the theme's body styles. */
val FIELD_TEXT_BOOST: TextUnit = 2.sp

/** Every form field inside [content] is drawn emphasized (see [LocalFormFieldEmphasis]). */
@Composable
fun EmphasizedFormFields(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFormFieldEmphasis provides true, content = content)
}

/** The glass field colours; with the stronger unfocused outline when the fields are emphasized. */
@Composable
internal fun formFieldColors(): TextFieldColors {
    val colors = glassTextFieldColors()
    return if (LocalFormFieldEmphasis.current) colors.copy(unfocusedIndicatorColor = glass.fieldBorderStrong) else colors
}

/**
 * The typography a form field draws with: the theme's, or with the body styles (input text, the resting and
 * floating label, the supporting line) [FIELD_TEXT_BOOST] larger when emphasized. The M3 field reads these styles
 * from the theme, so the label still animates between its two sizes.
 */
@Composable
internal fun FormFieldTypography(content: @Composable () -> Unit) {
    if (!LocalFormFieldEmphasis.current) {
        content()
        return
    }
    val type = MaterialTheme.typography
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = type.copy(
            bodyLarge = type.bodyLarge.boosted(),
            bodyMedium = type.bodyMedium.boosted(),
            bodySmall = type.bodySmall.boosted(),
            headlineSmall = type.headlineSmall.boosted(),
        ),
        content = content,
    )
}

private fun TextStyle.boosted(): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) (fontSize.value + FIELD_TEXT_BOOST.value).sp else fontSize,
    lineHeight = if (lineHeight.isSpecified) (lineHeight.value + FIELD_TEXT_BOOST.value).sp else lineHeight,
)

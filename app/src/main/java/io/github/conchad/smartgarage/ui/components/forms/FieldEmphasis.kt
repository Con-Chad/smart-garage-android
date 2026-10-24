package io.github.conchad.smartgarage.ui.components.forms

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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

/**
 * The glass field colours; emphasized (D-A62, D-A69) with the stronger unfocused outline over a faint frosted fill
 * ([io.github.conchad.smartgarage.ui.glass.GlassTokens.fieldFill]), focused or not: M3 cuts the floating label's notch
 * out of the fill too, and a denser focused fill made that notch a visible patch.
 */
@Composable
internal fun formFieldColors(): TextFieldColors {
    val colors = glassTextFieldColors()
    if (!LocalFormFieldEmphasis.current) return colors
    val tokens = glass
    return colors.copy(
        unfocusedIndicatorColor = tokens.fieldBorderStrong,
        unfocusedContainerColor = tokens.fieldFill,
        focusedContainerColor = tokens.fieldFill,
        errorContainerColor = tokens.fieldFill,
    )
}

/** The field's corners: the glass field's rounder [GlassTokens.radiusField] when emphasized (D-A69), else M3's. */
@Composable
internal fun formFieldShape(): Shape =
    if (LocalFormFieldEmphasis.current) RoundedCornerShape(glass.radiusField) else OutlinedTextFieldDefaults.shape

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

/** [formFieldShape]'s corner radius, for the field drawn in pieces (the autocomplete's joined list, D-A69). */
@Composable
internal fun formFieldRadius(): Dp = if (LocalFormFieldEmphasis.current) glass.radiusField else FIELD_RADIUS_DEFAULT

/** M3's outlined field corner (the theme's `extraSmall`). */
private val FIELD_RADIUS_DEFAULT: Dp = 4.dp

/** Above an outlined field with a label, the room for the label's top half (M3's `OutlinedTextFieldTopPadding`). */
internal val FIELD_LABEL_ROOM: Dp = 8.dp

/**
 * The form kit's outlined field. Not emphasized it is M3's [OutlinedTextField] in [formFieldColors] and
 * [formFieldShape]. Emphasized (D-A69) it is the same field drawn by hand from [BasicTextField] and M3's decoration
 * box, so its frosted [io.github.conchad.smartgarage.ui.glass.GlassTokens.fieldFill] can lie under the floating
 * label: M3 draws the fill in the outline's container and cuts the label's notch out of both, which left a darker
 * hole under the label on the glass. The outline keeps M3's notch, colours and thicknesses; the error, when given as
 * [errorDescription], is announced on the field as M3's own field does.
 */
@Composable
internal fun FormOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    errorDescription: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = false,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = formFieldColors()
    val shape = formFieldShape()
    if (!LocalFormFieldEmphasis.current) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle,
            label = label,
            placeholder = placeholder,
            trailingIcon = trailingIcon,
            prefix = prefix,
            suffix = suffix,
            supportingText = supportingText,
            isError = isError,
            keyboardOptions = keyboardOptions,
            singleLine = singleLine,
            minLines = minLines,
            interactionSource = interactionSource,
            shape = shape,
            colors = colors,
        )
        return
    }
    val tokens = glass
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    // The container's height, without the supporting line under it: how far down the fill goes.
    val containerHeight = remember { mutableIntStateOf(0) }
    val outlineColors = colors.copy(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
    )
    val textColor = if (enabled) tokens.textPrimary else tokens.textSecondary
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .then(if (label != null) Modifier.padding(top = FIELD_LABEL_ROOM) else Modifier)
            .semantics { if (isError && errorDescription != null) error(errorDescription) }
            .drawBehind {
                val h = containerHeight.intValue
                if (h > 0) drawOutline(shape.createOutline(Size(size.width, h.toFloat()), layoutDirection, this), colors.unfocusedContainerColor)
            }
            .defaultMinSize(minWidth = OutlinedTextFieldDefaults.MinWidth, minHeight = OutlinedTextFieldDefaults.MinHeight),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle.merge(TextStyle(color = textColor)),
        cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
        keyboardOptions = keyboardOptions,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        minLines = minLines,
        interactionSource = interaction,
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = inner,
                enabled = enabled,
                singleLine = singleLine,
                visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                isError = isError,
                label = label,
                placeholder = placeholder,
                trailingIcon = trailingIcon,
                prefix = prefix,
                suffix = suffix,
                supportingText = supportingText,
                colors = outlineColors,
                container = {
                    Box(Modifier.fillMaxSize().onSizeChanged { containerHeight.intValue = it.height })
                    OutlinedTextFieldDefaults.Container(
                        enabled = enabled,
                        isError = isError,
                        interactionSource = interaction,
                        colors = outlineColors,
                        shape = shape,
                    )
                },
            )
        },
    )
}

package io.github.conchad.smartgarage.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerColors
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The glass kit's everyday controls (D-A41): what the Service, Fuel, More sections, Settings, setup and every form
 * and sheet use instead of the stock tonal Material surfaces, so those screens match Home and the vehicle page.
 * Colours come from [GlassTokens] only; press feedback from [rememberPressFeedback]; no timings of their own.
 */

/**
 * An icon in a small glass circle (D-A41): the pill fill and 1 dp pill border, the icon in [tint] (the sheen by
 * default). Replaces the opaque tonal badge, so a list row or sheet header no longer carries a solid disc.
 */
@Composable
fun GlassIconBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = glass.sheen) {
    val tokens = glass
    Box(
        modifier
            .size(size)
            .glassPillSurface(tokens, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * The bottom sheet on glass (D-A41), for detail sheets and short editors: the page colour underneath (a sheet sits
 * over content, so like [GlassMenu] it must not let rows show through its text), the frosted panel fill, the inset
 * top highlight and the plate glow in the aurora tint on top (drawn inside the sheet's content, with the drag
 * handle, since the sheet's own modifier sits on a full-screen wrapper), a 1 dp panel border and the large radius.
 * No elevation shadow: the scrim already separates it.
 *
 * The glass is at most the window less the system bars and [SheetTopGap], a fixed height, and taller content
 * scrolls inside it. M3 1.4 pads the sheet for the status bar only while the sheet overlaps it, so it hands that room
 * back as the sheet is dragged down; content that wanted more room than it had grew with every pixel dragged, a
 * sheet whose height follows its offset moves its own resting point and re-snaps each frame, and swiping it down
 * bounced instead of closing (long schedules at a large font scale). Capped, it rests [SheetTopGap] below the
 * status bar, where that padding is already zero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tokens = glass
    val shape = RoundedCornerShape(topStart = tokens.radiusLarge, topEnd = tokens.radiusLarge)
    val glow = LocalAuroraTint.current.effective ?: MaterialTheme.colorScheme.primary
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val insets = WindowInsets.safeDrawing
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = tokens.pageBackground,
        contentColor = tokens.textPrimary,
        tonalElevation = 0.dp,
        // The handle is drawn inside the glass below, so the plate covers the sheet from its top edge.
        dragHandle = null,
    ) {
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val cap = (windowHeight - insets.getTop(this) - insets.getBottom(this) - SheetTopGap.roundToPx()).coerceAtLeast(constraints.minHeight)
                    val placeable = measurable.measure(constraints.copy(maxHeight = minOf(constraints.maxHeight, cap)))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .fillMaxWidth()
                .glassPlate(tokens, glow)
                .border(1.dp, tokens.panelBorder, shape),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                BottomSheetDefaults.DragHandle(color = tokens.textSecondary.copy(alpha = 0.5f))
            }
            content()
        }
    }
}

/** How far below the status bar the tallest [GlassBottomSheet] stops. */
private val SheetTopGap = 32.dp

/**
 * The filled button on glass (D-A41): a form's Save and a sheet's primary verb. The sheen as the fill with the
 * dark ink on it ([GlassTokens.onPale], ≥ 7:1), at least 48 dp tall, the press shrink of the pills. Disabled it
 * drops to the pill fill with secondary text, so a disabled Save still reads as a button.
 */
@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = glassButtonColors(),
        interactionSource = interaction,
        contentPadding = contentPadding,
        modifier = modifier.heightIn(min = 48.dp).pressScale(press),
        content = content,
    )
}

@Composable
fun glassButtonColors(): ButtonColors {
    val tokens = glass
    return ButtonDefaults.buttonColors(
        containerColor = tokens.sheen,
        contentColor = tokens.onPale,
        disabledContainerColor = tokens.pill,
        disabledContentColor = tokens.textSecondary,
    )
}

/**
 * The outlined button on glass (D-A41): pill fill, pill border and the sheen label, like [GlassPillButton] but with
 * the stock button's layout (full width, icon + label rows). Edit, a sheet's secondary verb, "Test connection".
 */
@Composable
fun GlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    shape: Shape = ButtonDefaults.outlinedShape,
    content: @Composable RowScope.() -> Unit,
) {
    val tokens = glass
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = tokens.pill,
            contentColor = tokens.sheen,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = tokens.textSecondary,
        ),
        border = BorderStroke(1.dp, tokens.pillBorder),
        interactionSource = interaction,
        contentPadding = contentPadding,
        modifier = modifier.heightIn(min = 48.dp).pressScale(press),
        content = content,
    )
}

/**
 * A filter chip on glass (D-A41): the vehicle filter and note tags. Unselected it is the pill; selected it takes a
 * sheen-tinted fill, the sheen border and label, and a check (so the state never rests on colour alone). The chip
 * is 32 dp; Material pads its touch target to 48 dp.
 */
@Composable
fun GlassFilterChip(selected: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val tokens = glass
    val interaction = remember { MutableInteractionSource() }
    val press = rememberPressFeedback(interaction)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = tokens.pill,
            labelColor = tokens.textPrimary,
            selectedContainerColor = tokens.sheen.copy(alpha = 0.18f),
            selectedLabelColor = tokens.sheen,
            selectedLeadingIconColor = tokens.sheen,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = tokens.pillBorder,
            selectedBorderColor = tokens.sheen.copy(alpha = 0.6f),
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
        interactionSource = interaction,
        modifier = modifier.pressScale(press),
    )
}

/** Switches on glass: the sheen track with dark ink thumb when on, the pill track and secondary thumb when off. */
@Composable
fun glassSwitchColors(): SwitchColors {
    val tokens = glass
    return SwitchDefaults.colors(
        checkedThumbColor = tokens.onPale,
        checkedTrackColor = tokens.sheen,
        checkedBorderColor = tokens.sheen,
        checkedIconColor = tokens.sheen,
        uncheckedThumbColor = tokens.textSecondary,
        uncheckedTrackColor = tokens.pill,
        uncheckedBorderColor = tokens.textSecondary.copy(alpha = 0.6f),
    )
}

/** Segmented buttons on glass: the active segment sheen-tinted with the sheen label, the rest clear, pill borders. */
@Composable
fun glassSegmentedColors(): SegmentedButtonColors {
    val tokens = glass
    return SegmentedButtonDefaults.colors(
        activeContainerColor = tokens.sheen.copy(alpha = 0.18f),
        activeContentColor = tokens.sheen,
        activeBorderColor = tokens.sheen.copy(alpha = 0.6f),
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = tokens.textPrimary,
        inactiveBorderColor = tokens.pillBorder,
    )
}

/** `ListItem`s on a glass card: no fill of their own, glass text colours, sheen leading and trailing icons. */
@Composable
fun glassListItemColors(): ListItemColors {
    val tokens = glass
    return ListItemDefaults.colors(
        containerColor = Color.Transparent,
        headlineColor = tokens.textPrimary,
        supportingColor = tokens.textSecondary,
        leadingIconColor = tokens.sheen,
        trailingIconColor = tokens.textSecondary,
        overlineColor = tokens.textSecondary,
    )
}

/**
 * The date picker on glass: the page colour as its container (a dialog over content, like [GlassAlertDialog]),
 * the selected day as a sheen disc with dark ink, today ringed in the sheen, glass text elsewhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun glassDatePickerColors(): DatePickerColors {
    val tokens = glass
    return DatePickerDefaults.colors(
        containerColor = tokens.pageBackground,
        titleContentColor = tokens.textSecondary,
        headlineContentColor = tokens.textPrimary,
        weekdayContentColor = tokens.textSecondary,
        subheadContentColor = tokens.textSecondary,
        navigationContentColor = tokens.textPrimary,
        yearContentColor = tokens.textPrimary,
        currentYearContentColor = tokens.sheen,
        selectedYearContentColor = tokens.onPale,
        selectedYearContainerColor = tokens.sheen,
        dayContentColor = tokens.textPrimary,
        selectedDayContentColor = tokens.onPale,
        selectedDayContainerColor = tokens.sheen,
        todayContentColor = tokens.sheen,
        todayDateBorderColor = tokens.sheen,
        dividerColor = tokens.panelBorder,
    )
}

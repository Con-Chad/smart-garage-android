package io.github.conchad.smartgarage.ui.components.forms

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.IntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.orNone
import io.github.conchad.smartgarage.ui.glass.orSnap
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion

/** Height of one suggestion row at font scale 1 (also the touch-target minimum). */
private val SUGGESTION_ROW = 48.dp

/** About six rows show before the list scrolls; the half row hints that there is more. */
private val SUGGESTIONS_MAX_HEIGHT = SUGGESTION_ROW * 6.5f

/** The focused field's outline width (M3's focused border thickness); the rest of the time 1 dp. */
private val FOCUSED_EDGE = 2.dp
private val RESTING_EDGE = 1.dp

/**
 * A one-line [FormTextField] that suggests values from a built-in list as the user types (D-A50): the matches
 * from [suggest] in a list right under the field (in the form's own column, so it scrolls with the form and stays
 * above the keyboard), about six rows before it scrolls, and an "Other…" row pinned at the bottom that keeps the
 * typed text as it is. The value stays free text: tapping a suggestion only fills the field.
 *
 * D-A69: the list is joined to the field, one piece of glass: as it opens the field's bottom corners square off and
 * its bottom edge fades, the list hangs from it at the same width with the field's outline carried down its sides
 * and round its bottom corners, both on the same frosted surface, a hairline between them. Scrolling, the rows
 * curve away like a drum ([convexRow]); at rest, and under reduced motion, they lie flat. The error line sits under
 * the whole piece instead of between the field and its list.
 *
 * The list shows while the field has focus, the text isn't blank and something matches. It closes on a pick, on
 * "Other…", on the keyboard's Done (which also hides the keyboard), on Back and when focus moves on; typing opens
 * it again. Rows are ≥ 48 dp and grow with the font scale; [icon] may return an image for a row (the insurer's
 * installed app icon), else [fallbackIcon] is drawn so the names line up. TalkBack hears the panel as a pane
 * titled with the number of suggestions, each row as a button in a list, and the error on the field itself.
 */
@Composable
fun FormAutocompleteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    suggest: (String) -> List<String>,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    capitalizeWords: Boolean = false,
    fallbackIcon: ImageVector? = null,
    icon: @Composable (String) -> ImageBitmap? = { null },
    browse: List<String> = emptyList(),
    keyboardType: KeyboardType = KeyboardType.Text,
    filter: (String) -> String = { it },
) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    // D-A62: the dropdown arrow lists all of [browse] whatever is typed, until the user types again.
    var browsing by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    // A TextFieldValue so a picked name puts the cursor at its end; the ViewModel still holds the plain string.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    val matches = remember(value, suggest) { suggest(value) }
    // A blank field with a list to browse shows the whole list (the models of the chosen make, the years).
    val suggestions = if (browsing || value.isBlank()) browse else matches
    val open = enabled && focused && !dismissed && suggestions.isNotEmpty()

    BackHandler(enabled = open) { dismissed = true }

    val tokens = glass
    val reduced = rememberReducedMotion()
    // 0 = a field on its own, 1 = the list hangs from it; drives the corners and the bottom edge.
    val join = animateFloatAsState(
        if (open) 1f else 0f,
        tween<Float>(Motion.SHORT_MS, easing = Motion.EmphasizedEasing).orSnap(reduced),
        label = "join",
    )

    FormFieldTypography {
        Column(modifier.fillMaxWidth()) {
            val colors = formFieldColors()
            val radius = formFieldRadius()
            val interaction = remember { MutableInteractionSource() }
            val isFocused by interaction.collectIsFocusedAsState()
            val isError = error != null
            // The list's surface, which the field takes on as the list attaches: the denser frost of raised glass.
            val listFill = tokens.frost
            val restFill = when {
                isError -> colors.errorContainerColor
                isFocused -> colors.focusedContainerColor
                else -> colors.unfocusedContainerColor
            }
            val edge by animateColorAsState(
                when {
                    !enabled -> colors.disabledIndicatorColor
                    isError -> colors.errorIndicatorColor
                    isFocused -> colors.focusedIndicatorColor
                    else -> colors.unfocusedIndicatorColor
                },
                tween(Motion.QUICK_MS),
                label = "edge",
            )
            val edgeWidth = if (isFocused || isError) FOCUSED_EDGE else RESTING_EDGE
            val textStyle = LocalTextStyle.current.merge(TextStyle(color = if (enabled) tokens.textPrimary else tokens.textSecondary))
            BasicTextField(
                value = shown,
                onValueChange = { raw ->
                    val next = raw.copy(text = filter(raw.text))
                    if (next.text != value) {
                        dismissed = false
                        browsing = false
                    }
                    field = next
                    if (next.text != value) onValueChange(next.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = FIELD_LABEL_ROOM)
                    .defaultMinSize(minHeight = OutlinedTextFieldDefaults.MinHeight)
                    .focusRequester(focusRequester)
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) browsing = false
                    }
                    .semantics { if (error != null) error(error) }
                    // The fill under the whole field, not in M3's container, which cuts the floating label's notch
                    // out of it: the label sits on the glass instead of in a hole through it.
                    .drawBehind {
                        val r = radius.toPx()
                        drawPath(roundedPath(Rect(Offset.Zero, size), r, r * (1f - join.value)), lerp(restFill, listFill, join.value))
                    },
                enabled = enabled,
                textStyle = textStyle,
                cursorBrush = SolidColor(if (isError) colors.errorCursorColor else colors.cursorColor),
                singleLine = true,
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(
                    capitalization = when {
                        keyboardType != KeyboardType.Text -> KeyboardCapitalization.None
                        capitalizeWords -> KeyboardCapitalization.Words
                        else -> KeyboardCapitalization.Sentences
                    },
                    keyboardType = keyboardType,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = {
                    dismissed = true
                    browsing = false
                    defaultKeyboardAction(ImeAction.Done)
                }),
                decorationBox = { inner ->
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = shown.text,
                        innerTextField = inner,
                        enabled = enabled,
                        singleLine = true,
                        visualTransformation = VisualTransformation.None,
                        interactionSource = interaction,
                        isError = isError,
                        label = { Text(label) },
                        trailingIcon = if (browse.isNotEmpty()) {
                            {
                                val description = stringResource(if (open) R.string.form_suggestions_hide else R.string.form_suggestions_show, label)
                                IconButton(
                                    onClick = {
                                        if (open) {
                                            dismissed = true
                                            browsing = false
                                        } else {
                                            browsing = true
                                            dismissed = false
                                            focusRequester.requestFocus()
                                        }
                                    },
                                    enabled = enabled,
                                    modifier = Modifier.semantics { contentDescription = description },
                                ) { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) }
                            }
                        } else {
                            null
                        },
                        colors = colors,
                        container = {
                            Box(
                                Modifier.fillMaxSize().drawBehind {
                                    drawFieldEdge(edge, edgeWidth.toPx(), radius.toPx(), join.value)
                                },
                            )
                        },
                    )
                },
            )
            SuggestionPanel(
                open = open,
                typed = value.trim(),
                suggestions = suggestions,
                fallbackIcon = fallbackIcon,
                icon = icon,
                fill = listFill,
                edge = edge,
                edgeWidth = edgeWidth,
                radius = radius,
                reduced = reduced,
                onPick = { name ->
                    field = TextFieldValue(name, TextRange(name.length))
                    dismissed = true
                    browsing = false
                    if (name != value) onValueChange(name)
                },
                onOther = {
                    dismissed = true
                    browsing = false
                },
            )
            // Under the whole piece, not between the field and its list; TalkBack hears it on the field (above).
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.errorSupportingTextColor,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).clearAndSetSemantics {},
                )
            }
        }
    }
}

/**
 * The field's outline for [FormAutocompleteField] (D-A69), in [edge]: sides and top always, the bottom corners rounding off by `1 - join` and the bottom edge fading by it, so at
 * `join` 1 the sides run straight down into the list's.
 */
private fun DrawScope.drawFieldEdge(edge: Color, edgeWidth: Float, radius: Float, join: Float) {
    val bottomRadius = radius * (1f - join)
    val half = edgeWidth / 2f
    val box = Rect(half, half, size.width - half, size.height - half)
    val r = (radius - half).coerceAtLeast(0f)
    val rb = (bottomRadius - half).coerceAtLeast(0f)
    val stroke = Stroke(edgeWidth)
    // Sides and top: from the start of the bottom-left corner, up, across and down to the start of the bottom-right.
    val upper = Path().apply {
        moveTo(box.left, box.bottom - rb)
        lineTo(box.left, box.top + r)
        arcTo(Rect(box.left, box.top, box.left + 2 * r, box.top + 2 * r), 180f, 90f, false)
        lineTo(box.right - r, box.top)
        arcTo(Rect(box.right - 2 * r, box.top, box.right, box.top + 2 * r), 270f, 90f, false)
        lineTo(box.right, if (join >= 1f) size.height else box.bottom - rb)
    }
    if (join >= 1f) upper.apply { moveTo(box.left, box.bottom - rb); lineTo(box.left, size.height) }
    drawPath(upper, edge, style = stroke)
    if (join < 1f) {
        val lower = Path().apply {
            moveTo(box.right, box.bottom - rb)
            arcTo(Rect(box.right - 2 * rb, box.bottom - 2 * rb, box.right, box.bottom), 0f, 90f, false)
            lineTo(box.left + rb, box.bottom)
            arcTo(Rect(box.left, box.bottom - 2 * rb, box.left + 2 * rb, box.bottom), 90f, 90f, false)
        }
        drawPath(lower, edge.copy(alpha = edge.alpha * (1f - join)), style = stroke)
    }
}

/** A rectangle path with [top] corners on its upper edge and [bottom] corners on its lower edge. */
private fun roundedPath(rect: Rect, top: Float, bottom: Float): Path = Path().apply {
    addRoundRect(
        RoundRect(
            rect,
            topLeft = CornerRadius(top),
            topRight = CornerRadius(top),
            bottomRight = CornerRadius(bottom),
            bottomLeft = CornerRadius(bottom),
        ),
    )
}

@Composable
private fun SuggestionPanel(
    open: Boolean,
    typed: String,
    suggestions: List<String>,
    fallbackIcon: ImageVector?,
    icon: @Composable (String) -> ImageBitmap?,
    fill: Color,
    edge: Color,
    edgeWidth: Dp,
    radius: Dp,
    reduced: Boolean,
    onPick: (String) -> Unit,
    onOther: () -> Unit,
) {
    val tokens = glass
    val bringIntoView = remember { BringIntoViewRequester() }
    AnimatedVisibility(
        visible = open,
        enter = (fadeIn(tween(Motion.SHORT_MS)) + expandVertically(Motion.gentleSpring())).orNone(reduced),
        exit = if (reduced) ExitTransition.None else fadeOut(tween(Motion.QUICK_MS)) + shrinkVertically(tween(Motion.QUICK_MS)),
    ) {
        // Scroll the whole panel into view in the form once it has its size (the field may sit just above the keyboard).
        LaunchedEffect(Unit) { bringIntoView.bringIntoView() }
        val shape = RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
        val count = pluralStringResource(R.plurals.form_suggestions_count, suggestions.size, suggestions.size)
        Column(
            Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(bringIntoView)
                .clip(shape)
                // D-A69: the field's frost and outline carried down: no top edge, the field's sides continue into
                // this one's, round its bottom corners; a hairline under the field separates the two.
                .drawWithContent {
                    drawRect(fill)
                    drawContent()
                    drawListEdge(edge, edgeWidth.toPx(), radius.toPx(), tokens.panelBorder, 12.dp.toPx())
                }
                .semantics {
                    paneTitle = count
                    collectionInfo = CollectionInfo(rowCount = suggestions.size + if (typed.isNotEmpty()) 1 else 0, columnCount = 1)
                },
        ) {
            val scroll = rememberScrollState()
            val viewport = remember { mutableIntStateOf(0) }
            // The drum curve shows while the list moves and settles flat again (D-A69); never under reduced motion.
            val curve = animateFloatAsState(
                if (scroll.isScrollInProgress && !reduced) 1f else 0f,
                tween(Motion.MEDIUM_MS),
                label = "curve",
            )
            Column(
                Modifier
                    .heightIn(max = SUGGESTIONS_MAX_HEIGHT)
                    .onSizeChanged { viewport.intValue = it.height }
                    .verticalScroll(scroll),
            ) {
                suggestions.forEachIndexed { index, name ->
                    // Where this row's centre sits in the list's content, written on placement, read while drawing.
                    val place = remember { mutableFloatStateOf(Float.NaN) }
                    SuggestionRow(
                        text = name,
                        bitmap = icon(name),
                        vector = fallbackIcon,
                        index = index,
                        onClick = { onPick(name) },
                        modifier = if (reduced) Modifier else Modifier.convexRow(scroll, viewport, curve, place),
                    )
                }
            }
            // "Keep what I typed" means nothing for a blank field (browsing the whole list): no row then.
            if (typed.isNotEmpty()) {
                RowDivider()
                val otherDescription = stringResource(R.string.form_suggestion_other_description, typed)
                SuggestionRow(
                    text = stringResource(R.string.form_suggestion_other),
                    supporting = stringResource(R.string.form_suggestion_other_keep, typed),
                    bitmap = null,
                    vector = Icons.Outlined.Edit,
                    index = suggestions.size,
                    description = otherDescription,
                    onClick = onOther,
                )
            }
        }
    }
}

/**
 * The list's outline (D-A69): down both sides from its top, round the bottom corners and across, in the field's
 * [edge] and [edgeWidth] so it reads as the field's own border carried on; and a [divider] hairline across the top,
 * [dividerInset] in from each side, where the field ends.
 */
private fun DrawScope.drawListEdge(edge: Color, edgeWidth: Float, radius: Float, divider: Color, dividerInset: Float) {
    val half = edgeWidth / 2f
    val box = Rect(half, 0f, size.width - half, size.height - half)
    val r = (radius - half).coerceAtLeast(0f)
    val path = Path().apply {
        moveTo(box.left, 0f)
        lineTo(box.left, box.bottom - r)
        arcTo(Rect(box.left, box.bottom - 2 * r, box.left + 2 * r, box.bottom), 180f, -90f, false)
        lineTo(box.right - r, box.bottom)
        arcTo(Rect(box.right - 2 * r, box.bottom - 2 * r, box.right, box.bottom), 90f, -90f, false)
        lineTo(box.right, 0f)
    }
    drawPath(path, edge, style = Stroke(edgeWidth))
    val y = 0.5.dp.toPx()
    drawLine(divider, Offset(dividerInset, y), Offset(size.width - dividerInset, y), strokeWidth = 1.dp.toPx())
}

/**
 * The drum look of a scrolling suggestion list (D-A69): by its distance from the middle of the list's [viewport],
 * a row tips back about the horizontal axis (up to [CONVEX_TILT]°), shrinks a little and fades a little, all scaled
 * by [curve] (0 at rest). Only the layer moves: layout, touch and TalkBack bounds stay the flat row's. A list
 * that doesn't scroll is never curved.
 */
private fun Modifier.convexRow(scroll: ScrollState, viewport: IntState, curve: State<Float>, place: MutableFloatState): Modifier =
    this
        .onPlaced { place.floatValue = it.positionInParent().y + it.size.height / 2f }
        .graphicsLayer {
            val k = curve.value
            val half = viewport.intValue / 2f
            val centre = place.floatValue
            if (k <= 0f || half <= 0f || centre.isNaN() || scroll.maxValue == 0) return@graphicsLayer
            val d = ((centre - scroll.value - half) / half).coerceIn(-1.2f, 1.2f)
            val d2 = d * d
            rotationX = -d * CONVEX_TILT * k
            scaleX = 1f - CONVEX_SHRINK * d2 * k
            scaleY = scaleX
            alpha = 1f - CONVEX_FADE * d2 * k
            cameraDistance = CONVEX_CAMERA * density
        }

/** The drum's strongest tilt at the list's edges, in degrees: subtle, the rows stay plainly readable. */
private const val CONVEX_TILT = 18f

/** How much an edge row shrinks (6 %) and fades (35 %) at full curve. */
private const val CONVEX_SHRINK = 0.06f
private const val CONVEX_FADE = 0.35f

/** A far camera keeps the tilt's perspective gentle (in dp, like the platform's default of 8). */
private const val CONVEX_CAMERA = 16f

@Composable
private fun SuggestionRow(
    text: String,
    bitmap: ImageBitmap?,
    vector: ImageVector?,
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    description: String? = null,
) {
    val tokens = glass
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                collectionItemInfo = CollectionItemInfo(rowIndex = index, rowSpan = 1, columnIndex = 0, columnSpan = 1)
                if (description != null) contentDescription = description
            }
            .heightIn(min = SUGGESTION_ROW)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            bitmap != null -> Image(bitmap, contentDescription = null, modifier = Modifier.size(24.dp))
            vector != null -> Icon(vector, contentDescription = null, tint = tokens.textSecondary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.padding(start = if (bitmap != null || vector != null) 16.dp else 0.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

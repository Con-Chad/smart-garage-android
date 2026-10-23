package io.github.conchad.smartgarage.ui.components.forms

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.orNone
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion

/** Height of one suggestion row at font scale 1 (also the touch-target minimum). */
private val SUGGESTION_ROW = 48.dp

/** About six rows show before the list scrolls; the half row hints that there is more. */
private val SUGGESTIONS_MAX_HEIGHT = SUGGESTION_ROW * 6.5f

/**
 * A one-line [FormTextField] that suggests values from a built-in list as the user types (D-A50): the matches
 * from [suggest] in a glass panel right under the field (in the form's own column, so it scrolls with the form
 * and stays above the keyboard), about six rows before it scrolls, and an "Other…" row pinned at the bottom that
 * keeps the typed text as it is. The value stays free text: tapping a suggestion only fills the field.
 *
 * The list shows while the field has focus, the text isn't blank and something matches. It closes on a pick, on
 * "Other…", on the keyboard's Done (which also hides the keyboard), on Back and when focus moves on; typing opens
 * it again. Rows are ≥ 48 dp and grow with the font scale; [icon] may return an image for a row (the insurer's
 * installed app icon), else [fallbackIcon] is drawn so the names line up. TalkBack hears the panel as a pane
 * titled with the number of suggestions, each row as a button in a list.
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

    Column(modifier.fillMaxWidth()) {
        FormFieldTypography {
            OutlinedTextField(
                colors = formFieldColors(),
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
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) browsing = false
                },
                enabled = enabled,
                singleLine = true,
                isError = error != null,
                supportingText = supportingText(error, null),
                trailingIcon = if (browse.isNotEmpty()) {
                    {
                        val expanded = open
                        val description = stringResource(if (expanded) R.string.form_suggestions_hide else R.string.form_suggestions_show, label)
                        IconButton(
                            onClick = {
                                if (expanded) {
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
                        ) { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                    }
                } else {
                    null
                },
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
            )
        }
        SuggestionPanel(
            open = open,
            typed = value.trim(),
            suggestions = suggestions,
            fallbackIcon = fallbackIcon,
            icon = icon,
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
    }
}

@Composable
private fun SuggestionPanel(
    open: Boolean,
    typed: String,
    suggestions: List<String>,
    fallbackIcon: ImageVector?,
    icon: @Composable (String) -> ImageBitmap?,
    onPick: (String) -> Unit,
    onOther: () -> Unit,
) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    val bringIntoView = remember { BringIntoViewRequester() }
    AnimatedVisibility(
        visible = open,
        enter = (fadeIn(tween(Motion.SHORT_MS)) + expandVertically(Motion.gentleSpring())).orNone(reduced),
        exit = if (reduced) ExitTransition.None else fadeOut(tween(Motion.QUICK_MS)) + shrinkVertically(tween(Motion.QUICK_MS)),
    ) {
        // Scroll the whole panel into view in the form once it has its size (the field may sit just above the keyboard).
        LaunchedEffect(Unit) { bringIntoView.bringIntoView() }
        val shape = RoundedCornerShape(tokens.radiusMedium)
        val count = pluralStringResource(R.plurals.form_suggestions_count, suggestions.size, suggestions.size)
        GlassPanel(
            shape = shape,
            elevated = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .bringIntoViewRequester(bringIntoView)
                // Readable over any aurora, like the glass menus (D-A31): the page colour under the frosted fill, in the
                // panel's rounded shape (a plain rect showed square corners past the rim).
                .drawBehind { drawOutline(shape.createOutline(size, layoutDirection, this), tokens.pageBackground.copy(alpha = 0.94f)) }
                .semantics {
                    paneTitle = count
                    collectionInfo = CollectionInfo(rowCount = suggestions.size + if (typed.isNotEmpty()) 1 else 0, columnCount = 1)
                },
        ) {
            Column(Modifier.heightIn(max = SUGGESTIONS_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                suggestions.forEachIndexed { index, name ->
                    SuggestionRow(
                        text = name,
                        bitmap = icon(name),
                        vector = fallbackIcon,
                        index = index,
                        onClick = { onPick(name) },
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

@Composable
private fun SuggestionRow(
    text: String,
    bitmap: ImageBitmap?,
    vector: ImageVector?,
    index: Int,
    onClick: () -> Unit,
    supporting: String? = null,
    description: String? = null,
) {
    val tokens = glass
    Row(
        Modifier
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

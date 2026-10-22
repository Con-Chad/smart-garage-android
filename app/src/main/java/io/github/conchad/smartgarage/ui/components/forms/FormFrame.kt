package io.github.conchad.smartgarage.ui.components.forms

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R

/**
 * Full-screen editor (D-A20): a close button and title on top, the fields in a scrolling column, and a pinned
 * [SaveBar] at the bottom that rises with the keyboard. Back or close with unsaved changes asks first.
 */
@Composable
fun FormScreen(
    title: String,
    onClose: () -> Unit,
    saveLabel: String,
    canSave: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    dirty: Boolean = false,
    validation: String? = null,
    snackbarHost: SnackbarHostState? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val close = { if (dirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
                },
            )
        },
        bottomBar = { SaveBar(saveLabel, enabled = canSave, saving = saving, onSave = onSave, validation = validation) },
        snackbarHost = { snackbarHost?.let { SnackbarHost(it) } },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.form_discard_title),
            body = stringResource(R.string.form_discard_body),
            confirmLabel = stringResource(R.string.form_discard_confirm),
            onConfirm = {
                confirmDiscard = false
                onClose()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/** A short editor in a bottom sheet (the odometer update): title, a few fields, the same [SaveBar]. */
@Composable
fun FormSheet(
    title: String,
    onDismiss: () -> Unit,
    saveLabel: String,
    canSave: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    validation: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
            SaveBar(saveLabel, enabled = canSave, saving = saving, onSave = onSave, validation = validation, inSheet = true)
        }
    }
}

/**
 * The one way a form saves: a full-width button, disabled until the form is valid, with the reason it is disabled
 * (or the first error) in the line above it, so a disabled button never leaves the user guessing. Shows a spinner
 * in place of the label while the write is being queued.
 */
@Composable
fun SaveBar(
    label: String,
    enabled: Boolean,
    saving: Boolean,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    validation: String? = null,
    inSheet: Boolean = false,
) {
    Surface(color = if (inSheet) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.fillMaxWidth().then(if (inSheet) Modifier else Modifier.navigationBarsPadding().imePadding())) {
            if (!inSheet) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AnimatedVisibility(visible = validation != null) {
                Text(
                    validation.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(
                onClick = onSave,
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp),
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.size(12.dp))
                }
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Small primary label between groups of fields ("Parts and fluids"). */
@Composable
fun FormSection(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/**
 * A group of optional fields folded away behind its title ("Specs"), so a long form opens short. The header is a
 * 48 dp toggle that reads as expanded or collapsed to TalkBack; [summary] under the title says what is inside.
 */
@Composable
fun FormDisclosure(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = stringResource(if (expanded) R.string.form_expanded else R.string.form_collapsed)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onToggle, role = Role.Button)
                .semantics(mergeDescendants = true) { stateDescription = state }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** Yes/no question before something that can't be taken back (delete, odometer going backwards, discard). */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Breathing room at the end of a scrolling form so the last field clears the save bar. */
@Composable
fun FormEndSpacer() = Spacer(Modifier.height(16.dp))

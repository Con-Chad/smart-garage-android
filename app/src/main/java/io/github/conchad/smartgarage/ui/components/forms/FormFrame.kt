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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.GlassAlertDialog
import io.github.conchad.smartgarage.ui.glass.GlassBottomSheet
import io.github.conchad.smartgarage.ui.glass.GlassButton
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.MetallicText
import io.github.conchad.smartgarage.ui.glass.auroraInk
import io.github.conchad.smartgarage.ui.glass.SkeletonFields
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.ui.glass.glass
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import io.github.conchad.smartgarage.ui.glass.GlassIconBadge
import io.github.conchad.smartgarage.ui.glass.LocalAuroraTint
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.drawerCollapse
import io.github.conchad.smartgarage.ui.glass.drawerExpand
import io.github.conchad.smartgarage.ui.glass.orNone
import io.github.conchad.smartgarage.ui.glass.orSnap
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import io.github.conchad.smartgarage.ui.glass.topFadeEdge

/**
 * Full-screen editor (D-A20): a close button and title on top, the fields in a scrolling column. Back or close with
 * unsaved changes asks first.
 *
 * D-A52: no pinned bar above the keyboard any more (it rode up with the IME and covered the fields and the
 * autocomplete panel). Save is in two places, both always reachable: a compact Save pill (check + "Save") at the end
 * of the top bar, and the full [SaveBar] (the reason it is disabled + the big button) inline at the end of the form's
 * panel. The column itself pads for the keyboard, so a focused field is brought into view above it.
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
    /**
     * D-A69: the caller lays its fields out on its own glass cards ([FormCard], [FormDisclosureCard]), spaced like
     * Home's; the inline Save then sits on a card of its own at the end. False: everything on the one panel.
     */
    grouped: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val close = { if (dirty) confirmDiscard = true else onClose() }
    BackHandler(enabled = dirty) { confirmDiscard = true }
    // D-A76: the fields fade out under the bar once scrolled (no divider, no scrolled tint).
    val formScroll = rememberScrollState()

    // On the shell's aurora (D-A30): no surface of its own, the fields on one glass panel so they never float on
    // the ribbons, and the save bar on the same frosted fill.
    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                // On the aurora: the adaptive ink (D-A46).
                title = { Text(title, Modifier.auroraInk(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
                },
                actions = { TopBarSave(enabled = canSave, saving = saving, onSave = onSave) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
            )
        },
        snackbarHost = { snackbarHost?.let { SnackbarHost(it) } },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
                .topFadeEdge(formScroll, glass.contentFadeLift)
                .verticalScroll(formScroll)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp + glass.contentFadeLift, bottom = 8.dp),
        ) {
            if (grouped) {
                Column(verticalArrangement = Arrangement.spacedBy(FORM_CARD_GAP), content = content)
                GlassPanel(Modifier.fillMaxWidth().padding(top = FORM_CARD_GAP), contentPadding = 16.dp, glow = cardGlow()) {
                    SaveBar(saveLabel, enabled = canSave, saving = saving, onSave = onSave, validation = validation, inSheet = true, inset = 0.dp)
                }
            } else {
                GlassPanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                    SaveBar(saveLabel, enabled = canSave, saving = saving, onSave = onSave, validation = validation, inSheet = true, inset = 0.dp)
                }
            }
        }
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
    GlassBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()) {
            MetallicText(title, style = MaterialTheme.typography.titleLarge, animated = true, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
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
 * The top bar's Save (D-A52): a compact sheen pill with a check and "Save", so saving never needs the keyboard put
 * away or the form scrolled to its end. Disabled like [GlassButton] (pill fill, secondary text); a spinner replaces
 * the check while the write is queued.
 */
@Composable
private fun TopBarSave(enabled: Boolean, saving: Boolean, onSave: () -> Unit) {
    GlassButton(
        onClick = onSave,
        enabled = enabled && !saving,
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = Modifier.padding(end = 8.dp),
    ) {
        if (saving) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = glass.textSecondary)
        } else {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
    /** Side inset of the line and the button; 0 inside a panel that already pads (the full-screen form, D-A52). */
    inset: Dp = 16.dp,
) {
    // On the screen, the frosted bar; in a sheet, nothing of its own: the sheet's glass is already behind it (D-A41).
    Surface(color = if (inSheet) Color.Transparent else glass.panel, contentColor = glass.textPrimary, modifier = modifier) {
        Column(Modifier.fillMaxWidth().then(if (inSheet) Modifier else Modifier.navigationBarsPadding().imePadding())) {
            if (!inSheet) HorizontalDivider(color = glass.panelBorder)
            AnimatedVisibility(visible = validation != null) {
                Text(
                    validation.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = inset, end = inset, top = 12.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            GlassButton(
                onClick = onSave,
                enabled = enabled && !saving,
                modifier = Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 16.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = glass.textSecondary)
                    Spacer(Modifier.size(12.dp))
                }
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** The space between a grouped form's cards (D-A69), as between Home's. */
private val FORM_CARD_GAP = 16.dp

/** The plate glow of a form card: the aurora's tint, like Home's cards (D-A30). */
@Composable
private fun cardGlow(): Color = LocalAuroraTint.current.effective ?: MaterialTheme.colorScheme.primary

/**
 * One group of a grouped [FormScreen] on its own glass card (D-A69): the panel fill, rim and plate glow of Home's
 * cards, a header of [icon] in a small glass badge and the animated metallic [title] (a heading for TalkBack) with an
 * optional [action] at its end, then the fields 12 dp apart. Without [title] the card holds only the fields.
 */
@Composable
fun FormCard(
    title: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(modifier.fillMaxWidth(), contentPadding = 16.dp, glow = cardGlow()) {
        if (title != null) {
            FormCardHeader(title, icon, action)
            Spacer(Modifier.height(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun FormCardHeader(title: String, icon: ImageVector?, action: (@Composable () -> Unit)?, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            GlassIconBadge(icon, size = 36.dp)
            Spacer(Modifier.width(12.dp))
        }
        MetallicText(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            animated = true,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action?.invoke()
        trailing?.invoke()
    }
}

/**
 * [FormDisclosure] as its own glass card (D-A69, "Specs" on the vehicle form): the [FormCard] header is the toggle
 * (≥ 48 dp, a button with its expanded / collapsed state), [summary] under it, a chevron that turns, and the body
 * glides open inside the glass ([drawerExpand] / [drawerCollapse], snapping under reduced motion).
 */
@Composable
fun FormDisclosureCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = glass
    val reduced = rememberReducedMotion()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, Motion.standardSpring<Float>().orSnap(reduced), label = "chevron")
    val state = stringResource(if (expanded) R.string.form_expanded else R.string.form_collapsed)
    GlassPanel(modifier.fillMaxWidth(), glow = cardGlow()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle, role = Role.Button)
                .semantics(mergeDescendants = true) { stateDescription = state }
                .heightIn(min = 56.dp)
                .padding(16.dp),
        ) {
            FormCardHeader(
                title,
                icon,
                action = null,
                trailing = {
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = tokens.sheen,
                        modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation },
                    )
                },
            )
            summary?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.textSecondary,
                    modifier = Modifier.padding(start = if (icon != null) 48.dp else 0.dp, top = 2.dp),
                )
            }
        }
        AnimatedVisibility(expanded, enter = drawerExpand().orNone(reduced), exit = drawerCollapse().orNone(reduced)) {
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** Small sheen label between groups of fields ("Parts and fluids"). */
@Composable
fun FormSection(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = glass.sheen, modifier = Modifier.weight(1f))
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
                Text(title, style = MaterialTheme.typography.titleSmall, color = glass.sheen)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = glass.textSecondary) }
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = glass.sheen)
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
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else glassTextButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Placeholder fields while an editor loads the record it edits (D-A41): field-shaped skeleton bones inside the
 * form's glass panel instead of a spinner, so the form appears where it will be.
 */
@Composable
fun FormSkeleton() = SkeletonFields(stringResource(R.string.loading))

/** Breathing room between the last field and the form's inline Save (D-A52). */
@Composable
fun FormEndSpacer() = Spacer(Modifier.height(16.dp))

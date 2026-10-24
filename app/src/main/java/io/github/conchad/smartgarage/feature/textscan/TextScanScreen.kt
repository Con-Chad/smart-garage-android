package io.github.conchad.smartgarage.feature.textscan

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.feature.receipts.ocr.DeviceReadFailure
import io.github.conchad.smartgarage.feature.receipts.rememberReceiptCamera
import io.github.conchad.smartgarage.feature.receipts.rememberReceiptGallery
import io.github.conchad.smartgarage.ui.components.IconBadge
import io.github.conchad.smartgarage.ui.components.forms.ActionButton
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.auroraInk
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import io.github.conchad.smartgarage.ui.glass.headerDivider
import io.github.conchad.smartgarage.ui.glass.orNone
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * "Scan text" (D-A64), from the Home fan's scan menu: take or pick a photo, the phone reads it (no upload), and the
 * recognised lines are boxed on the photo. Tapping boxes picks lines; the text below holds the picked lines (or all
 * of them, in reading order), can be edited, copied or shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextScanScreen(onClose: () -> Unit, viewModel: TextScanViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.canScrollBackward } }
    val reduced = rememberReducedMotion()

    val camera = rememberReceiptCamera(viewModel::startCamera, viewModel::captured, viewModel::cameraUnavailable)
    val gallery = rememberReceiptGallery(viewModel::picked, viewModel::galleryUnavailable)
    val message = state.message?.let { stringResource(it.res) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.clearMessage()
        }
    }
    val copied = stringResource(R.string.text_scan_copied)
    val noShare = stringResource(R.string.text_scan_no_share)
    val shareTitle = stringResource(R.string.text_scan_share_title)
    val clipLabel = stringResource(R.string.text_scan_title)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                modifier = Modifier.headerDivider(scrolled),
                title = { Text(stringResource(R.string.text_scan_title), Modifier.auroraInk(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = glass.panel),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            AnimatedContent(
                targetState = state.phase,
                transitionSpec = {
                    fadeIn(tween(Motion.SHORT_MS)).orNone(reduced) togetherWith fadeOut(tween(Motion.QUICK_MS)).orNone(reduced)
                },
                contentKey = { it::class },
                label = "textScanPhase",
            ) { phase ->
                when (phase) {
                    TextScanPhase.Idle -> SourcePanel(
                        icon = Icons.Outlined.TextFields,
                        message = stringResource(R.string.text_scan_intro),
                        onCamera = camera,
                        onGallery = gallery,
                    )
                    TextScanPhase.Reading -> ReadingPanel()
                    TextScanPhase.Empty -> SourcePanel(
                        icon = Icons.Outlined.ErrorOutline,
                        message = stringResource(R.string.receipt_scan_no_text),
                        onCamera = camera,
                        onGallery = gallery,
                        announce = true,
                    )
                    is TextScanPhase.Failed -> SourcePanel(
                        icon = Icons.Outlined.ErrorOutline,
                        message = stringResource(phase.reason.messageRes()),
                        onCamera = camera,
                        onGallery = gallery,
                        announce = true,
                    )
                    is TextScanPhase.Done -> ResultContent(
                        result = phase.result,
                        state = state,
                        onToggle = viewModel::toggle,
                        onSelectAll = viewModel::selectAll,
                        onClear = viewModel::clearSelection,
                        onText = viewModel::setText,
                        onCopy = {
                            copyText(context, clipLabel, state.text)
                            // Android 13+ confirms a copy itself; older versions get the snackbar.
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                        },
                        onShare = {
                            if (!shareText(context, shareTitle, state.text)) scope.launch { snackbar.showSnackbar(noShare) }
                        },
                        onCamera = camera,
                        onGallery = gallery,
                    )
                }
            }
        }
    }
}

/** Where nothing has been read (yet, or usefully): a short message and the two sources. */
@Composable
private fun SourcePanel(icon: ImageVector, message: String, onCamera: () -> Unit, onGallery: () -> Unit, announce: Boolean = false) {
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { if (announce) liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon)
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
        }
        SourceButtons(onCamera, onGallery, Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun SourceButtons(onCamera: () -> Unit, onGallery: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SourceButton(Icons.Outlined.PhotoCamera, stringResource(R.string.file_take_photo), onCamera, Modifier.weight(1f))
        SourceButton(Icons.Outlined.PhotoLibrary, stringResource(R.string.receipt_scan_pick_gallery), onGallery, Modifier.weight(1f))
    }
}

@Composable
private fun SourceButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    GlassOutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReadingPanel() {
    GlassPanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = glass.sheen)
            Text(
                stringResource(R.string.text_scan_reading),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun ResultContent(
    result: ScanResult,
    state: TextScanState,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onText: (String) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    val selection = state.selection
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
            ScanOverlay(result, selection.selected, onToggle)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selection.none) {
                        stringResource(R.string.text_scan_hint_tap)
                    } else {
                        pluralStringResource(R.plurals.text_scan_selected, selection.count, selection.selected.size, selection.count)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary,
                    modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                TextButton(onClick = onSelectAll, enabled = !selection.all, colors = glassTextButtonColors(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.text_scan_select_all))
                }
                TextButton(onClick = onClear, enabled = !selection.none, colors = glassTextButtonColors(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.text_scan_clear))
                }
            }
        }
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            FormNotesField(
                value = state.text,
                onValueChange = onText,
                label = stringResource(R.string.text_scan_text_label),
                supporting = stringResource(if (selection.none) R.string.text_scan_text_all else R.string.text_scan_text_selected),
            )
            val hasText = state.text.isNotBlank()
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(stringResource(R.string.text_scan_copy), Icons.Outlined.ContentCopy, onCopy, Modifier.weight(1f), enabled = hasText)
                ActionButton(stringResource(R.string.text_scan_share), Icons.Outlined.Share, onShare, Modifier.weight(1f), enabled = hasText)
            }
        }
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            Text(stringResource(R.string.text_scan_another), style = MaterialTheme.typography.bodyMedium, color = glass.textSecondary)
            SourceButtons(onCamera, onGallery, Modifier.padding(top = 12.dp))
        }
    }
}

/**
 * The photo with a box over each recognised line. Taps go through [hitTest] (every box answers within 48 dp, the
 * nearest wins); for TalkBack each box is a checkbox node named by its text, bounded by exactly its box (no 48 dp
 * growth, so explore-by-touch lands on the box under the finger), in reading order.
 */
@Composable
private fun ScanOverlay(result: ScanResult, selected: Set<Int>, onToggle: (Int) -> Unit) {
    val tokens = glass
    val density = LocalDensity.current
    val toggle by rememberUpdatedState(onToggle)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = (maxWidth * (result.height.toFloat() / result.width)).coerceAtMost(MAX_IMAGE_HEIGHT)
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = with(density) { height.toPx() }
        val fit = remember(result, widthPx, heightPx) { ImageFit.fit(result.width, result.height, widthPx, heightPx) } ?: return@BoxWithConstraints
        val rects = remember(result, fit) { result.blocks.map { fit.toView(it.bounds) } }
        val outlines = remember(result, fit) {
            result.blocks.map { block ->
                Path().apply {
                    block.points.forEachIndexed { i, p -> if (i == 0) moveTo(fit.x(p.x), fit.y(p.y)) else lineTo(fit.x(p.x), fit.y(p.y)) }
                    close()
                }
            }
        }
        val minTarget = with(density) { MIN_TARGET.toPx() }
        val stroke = with(density) { Stroke(1.5.dp.toPx()) }
        val selectedStroke = with(density) { Stroke(2.5.dp.toPx()) }
        val imageDescription = pluralStringResource(R.plurals.text_scan_image_desc, result.blocks.size, result.blocks.size)
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(tokens.radiusMedium))
                .pointerInput(rects, minTarget) {
                    detectTapGestures { tap -> hitTest(rects, tap.x, tap.y, minTarget)?.let(toggle) }
                }
                .drawBehind {
                    drawImage(
                        result.image,
                        dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
                        dstSize = IntSize((result.width * fit.scale).roundToInt(), (result.height * fit.scale).roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                    outlines.forEachIndexed { i, outline ->
                        val on = i in selected
                        drawPath(outline, tokens.sheen.copy(alpha = if (on) 0.38f else 0.10f))
                        drawPath(outline, tokens.sheen.copy(alpha = if (on) 1f else 0.75f), style = if (on) selectedStroke else stroke)
                    }
                }
                .semantics {
                    isTraversalGroup = true
                    contentDescription = imageDescription
                },
        ) {
            // Compose's accessibility layer grows every node with a click action to the view configuration's minimum
            // touch target (48 dp) for its reported bounds and explore-by-touch hit test, so on a dense photo the
            // nodes overlapped and cropped each other. Here the box is the node: no growth. Sighted taps keep their
            // 48 dp reach through hitTest on the photo above (D-A68).
            val base = LocalViewConfiguration.current
            val exact = remember(base) { ExactTouchBounds(base) }
            CompositionLocalProvider(LocalViewConfiguration provides exact) {
                result.rows.flatten().forEachIndexed { order, i ->
                    val r = rects[i]
                    val on = i in selected
                    Box(
                        Modifier
                            .offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
                            .semantics {
                                contentDescription = result.blocks[i].text
                                role = Role.Checkbox
                                toggleableState = ToggleableState(on)
                                traversalIndex = order.toFloat()
                                onClick {
                                    toggle(i)
                                    true
                                }
                            },
                    )
                }
            }
        }
    }
}

/** [base] with no minimum touch target, so accessibility nodes keep their real bounds. */
private class ExactTouchBounds(base: ViewConfiguration) : ViewConfiguration by base {
    override val minimumTouchTargetSize: DpSize get() = DpSize.Zero
}

private val MAX_IMAGE_HEIGHT = 520.dp
private val MIN_TARGET = 48.dp

private fun DeviceReadFailure.messageRes(): Int = when (this) {
    DeviceReadFailure.Unreadable -> R.string.text_scan_unreadable
    DeviceReadFailure.OutOfMemory -> R.string.text_scan_memory
    DeviceReadFailure.EngineFailed, DeviceReadFailure.Cancelled -> R.string.text_scan_failed
}

private fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The system share sheet for [text]; false when no app takes plain text. */
private fun shareText(context: Context, title: String, text: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    return try {
        context.startActivity(Intent.createChooser(send, title))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

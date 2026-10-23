package io.github.conchad.smartgarage.feature.documents.scan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.forms.ConfirmDialog
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.glass.GlassButton
import io.github.conchad.smartgarage.ui.glass.GlassFilterChip
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.GlassPanel
import io.github.conchad.smartgarage.ui.glass.auroraInk
import io.github.conchad.smartgarage.ui.glass.glass
import kotlin.math.hypot

/**
 * The document scanner (D-A59), shown by the document form in place of its fields while a scan is open: the
 * corner screen for a fresh photo, then the page list with the filter, the name and "Use scan". On the shell's
 * aurora like the form; the photos come from the system camera via [onCapture] (the page index for a retake).
 */
@Composable
fun DocumentScannerScreen(session: ScanSession, controller: ScanController, onCapture: (retake: Int?) -> Unit) {
    val crop = session.cropIndex?.let { i -> session.pages.getOrNull(i)?.let { i to it } }
    if (crop != null) {
        BackHandler { controller.doneCropping() }
        CropScreen(
            page = crop.second,
            busy = session.busy,
            onCorners = { controller.setCorners(crop.first, it) },
            onRetake = { onCapture(crop.first) },
            onDetect = { controller.detect(crop.first) },
            onDone = controller::doneCropping,
        )
    } else {
        var confirmDiscard by rememberSaveable { mutableStateOf(false) }
        val close = { if (session.pages.isEmpty()) controller.discard() else confirmDiscard = true }
        BackHandler(onBack = close)
        ReviewScreen(session, controller, onClose = close, onAddPage = { onCapture(null) })
        if (confirmDiscard) {
            ConfirmDialog(
                title = stringResource(R.string.scan_discard_title),
                body = stringResource(R.string.scan_discard_body),
                confirmLabel = stringResource(R.string.form_discard_confirm),
                onConfirm = {
                    confirmDiscard = false
                    controller.discard()
                },
                onDismiss = { confirmDiscard = false },
                destructive = true,
            )
        }
    }
}

@Composable
private fun ScannerScaffold(
    title: String,
    navigation: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(title, Modifier.auroraInk(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = navigation,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = glass.panel),
            )
        },
    ) { padding -> content(Modifier.padding(padding).consumeWindowInsets(padding)) }
}

@Composable
private fun CropScreen(
    page: ScanPage,
    busy: Boolean,
    onCorners: (List<Pt>) -> Unit,
    onRetake: () -> Unit,
    onDetect: () -> Unit,
    onDone: () -> Unit,
) {
    ScannerScaffold(
        title = stringResource(R.string.scan_adjust_title),
        navigation = {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
        },
    ) { modifier ->
        Column(modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassPanel(Modifier.fillMaxWidth().weight(1f), contentPadding = 12.dp) {
                Text(
                    stringResource(R.string.scan_adjust_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = glass.textSecondary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (busy) Busy(stringResource(R.string.capture_processing)) else CornerEditor(page, onCorners, Modifier.fillMaxWidth().weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassOutlinedButton(onClick = onRetake, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.scan_retake), modifier = Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val whole = page.corners == ScanGeometry.FULL
                GlassOutlinedButton(onClick = { if (whole) onDetect() else onCorners(ScanGeometry.FULL) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Crop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        stringResource(if (whole) R.string.scan_detect else R.string.scan_whole_photo),
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            GlassButton(onClick = onDone, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.scan_done)) }
        }
    }
}

/** The photo with the page outline and four handles; a drag picks the handle nearest the finger (within 48 dp). */
@Composable
private fun CornerEditor(page: ScanPage, onCorners: (List<Pt>) -> Unit, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, page.path) { value = ScanImaging.preview(page.path)?.asImageBitmap() }
    var points by remember(page.path) { mutableStateOf(page.corners) }
    var active by remember { mutableIntStateOf(-1) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(page.corners) { if (active < 0) points = page.corners }
    val latestOnCorners by rememberUpdatedState(onCorners)
    val tokens = glass
    val description = stringResource(R.string.scan_adjust_hint)

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image == null) {
            CircularProgressIndicator(color = tokens.sheen)
            return@BoxWithConstraints
        }
        val aspect = image.width.toFloat() / image.height
        val boxAspect = maxWidth.value / maxHeight.value
        Box(
            Modifier
                .aspectRatio(aspect, matchHeightConstraintsFirst = boxAspect > aspect)
                .semantics { contentDescription = description }
                .pointerInput(page.path) {
                    val reach = 48.dp.toPx()
                    detectDragGestures(
                        onDragStart = { at ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val nearest = points.indices.minBy { hypot(points[it].x * w - at.x, points[it].y * h - at.y) }
                            active = if (hypot(points[nearest].x * w - at.x, points[nearest].y * h - at.y) <= reach) nearest else -1
                            // The handle keeps its offset from the finger, so it doesn't jump onto the fingertip.
                            grab = if (active >= 0) Offset(points[active].x * w - at.x, points[active].y * h - at.y) else Offset.Zero
                        },
                        onDragEnd = {
                            if (active >= 0) latestOnCorners(points)
                            active = -1
                        },
                        onDragCancel = {
                            if (active >= 0) latestOnCorners(points)
                            active = -1
                        },
                        onDrag = { change, _ ->
                            val i = active
                            if (i >= 0) {
                                change.consume()
                                val at = change.position + grab
                                val moved = ScanGeometry.clamp(Pt(at.x / size.width, at.y / size.height))
                                points = points.toMutableList().apply { set(i, moved) }
                            }
                        },
                    )
                },
        ) {
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)))
            Canvas(Modifier.fillMaxSize()) {
                val px = points.map { Offset(it.x * size.width, it.y * size.height) }
                val ordered = ScanGeometry.orderCorners(points).map { Offset(it.x * size.width, it.y * size.height) }
                val outline = Path().apply {
                    moveTo(ordered[0].x, ordered[0].y)
                    for (k in 1 until 4) lineTo(ordered[k].x, ordered[k].y)
                    close()
                }
                val shade = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addPath(outline)
                }
                drawPath(shade, Color.Black.copy(alpha = 0.45f))
                drawPath(outline, tokens.sheen, style = Stroke(width = 2.dp.toPx()))
                px.forEachIndexed { i, c ->
                    val r = (if (i == active) 18 else 13).dp.toPx()
                    drawCircle(tokens.sheen.copy(alpha = 0.35f), radius = r, center = c)
                    drawCircle(Color.White, radius = r, center = c, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(Color.White, radius = 2.5.dp.toPx(), center = c)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewScreen(session: ScanSession, controller: ScanController, onClose: () -> Unit, onAddPage: () -> Unit) {
    ScannerScaffold(
        title = stringResource(R.string.scan_document),
        navigation = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close)) }
        },
    ) { modifier ->
        Column(
            modifier
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            GlassPanel(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScanFilter.entries.forEach { f ->
                            GlassFilterChip(
                                selected = session.filter == f,
                                onClick = { controller.setFilter(f) },
                                label = stringResource(
                                    when (f) {
                                        ScanFilter.Original -> R.string.scan_filter_original
                                        ScanFilter.Greyscale -> R.string.scan_filter_greyscale
                                        ScanFilter.BlackWhite -> R.string.scan_filter_bw
                                    },
                                ),
                            )
                        }
                    }
                    session.pages.forEachIndexed { index, page ->
                        PageCard(index, session.pages.size, page, session.filter, controller)
                    }
                    if (session.busy) Busy(stringResource(R.string.capture_processing))
                    GlassOutlinedButton(
                        onClick = onAddPage,
                        enabled = session.canAddPage && !session.busy && !session.exporting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.scan_add_page), modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        stringResource(R.string.scan_pages_hint, ScanGeometry.MAX_PAGES),
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    FormTextField(
                        value = session.name,
                        onValueChange = controller::setName,
                        label = stringResource(R.string.scan_name),
                        error = if (session.name.isBlank()) stringResource(R.string.scan_name_needed) else null,
                        enabled = !session.exporting,
                    )
                    when {
                        session.exporting -> Busy(stringResource(R.string.scan_exporting))
                        session.failed -> Text(
                            stringResource(R.string.scan_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    GlassButton(onClick = controller::finish, enabled = session.canFinish, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scan_use))
                    }
                }
            }
        }
    }
}

/** One page: its flattened, filtered preview (tap to adjust corners) and the page actions. */
@Composable
private fun PageCard(index: Int, count: Int, page: ScanPage, filter: ScanFilter, controller: ScanController) {
    val preview by produceState<ImageBitmap?>(null, page, filter) {
        value = ScanImaging.render(page, filter, THUMB_EDGE)?.asImageBitmap()
    }
    val tokens = glass
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.scan_page, index + 1),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = stringResource(R.string.scan_adjust_title)) { controller.editCorners(index) },
            contentAlignment = Alignment.Center,
        ) {
            val image = preview
            if (image == null) {
                CircularProgressIndicator(color = tokens.sheen, modifier = Modifier.padding(32.dp))
            } else {
                Image(
                    image,
                    contentDescription = stringResource(R.string.scan_page, index + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .aspectRatio(image.width.toFloat() / image.height)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = { controller.editCorners(index) }) {
                Icon(Icons.Outlined.Crop, contentDescription = stringResource(R.string.scan_adjust_title), tint = tokens.sheen)
            }
            IconButton(onClick = { controller.rotate(index) }) {
                Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = stringResource(R.string.scan_rotate), tint = tokens.sheen)
            }
            IconButton(onClick = { controller.move(index, -1) }, enabled = index > 0) {
                Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.scan_move_up))
            }
            IconButton(onClick = { controller.move(index, 1) }, enabled = index < count - 1) {
                Icon(Icons.Outlined.ArrowDownward, contentDescription = stringResource(R.string.scan_move_down))
            }
            IconButton(onClick = { controller.remove(index) }) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.scan_delete_page))
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = glass.sheen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = glass.textSecondary, modifier = Modifier.padding(start = 12.dp))
    }
}

private const val THUMB_EDGE = 900

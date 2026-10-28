package io.github.conchad.smartgarage.feature.vehicles.photo

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.VEHICLE_PHOTO_ASPECT
import io.github.conchad.smartgarage.ui.glass.AuroraBackground
import io.github.conchad.smartgarage.ui.glass.GlassButton
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.Motion
import io.github.conchad.smartgarage.ui.glass.auroraInk
import io.github.conchad.smartgarage.ui.glass.glass
import io.github.conchad.smartgarage.ui.glass.rememberReducedMotion
import kotlin.math.roundToInt

/** How dark the photo is outside the crop frame (as the document scanner's corner screen, a touch deeper). */
private const val OUTSIDE_SHADE_ALPHA = 0.55f

/** The rule-of-thirds guides inside the frame. */
private const val GUIDE_ALPHA = 0.35f

/** [CropState] in saved state, so a rotation or process death keeps the crop the user had. */
private val CropStateSaver = Saver<CropState, List<Float>>(
    save = { listOf(it.turns.toFloat(), it.zoom, it.centerX, it.centerY) },
    restore = { CropState(it[0].toInt(), it[1], it[2], it[3]) },
)

/**
 * The crop step after a vehicle photo is picked (D-A82): full screen on the aurora, the photo under a fixed frame of
 * the cards' [VEHICLE_PHOTO_ASPECT] (outside it shaded), pinch to zoom and drag to move. "Rotate" turns the photo
 * 90° clockwise and starts the frame over; "Reset" goes back to the photo as picked. Cancel, Back and the close icon
 * upload nothing; Done hands the photo and the crop to [onDone].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehiclePhotoCropScreen(uri: Uri, onCancel: () -> Unit, onDone: (source: Bitmap, crop: CropState, aspect: Float) -> Unit) {
    val context = LocalContext.current
    // Null while decoding; Result.failure when the photo can't be read (then only Cancel is offered).
    val loaded by produceState<Result<Bitmap>?>(null, uri) {
        value = PhotoCropImaging.load(context, uri)?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
    }
    var crop by rememberSaveable(uri, stateSaver = CropStateSaver) { mutableStateOf(CropState()) }
    val source = loaded?.getOrNull()
    val image = remember(source) { source?.asImageBitmap() }
    val tokens = glass

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        AuroraBackground(tint = MaterialTheme.colorScheme.primary) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.photo_crop_title), Modifier.auroraInk(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
                    )
                },
            ) { padding ->
                Column(
                    Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(if (loaded?.isFailure == true) R.string.file_unreadable else R.string.photo_crop_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.textSecondary,
                        modifier = Modifier.fillMaxWidth().auroraInk(),
                    )
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        when {
                            loaded == null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(color = tokens.sheen)
                                Text(stringResource(R.string.capture_processing), color = tokens.textSecondary, textAlign = TextAlign.Center)
                            }
                            image != null -> CropEditor(image, crop, onCrop = { crop = it }, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassOutlinedButton(onClick = { crop = PhotoCropGeometry.rotate(crop) }, enabled = image != null, modifier = Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Outlined.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.photo_crop_rotate), modifier = Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        GlassOutlinedButton(onClick = { crop = CropState() }, enabled = image != null && crop != CropState(), modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.photo_crop_reset), modifier = Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassOutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        GlassButton(onClick = { source?.let { onDone(it, crop, VEHICLE_PHOTO_ASPECT) } }, enabled = source != null, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.scan_done), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The photo turned by [crop]'s quarter turns, scaled and placed so the crop sits in a centred [VEHICLE_PHOTO_ASPECT]
 * frame; a pinch or drag anywhere on it moves the photo under the frame. TalkBack gets "Zoom in" / "Zoom out".
 */
@Composable
private fun CropEditor(image: ImageBitmap, crop: CropState, onCrop: (CropState) -> Unit, modifier: Modifier = Modifier) {
    val tokens = glass
    val latestCrop by rememberUpdatedState(crop)
    val latestOnCrop by rememberUpdatedState(onCrop)
    val (turnedW, turnedH) = PhotoCropGeometry.turnedSize(image.width, image.height, crop.turns).let { it.first.toFloat() to it.second.toFloat() }
    val aspect = VEHICLE_PHOTO_ASPECT
    // Rotate shows the photo turning its quarter (a snap under reduced motion); Reset and restored state jump. The
    // angle ends on the same value mod 360, so the snap after the turn is invisible. The crop follows the finger.
    val reduced = rememberReducedMotion()
    val turnAngle = remember { Animatable(90f * crop.turns) }
    LaunchedEffect(crop.turns) {
        val settled = (turnAngle.value / 90f).roundToInt()
        val target = 90f * crop.turns
        if (!reduced && PhotoCropGeometry.normalTurns(settled + 1) == crop.turns) {
            turnAngle.animateTo(90f * (settled + 1), tween(Motion.STANDARD_MS, easing = Motion.EmphasizedEasing))
        }
        turnAngle.snapTo(target)
    }
    val description = stringResource(R.string.photo_crop_hint)
    val zoomIn = stringResource(R.string.photo_crop_zoom_in)
    val zoomOut = stringResource(R.string.photo_crop_zoom_out)
    val inset = 16.dp

    Box(
        modifier
            .clipToBounds()
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(zoomIn) { latestOnCrop(PhotoCropGeometry.clamp(latestCrop.copy(zoom = latestCrop.zoom * PhotoCropGeometry.ZOOM_STEP), turnedW, turnedH, aspect)); true },
                    CustomAccessibilityAction(zoomOut) { latestOnCrop(PhotoCropGeometry.clamp(latestCrop.copy(zoom = latestCrop.zoom / PhotoCropGeometry.ZOOM_STEP), turnedW, turnedH, aspect)); true },
                )
            }
            .pointerInput(image, crop.turns) {
                val margin = inset.toPx()
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val frame = frameRect(Size(size.width.toFloat(), size.height.toFloat()), margin, aspect)
                    latestOnCrop(
                        PhotoCropGeometry.transform(
                            latestCrop, turnedW, turnedH, aspect, frame.width,
                            centroidX = centroid.x - frame.center.x, centroidY = centroid.y - frame.center.y,
                            panX = pan.x, panY = pan.y, zoomBy = zoom,
                        ),
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val frame = frameRect(size, inset.toPx(), aspect)
            val clamped = PhotoCropGeometry.clamp(crop, turnedW, turnedH, aspect)
            val scale = PhotoCropGeometry.displayScale(clamped, turnedW, turnedH, aspect, frame.width)
            // The turned image's centre on screen: the frame centre minus the crop centre's offset into the image.
            val centre = Offset(
                frame.center.x - (clamped.centerX - 0.5f) * turnedW * scale,
                frame.center.y - (clamped.centerY - 0.5f) * turnedH * scale,
            )
            withTransform({
                translate(centre.x, centre.y)
                rotate(turnAngle.value, pivot = Offset.Zero)
                scale(scale, scale, pivot = Offset.Zero)
                translate(-image.width / 2f, -image.height / 2f)
            }) {
                drawImage(image)
            }
            val shade = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRect(frame)
            }
            drawPath(shade, Color.Black.copy(alpha = OUTSIDE_SHADE_ALPHA))
            val guide = tokens.onPhoto.copy(alpha = GUIDE_ALPHA)
            val thin = 1.dp.toPx()
            for (k in 1..2) {
                val x = frame.left + frame.width * k / 3f
                val y = frame.top + frame.height * k / 3f
                drawLine(guide, Offset(x, frame.top), Offset(x, frame.bottom), thin)
                drawLine(guide, Offset(frame.left, y), Offset(frame.right, y), thin)
            }
            drawRect(tokens.onPhoto, topLeft = frame.topLeft, size = frame.size, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/** The crop frame: the largest [aspect] rect inside [size] less [margin] on every side, centred. */
private fun frameRect(size: Size, margin: Float, aspect: Float): Rect {
    val availW = (size.width - 2 * margin).coerceAtLeast(1f)
    val availH = (size.height - 2 * margin).coerceAtLeast(1f)
    val w = minOf(availW, availH * aspect)
    val h = w / aspect
    val left = (size.width - w) / 2f
    val top = (size.height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

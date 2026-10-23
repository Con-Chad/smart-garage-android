package io.github.conchad.smartgarage.feature.receipts

import android.content.ActivityNotFoundException
import android.net.Uri
import android.text.format.Formatter as FileSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.conchad.smartgarage.ui.components.DetailSheet
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.files.PickedFile
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.glass

/**
 * The camera launcher for a receipt scan: [start] gives the target URI, [onResult] gets whether a photo was taken,
 * [onNoCamera] runs when the phone has no camera app. Returns the action for the "Scan receipt" button.
 */
@Composable
fun rememberReceiptCamera(start: () -> Uri, onResult: (Boolean) -> Unit, onNoCamera: () -> Unit): () -> Unit {
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), onResult)
    return {
        try {
            takePicture.launch(start())
        } catch (_: ActivityNotFoundException) {
            onResult(false)
            onNoCamera()
        }
    }
}

/**
 * The Photo Picker for a receipt scan (D-A56): `PickVisualMedia` images only, no storage permission. On phones
 * without the system picker, androidx falls back to the document picker by itself; no Play Services involved.
 * [onPicked] gets the chosen photo's URI, or nothing when the user backed out.
 */
@Composable
fun rememberReceiptGallery(onPicked: (Uri) -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onPicked) }
    return {
        try {
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: ActivityNotFoundException) {
            onUnavailable()
        }
    }
}

/**
 * The receipt-scan buttons on every form that scans (fuel, service record, expense; D-A27, D-A53, D-A56): "Scan
 * receipt" takes a new photo with the camera, "Pick from gallery" uses an existing one through the Photo Picker.
 * Both are outlined, 48 dp, side by side with equal width, with [hint] beneath. While the photo is being prepared
 * or read, both are disabled (a second tap can't start a second scan) and the hint line becomes a spinner with
 * "Reading the receipt…" ("…on this phone" while it is read on the device, D-A63).
 */
@Composable
fun ReceiptScanButtons(
    scanning: Boolean,
    enabled: Boolean,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    hint: String = stringResource(R.string.receipt_scan_fuel_hint_both),
    /** The photo is being read on the phone (D-A63), not by the server: the progress line says so. */
    onDevice: Boolean = false,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanSourceButton(Icons.Outlined.DocumentScanner, stringResource(R.string.fuel_scan_receipt), enabled && !scanning, onCamera, Modifier.weight(1f))
            ScanSourceButton(Icons.Outlined.PhotoLibrary, stringResource(R.string.receipt_scan_pick_gallery), enabled && !scanning, onGallery, Modifier.weight(1f))
        }
        if (scanning) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = glass.sheen)
                Text(
                    stringResource(if (onDevice) R.string.receipt_scan_reading_device else R.string.fuel_scan_in_progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = glass.textSecondary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ScanSourceButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    GlassOutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(label, modifier = Modifier.padding(start = 8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The receipt photo kept on the form after a scan: a plain row on the form's panel (no inner box, D-A32) with a remove control. */
@Composable
fun ReceiptPhotoRow(photo: PickedFile, onRemove: () -> Unit, linked: Boolean = false) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Image, contentDescription = null, tint = glass.textSecondary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp).semantics(mergeDescendants = true) {}) {
            Text(stringResource(R.string.fuel_scan_photo_kept), style = MaterialTheme.typography.bodyMedium)
            val size = FileSize.formatShortFileSize(context, photo.sizeBytes)
            Text(
                if (linked) stringResource(R.string.receipt_scan_attached, size) else size,
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.fuel_scan_remove_photo))
        }
    }
}

/**
 * The short note after a scan (D-A53): which fields to check (confidence below 0.8) and the warnings in words, as
 * one or two lines with an info icon. Nothing when the review is empty and there is no [extra] line.
 */
@Composable
fun ScanReviewNote(review: ScanReview?, extra: String? = null) {
    if ((review == null || review.isEmpty) && extra == null) return
    val lines = buildList {
        if (review != null && review.check.isNotEmpty()) {
            add(stringResource(R.string.receipt_scan_check, review.check.map { stringResource(it.labelRes()) }.joinToString(", ")))
        }
        review?.warnings?.forEach { add(stringResource(it.messageRes())) }
        extra?.let(::add)
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = glass.sheen, modifier = Modifier.size(20.dp))
        Text(
            lines.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

private fun ScanField.labelRes(): Int = when (this) {
    ScanField.Date -> R.string.field_date
    ScanField.Volume -> R.string.fuel_volume
    ScanField.Price -> R.string.receipt_field_price
    ScanField.Total -> R.string.fuel_form_total
    ScanField.Station -> R.string.fuel_station
    ScanField.Odometer -> R.string.field_odometer
    ScanField.Vendor -> R.string.field_vendor
    ScanField.ServiceType -> R.string.field_schedule
}

private fun ScanWarning.messageRes(): Int = when (this) {
    ScanWarning.AmountsMismatch -> R.string.receipt_warn_amounts
    ScanWarning.CurrencyAssumed -> R.string.receipt_warn_currency_assumed
    ScanWarning.CurrencyConflict -> R.string.receipt_warn_currency_conflict
    ScanWarning.DateInFuture -> R.string.receipt_warn_date_future
    ScanWarning.DateUnreadable -> R.string.receipt_warn_date_unreadable
    ScanWarning.VolumeUnreadable -> R.string.receipt_warn_volume_unreadable
    ScanWarning.TotalUnreadable -> R.string.receipt_warn_total_unreadable
    ScanWarning.OdometerImplausible -> R.string.receipt_warn_odometer
    ScanWarning.PickSchedule -> R.string.receipt_warn_pick_schedule
    ScanWarning.ItemsTruncated -> R.string.receipt_warn_items_truncated
    ScanWarning.SuggestExpense -> R.string.receipt_warn_suggest_expense
    ScanWarning.SuggestRecord -> R.string.receipt_warn_suggest_record
}

fun ScanMessage.messageRes(): Int = when (this) {
    ScanMessage.Unavailable -> R.string.fuel_scan_unavailable
    ScanMessage.ViewOnly -> R.string.view_only
    ScanMessage.NotFuelReceipt -> R.string.fuel_scan_not_receipt
    ScanMessage.NotServiceReceipt -> R.string.receipt_scan_not_service
    ScanMessage.FuelReceiptOnServiceForm -> R.string.receipt_scan_is_fuel
    ScanMessage.RateLimited -> R.string.fuel_scan_rate_limited
    ScanMessage.FileRejected -> R.string.fuel_scan_file_rejected
    ScanMessage.Unreachable -> R.string.fuel_scan_unreachable
    ScanMessage.Failed -> R.string.fuel_scan_failed
    ScanMessage.FailedPhotoKept -> R.string.receipt_scan_failed_kept
    ScanMessage.NoCamera -> R.string.camera_unavailable
    ScanMessage.NoGallery -> R.string.receipt_scan_no_gallery
    ScanMessage.NoText -> R.string.receipt_scan_no_text
    ScanMessage.DeviceOutOfMemory -> R.string.receipt_scan_device_memory
    ScanMessage.DeviceFailed -> R.string.receipt_scan_device_failed
}

/**
 * The note after a receipt was read on the phone (D-A63): "Read on this phone. Check the values: …" naming every
 * field it filled (nothing it read is trusted unchecked), the parser's warnings in words, then [extra]. Below it,
 * "Show recognised text" opens a sheet with the lines it read; the sheet opens by itself when nothing could be used.
 */
@Composable
fun DeviceReviewNote(review: DeviceReview, extra: String? = null) {
    var showText by rememberSaveable(review) { mutableStateOf(review.nothingFilled && review.lines.isNotEmpty()) }
    val fields = review.filled.map { stringResource(it.labelRes()) }.joinToString(", ")
    val lines = buildList {
        add(
            when {
                review.nothingFilled && review.serverTried -> stringResource(R.string.receipt_device_nothing_after_server)
                review.nothingFilled -> stringResource(R.string.receipt_device_nothing)
                review.serverTried -> stringResource(R.string.receipt_device_read_after_server, fields)
                else -> stringResource(R.string.receipt_device_read, fields)
            },
        )
        review.warnings.forEach { add(stringResource(it.messageRes())) }
        extra?.let(::add)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = glass.sheen, modifier = Modifier.size(20.dp))
            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = glass.textSecondary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (review.lines.isNotEmpty()) {
            TextButton(
                onClick = { showText = true },
                colors = glassTextButtonColors(),
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.receipt_device_show_text))
            }
        }
    }
    if (showText) RecognisedTextSheet(review.lines, onDismiss = { showText = false })
}

/**
 * The lines the phone recognised, top to bottom, selectable so a value can be copied into a field by hand. Kept
 * free of the forms so the standalone text scanner can show its text the same way.
 */
@Composable
fun RecognisedTextSheet(lines: List<String>, onDismiss: () -> Unit) {
    DetailSheet(
        icon = Icons.AutoMirrored.Outlined.TextSnippet,
        title = stringResource(R.string.receipt_device_text_title),
        subtitle = stringResource(R.string.receipt_device_text_subtitle),
        onDismiss = onDismiss,
    ) {
        SelectionContainer {
            Text(
                lines.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

private fun DeviceWarning.messageRes(): Int = when (this) {
    DeviceWarning.TotalUnlabelled -> R.string.receipt_device_warn_total_unlabelled
    DeviceWarning.AmountsMismatch -> R.string.receipt_warn_amounts
    DeviceWarning.PriceDerived -> R.string.receipt_device_warn_price_derived
    DeviceWarning.FromGallons -> R.string.receipt_device_warn_gallons
    DeviceWarning.DateAmbiguous -> R.string.receipt_device_warn_date_ambiguous
    DeviceWarning.HardToRead -> R.string.receipt_device_warn_hard_to_read
    DeviceWarning.FuelReceipt -> R.string.receipt_scan_is_fuel
}

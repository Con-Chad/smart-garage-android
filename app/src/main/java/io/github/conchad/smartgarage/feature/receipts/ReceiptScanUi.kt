package io.github.conchad.smartgarage.feature.receipts

import android.content.ActivityNotFoundException
import android.net.Uri
import android.text.format.Formatter as FileSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * "Scan receipt" (fuel note 1, D-A27; on the service form too since D-A53): a full-width outlined button, 48 dp like
 * [ActionButton], with [hint] beneath. While the photo is being prepared or read, the icon becomes a spinner and the
 * label says so; the button is disabled meanwhile, so a second tap can't start a second scan.
 */
@Composable
fun ScanReceiptButton(scanning: Boolean, enabled: Boolean, onClick: () -> Unit, hint: String = stringResource(R.string.fuel_scan_hint)) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GlassOutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = glass.sheen)
            } else {
                Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                stringResource(if (scanning) R.string.fuel_scan_in_progress else R.string.fuel_scan_receipt),
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = glass.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
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
}

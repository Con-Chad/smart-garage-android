package io.github.conchad.smartgarage.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.cache.OutboxOp
import io.github.conchad.smartgarage.core.data.FailedWrite
import io.github.conchad.smartgarage.core.data.WriteDenied
import io.github.conchad.smartgarage.core.data.WriteFailure
import io.github.conchad.smartgarage.ui.sync.SyncInfo
import io.github.conchad.smartgarage.ui.sync.WritingViewModel

// Words for the write queue (D-A16, D-A18): why a write was refused locally, why the server rejected one, and the
// banner + list every writing screen shows.

@StringRes
fun WriteDenied.messageRes(): Int = when (this) {
    WriteDenied.NotSignedIn -> R.string.write_denied_not_signed_in
    WriteDenied.Unknown -> R.string.write_denied_unknown
    WriteDenied.ViewOnly -> R.string.write_denied_view_only
    WriteDenied.OwnerOnly -> R.string.write_denied_owner_only
}

@StringRes
fun WriteFailure.messageRes(): Int = when (this) {
    WriteFailure.ViewOnly -> R.string.write_failed_view_only
    WriteFailure.NotFound -> R.string.write_failed_not_found
    WriteFailure.Invalid -> R.string.write_failed_invalid
    WriteFailure.FileTooLarge -> R.string.write_failed_file_too_large
    WriteFailure.UnsupportedFile -> R.string.write_failed_unsupported_file
    WriteFailure.DependencyFailed -> R.string.write_failed_dependency
    WriteFailure.FileMissing -> R.string.write_failed_file_missing
    WriteFailure.Other -> R.string.write_failed_other
}

/** "Fill-up", "Service record"… for the failed list; the resource name is the server's, not the user's. */
@StringRes
private fun resourceLabel(resource: String): Int = when (resource) {
    "fuelEntries" -> R.string.write_resource_fuel
    "maintenanceRecords" -> R.string.write_resource_record
    "odometerLogs" -> R.string.write_resource_odometer
    "schedules" -> R.string.write_resource_schedule
    "expenses" -> R.string.write_resource_expense
    "vehicles" -> R.string.write_resource_vehicle
    "notes" -> R.string.write_resource_note
    "wishlistItems" -> R.string.write_resource_wishlist
    "documents" -> R.string.write_resource_document
    "inspections" -> R.string.write_resource_inspection
    "insurancePolicies" -> R.string.write_resource_insurance
    else -> R.string.write_resource_other
}

@StringRes
private fun opLabel(op: OutboxOp): Int = when (op) {
    OutboxOp.CREATE -> R.string.write_op_create
    OutboxOp.UPDATE -> R.string.write_op_update
    OutboxOp.DELETE -> R.string.write_op_delete
}

/** Shows the screen VM's one-shot [WritingViewModel.denied] reason as a snackbar and clears it. */
@Composable
fun DeniedSnackbar(viewModel: WritingViewModel, host: SnackbarHostState) {
    val denied by viewModel.denied.collectAsStateWithLifecycle()
    val message = denied?.let { stringResource(it.messageRes()) }
    LaunchedEffect(message) {
        if (message != null) {
            host.showSnackbar(message)
            viewModel.clearDenied()
        }
    }
}

/**
 * [SyncBanner] for a writing screen: the "couldn't be saved" state opens [FailedWritesSheet], which this hosts.
 * Drop-in for `SyncBanner(sync, onRetry)` on the four data screens.
 */
@Composable
fun WriteQueueBanner(sync: SyncInfo, viewModel: WritingViewModel, modifier: Modifier = Modifier) {
    var showFailed by rememberSaveable { mutableStateOf(false) }
    SyncBanner(sync, onRetry = viewModel::refresh, modifier = modifier, onShowFailed = { showFailed = true })
    if (showFailed) {
        val failed by viewModel.failedWrites.collectAsStateWithLifecycle()
        FailedWritesSheet(
            failed = failed,
            onRetry = viewModel::retryWrite,
            onDismiss = viewModel::dismissWrite,
            onClose = { showFailed = false },
        )
    }
}

/**
 * The writes the server rejected (D-A18), oldest first: what it was, why, and the server's own words as a second
 * line. Retry re-queues it; Dismiss forgets it (the cache already matches the server). Closes itself once empty.
 */
@Composable
fun FailedWritesSheet(failed: List<FailedWrite>, onRetry: (String) -> Unit, onDismiss: (String) -> Unit, onClose: () -> Unit) {
    LaunchedEffect(failed.isEmpty()) { if (failed.isEmpty()) onClose() }
    DetailSheet(
        icon = Icons.Outlined.ErrorOutline,
        title = pluralStringResource(R.plurals.failed_writes_title, failed.size, failed.size),
        subtitle = stringResource(R.string.failed_writes_subtitle),
        onDismiss = onClose,
    ) {
        failed.forEachIndexed { index, write ->
            if (index > 0) RowDivider()
            FailedWriteRow(write, onRetry = { onRetry(write.key) }, onDismiss = { onDismiss(write.key) })
        }
    }
}

@Composable
private fun FailedWriteRow(write: FailedWrite, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.failed_write_what, stringResource(opLabel(write.op)), stringResource(resourceLabel(write.resource))),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(write.failure.messageRes()), style = MaterialTheme.typography.bodyMedium)
            write.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
            if (write.retryable) {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

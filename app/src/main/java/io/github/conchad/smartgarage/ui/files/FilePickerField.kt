package io.github.conchad.smartgarage.ui.files

import android.text.format.Formatter as FileSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.glass.GlassOutlinedButton
import io.github.conchad.smartgarage.ui.glass.glass

/**
 * The file slot of an upload form (D-A21): a "Choose file" button (and "Take photo" when [onCapture] is given,
 * D-A23), then the chosen file's name, type and size with a "Change" button; an error line below says why the file
 * can't be sent. [onPick] opens the system picker; [busy] shows while a photo is being prepared. [onScan] adds a
 * third button, "Scan document" (D-A59).
 */
@Composable
fun FilePickerField(
    file: PickedFile?,
    kind: UploadKind,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    onCapture: (() -> Unit)? = null,
    onScan: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (busy) {
            Text(
                stringResource(R.string.capture_processing),
                style = MaterialTheme.typography.bodyMedium,
                color = glass.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else if (file == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Three sources (D-A59) stack icon over label so each third keeps its words on two lines at most.
                val stacked = onScan != null
                PickerButton(Icons.Outlined.AttachFile, stringResource(R.string.file_choose), stacked, onPick)
                if (onCapture != null) PickerButton(Icons.Outlined.PhotoCamera, stringResource(R.string.file_take_photo), stacked, onCapture)
                if (onScan != null) PickerButton(Icons.Outlined.DocumentScanner, stringResource(R.string.scan_document), stacked, onScan)
            }
        } else {
            // The chosen file as a plain row on the form's panel (D-A32: no inner box), like the fill-up form's receipt row.
            val actions: @Composable () -> Unit = {
                GlassOutlinedButton(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_change)) }
                if (onCapture != null) {
                    IconButton(onClick = onCapture, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = stringResource(R.string.file_take_photo))
                    }
                }
                if (onScan != null) {
                    IconButton(onClick = onScan, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.DocumentScanner, contentDescription = stringResource(R.string.scan_document))
                    }
                }
            }
            // Three actions don't leave the name enough room beside them (D-A59): they go on a line of their own.
            val actionsBelow = onScan != null
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = when {
                    file.mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
                    file.mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
                    else -> Icons.Outlined.Description
                }
                Icon(icon, contentDescription = null, tint = glass.textSecondary, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp).semantics(mergeDescendants = true) {}) {
                    Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(file.mimeType ?: stringResource(R.string.file_unknown_type), FileSize.formatShortFileSize(context, file.sizeBytes))
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = glass.textSecondary,
                    )
                }
                if (!actionsBelow) actions()
            }
            if (actionsBelow) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) { actions() }
            }
        }
        Text(
            error ?: stringResource(
                if (kind == UploadKind.Document) R.string.file_hint_document else R.string.file_hint_photo,
                FileSize.formatShortFileSize(context, FileRules.maxBytes(kind)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else glass.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** One of the picker's source buttons, a third of the row; [stacked] puts the icon above the label. */
@Composable
private fun RowScope.PickerButton(icon: ImageVector, label: String, stacked: Boolean, onClick: () -> Unit) {
    GlassOutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f).heightIn(min = if (stacked) 72.dp else 48.dp),
        contentPadding = if (stacked) PaddingValues(horizontal = 4.dp, vertical = 10.dp) else ButtonDefaults.ContentPadding,
        // A tall stadium would read as a circle; three tiles get the panel's rounded corners instead.
        shape = if (stacked) RoundedCornerShape(16.dp) else ButtonDefaults.outlinedShape,
    ) {
        if (stacked) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(label, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, modifier = Modifier.padding(start = 8.dp), maxLines = 2)
        }
    }
}

/** The words for a [FileProblem], with the limit for [kind] filled in. */
@Composable
fun fileProblemText(problem: FileProblem, kind: UploadKind): String {
    val context = LocalContext.current
    return when (problem) {
        FileProblem.TooLarge -> stringResource(R.string.file_too_large, FileSize.formatShortFileSize(context, FileRules.maxBytes(kind)))
        FileProblem.UnsupportedType -> stringResource(
            if (kind == UploadKind.Document) R.string.file_unsupported_document else R.string.file_unsupported_photo,
        )
    }
}

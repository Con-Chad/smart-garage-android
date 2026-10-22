package io.github.conchad.smartgarage.ui.files

import android.text.format.Formatter as FileSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.IconBadge

/**
 * The file slot of an upload form (D-A21): a "Choose file" button (and "Take photo" when [onCapture] is given,
 * D-A23), then the chosen file's name, type and size with a "Change" button; an error line below says why the file
 * can't be sent. [onPick] opens the system picker; [busy] shows while a photo is being prepared.
 */
@Composable
fun FilePickerField(
    file: PickedFile?,
    kind: UploadKind,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    onCapture: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    val context = LocalContext.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (busy) {
            Text(
                stringResource(R.string.capture_processing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else if (file == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.file_choose), modifier = Modifier.padding(start = 8.dp), maxLines = 2)
                }
                if (onCapture != null) {
                    OutlinedButton(onClick = onCapture, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.file_take_photo), modifier = Modifier.padding(start = 8.dp), maxLines = 2)
                    }
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val icon = when {
                        file.mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
                        file.mimeType?.startsWith("image/") == true -> Icons.Outlined.Image
                        else -> Icons.Outlined.Description
                    }
                    IconBadge(icon)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp).semantics(mergeDescendants = true) {}) {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(file.mimeType ?: stringResource(R.string.file_unknown_type), FileSize.formatShortFileSize(context, file.sizeBytes))
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onPick, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_change)) }
                    if (onCapture != null) {
                        IconButton(onClick = onCapture, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.PhotoCamera, contentDescription = stringResource(R.string.file_take_photo))
                        }
                    }
                }
            }
        }
        Text(
            error ?: stringResource(
                if (kind == UploadKind.Document) R.string.file_hint_document else R.string.file_hint_photo,
                FileSize.formatShortFileSize(context, FileRules.maxBytes(kind)),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
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

package io.github.conchad.smartgarage.feature.sections

import android.text.format.Formatter as FileSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.DocumentSheet
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.documentCategoryLabel
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.components.shortName
import io.github.conchad.smartgarage.ui.glass.GlassAlertDialog
import io.github.conchad.smartgarage.ui.glass.glassTextButtonColors

@Composable
internal fun DocumentsScreen(onBack: () -> Unit, actions: SectionActions, viewModel: DocumentsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val opening by viewModel.open.collectAsStateWithLifecycle()
    val fmt = state.formatter
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val showVehicle = state.selectedVehicleId == null && state.vehicles.size > 1
    val add = { actions.onAddDocument(state.selectedVehicleId) }
    SectionScaffold(
        title = stringResource(R.string.nav_documents),
        onBack = onBack,
        viewModel = viewModel,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty(),
        emptyIcon = Icons.Outlined.Description,
        emptyTitle = stringResource(R.string.documents_empty_title),
        floatingActionButton = {
            if (state.loaded && state.canEditAny) {
                AddFab(stringResource(R.string.action_add_document), onClick = add, icon = Icons.Outlined.UploadFile)
            }
        },
    ) {
        item { GroupHeader(pluralStringResource(R.plurals.documents_count, state.data.size, state.data.size)) }
        item {
            GroupCard {
                state.data.forEachIndexed { index, document ->
                    if (index > 0) RowDivider()
                    val loading = (opening as? DocumentOpen.Loading)?.documentId == document.id
                    SectionRow(
                        icon = when {
                            document.isImage -> Icons.Outlined.Image
                            document.mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
                            else -> Icons.Outlined.Description
                        },
                        title = document.displayName,
                        detail = listOfNotNull(
                            document.category?.let { documentCategoryLabel(it) }
                                ?: stringResource(R.string.documents_vehicle_photo).takeIf { document.purpose == "VEHICLE_IMAGE" },
                            state.vehicleById[document.vehicleId]?.shortName?.takeIf { showVehicle },
                            document.createdAt.toInstantOrNull()?.let(fmt::shortDate),
                            FileSize.formatShortFileSize(context, document.sizeBytes),
                        ).joinToString(" · "),
                        onClick = { open = document.id },
                        trailing = if (loading) "…" else null,
                        pending = document.id in pendingIds,
                    )
                }
            }
        }
    }
    state.data.firstOrNull { it.id == open }?.let { document ->
        DocumentSheet(
            document,
            state.vehicleById[document.vehicleId],
            fmt,
            onDismiss = { open = null },
            onOpen = {
                open = null
                viewModel.open(document)
            },
            pending = document.id in pendingIds,
            onDelete = {
                open = null
                viewModel.deleteDocument(document.id)
            },
        )
    }
    when (val current = opening) {
        is DocumentOpen.Image -> ImageViewer(current, onDismiss = viewModel::dismiss)
        DocumentOpen.Failed -> InfoDialog(stringResource(R.string.documents_failed), viewModel::dismiss)
        DocumentOpen.InvalidId -> InfoDialog(stringResource(R.string.documents_invalid_id), viewModel::dismiss)
        DocumentOpen.NoViewer -> InfoDialog(stringResource(R.string.documents_no_viewer), viewModel::dismiss)
        else -> Unit
    }
}

@Composable
private fun ImageViewer(image: DocumentOpen.Image, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                model = image.file,
                contentDescription = image.document.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 8.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }
        }
    }
}

@Composable
private fun InfoDialog(text: String, onDismiss: () -> Unit) {
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        confirmButton = { TextButton(onClick = onDismiss, colors = glassTextButtonColors()) { Text(stringResource(R.string.action_ok)) } },
        text = { Text(text) },
    )
}

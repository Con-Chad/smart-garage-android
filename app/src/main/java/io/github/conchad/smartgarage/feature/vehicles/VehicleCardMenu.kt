package io.github.conchad.smartgarage.feature.vehicles

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.VehicleLocalPrefs
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.files.UploadKind
import io.github.conchad.smartgarage.ui.files.fileProblemText
import io.github.conchad.smartgarage.ui.files.inspect
import io.github.conchad.smartgarage.ui.files.open
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The long-press actions shared by the home card and the vehicles list (D-A24): change / upload the photo (the
 * same `setVehiclePhoto` path as the detail screen, D-A21) and a device-local nickname. Its own ViewModel so the
 * two screens' ViewModels stay untouched and both get the same denied/rejected feedback.
 */
@HiltViewModel
class VehicleCardActionsViewModel @Inject constructor(
    private val prefs: VehicleLocalPrefs,
    control: SyncControl,
    writer: GarageWriter,
    @ApplicationContext private val context: Context,
) : WritingViewModel(control, writer) {

    val nicknames: StateFlow<Map<String, String>> = prefs.nicknames.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val photoPickState = MutableStateFlow<PhotoPick?>(null)
    val photoPick: StateFlow<PhotoPick?> = photoPickState.asStateFlow()

    fun clearPhotoPick() {
        photoPickState.value = null
    }

    /** Same checks as the detail screen: readable, an accepted image type, under the size limit, then queued. */
    fun setPhoto(vehicleId: String, uri: Uri) {
        viewModelScope.launch {
            val resolver = context.contentResolver
            val file = resolver.inspect(uri) ?: return@launch run { photoPickState.value = PhotoPick.Unreadable }
            file.problem(UploadKind.VehiclePhoto)?.let { photoPickState.value = PhotoPick.Rejected(it); return@launch }
            val stream = resolver.open(file) ?: return@launch run { photoPickState.value = PhotoPick.Unreadable }
            report(writer.setVehiclePhoto(vehicleId, file.name, file.mimeType ?: "application/octet-stream", stream))
        }
    }

    fun rename(vehicleId: String, nickname: String?) {
        viewModelScope.launch { prefs.setNickname(vehicleId, nickname) }
    }
}

/** What a card's long-press menu can start, wired once per screen with [rememberVehicleCardActions]. */
@Stable
class VehicleCardActions internal constructor(
    val nicknames: Map<String, String>,
    private val pick: (vehicleId: String) -> Unit,
    private val startRename: (Vehicle) -> Unit,
) {
    fun nicknameOf(vehicle: Vehicle): String? = nicknames[vehicle.id]

    /** The card's title: the nickname when set, else the server's "2019 Mazda MX-5". */
    fun titleOf(vehicle: Vehicle): String = nicknameOf(vehicle) ?: vehicle.displayName

    fun changePhoto(vehicle: Vehicle) = pick(vehicle.id)

    fun rename(vehicle: Vehicle) = startRename(vehicle)
}

/**
 * Hosts the photo picker, the rename dialog and the "photo refused" snackbar for a screen. The picker result
 * arrives after the launching card may have gone, so the vehicle id is kept in saved state until it does.
 */
@Composable
fun rememberVehicleCardActions(viewModel: VehicleCardActionsViewModel, snackbar: SnackbarHostState): VehicleCardActions {
    val nicknames by viewModel.nicknames.collectAsStateWithLifecycle()
    val photoPick by viewModel.photoPick.collectAsStateWithLifecycle()
    DeniedSnackbar(viewModel, snackbar)

    val photoMessage = when (val pick = photoPick) {
        null -> null
        PhotoPick.Unreadable -> stringResource(R.string.file_unreadable)
        is PhotoPick.Rejected -> fileProblemText(pick.problem, UploadKind.VehiclePhoto)
    }
    LaunchedEffect(photoMessage) {
        if (photoMessage != null) {
            snackbar.showSnackbar(photoMessage)
            viewModel.clearPhotoPick()
        }
    }

    var pendingPhotoFor by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val vehicleId = pendingPhotoFor
        pendingPhotoFor = null
        if (uri != null && vehicleId != null) viewModel.setPhoto(vehicleId, uri)
    }

    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var renamingName by rememberSaveable { mutableStateOf("") }
    val renameId = renaming
    if (renameId != null) {
        RenameVehicleDialog(
            serverName = renamingName,
            current = nicknames[renameId],
            onSave = { name ->
                viewModel.rename(renameId, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    return remember(nicknames, picker) {
        VehicleCardActions(
            nicknames = nicknames,
            pick = { vehicleId ->
                pendingPhotoFor = vehicleId
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            startRename = { vehicle ->
                renamingName = vehicle.displayName
                renaming = vehicle.id
            },
        )
    }
}

/**
 * The long-press menu over a vehicle photo: "Change photo" or "Upload photo" (whichever the vehicle needs; only
 * with edit access), "Rename vehicle" (local, so everyone), and "Open vehicle" so the menu also works as the
 * card's actions for people who reach it by other means.
 */
@Composable
fun VehicleCardMenu(
    vehicle: Vehicle,
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: VehicleCardActions,
    onOpen: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (vehicle.access.canEdit) {
            DropdownMenuItem(
                text = { Text(stringResource(photoActionRes(vehicle))) },
                leadingIcon = { Icon(Icons.Outlined.AddAPhoto, contentDescription = null) },
                onClick = {
                    onDismiss()
                    actions.changePhoto(vehicle)
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_rename_vehicle)) },
            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
            onClick = {
                onDismiss()
                actions.rename(vehicle)
            },
        )
        if (onOpen != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_open_vehicle)) },
                leadingIcon = { Icon(Icons.Outlined.OpenInNew, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onOpen()
                },
            )
        }
    }
}

fun photoActionRes(vehicle: Vehicle): Int = if (vehicle.imageDocumentId == null) R.string.action_upload_photo else R.string.action_change_photo

/** The same actions as the long-press menu, offered to TalkBack as custom actions on the card. */
@Composable
fun Modifier.vehicleCardSemantics(vehicle: Vehicle, actions: VehicleCardActions, onOpen: (() -> Unit)?): Modifier {
    val photoLabel = stringResource(photoActionRes(vehicle))
    val renameLabel = stringResource(R.string.action_rename_vehicle)
    val openLabel = stringResource(R.string.action_open_vehicle)
    return semantics {
        customActions = buildList {
            if (onOpen != null) add(CustomAccessibilityAction(openLabel) { onOpen(); true })
            if (vehicle.access.canEdit) add(CustomAccessibilityAction(photoLabel) { actions.changePhoto(vehicle); true })
            add(CustomAccessibilityAction(renameLabel) { actions.rename(vehicle); true })
        }
    }
}

/** Nickname editor. Empty saves as "no nickname"; the server's name is quoted so it's clear what stays. */
@Composable
fun RenameVehicleDialog(serverName: String, current: String?, onSave: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_vehicle_title)) },
        text = {
            Column {
                Text(stringResource(R.string.rename_vehicle_body, serverName), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.rename_vehicle_field)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim().ifEmpty { null }) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            if (current != null) {
                TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.rename_vehicle_clear)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

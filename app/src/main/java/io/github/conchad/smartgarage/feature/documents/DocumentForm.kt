package io.github.conchad.smartgarage.feature.documents

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.data.DocumentInput
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.LoadingState
import io.github.conchad.smartgarage.ui.components.documentCategoryLabel
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.components.forms.ViewOnlyNote
import io.github.conchad.smartgarage.ui.files.FilePickerField
import io.github.conchad.smartgarage.ui.files.FileProblem
import io.github.conchad.smartgarage.ui.files.FileRules
import io.github.conchad.smartgarage.ui.files.PickedFile
import io.github.conchad.smartgarage.ui.files.UploadKind
import io.github.conchad.smartgarage.ui.files.fileProblemText
import io.github.conchad.smartgarage.ui.files.inspect
import io.github.conchad.smartgarage.ui.files.open
import io.github.conchad.smartgarage.ui.navigation.DocumentFormRoute
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The web's `DocumentCategory` names; "" is the form's "Optional" (no category). */
val DOCUMENT_CATEGORIES = listOf("", "INVOICE", "INSPECTION", "REGISTRATION", "INSURANCE", "OTHER")

/**
 * The web's "Upload document": vehicle, category, title and the file. The file is checked against the server's
 * rules the moment it is picked (D-A21), so the save bar can say why a file can't be sent before anything queues.
 */
data class DocumentFormState(
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val category: String = "",
    val title: String = "",
    val file: PickedFile? = null,
    /** The picker returned something the phone can't read at all. */
    val unreadable: Boolean = false,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
    val canEdit: Boolean get() = vehicle?.access?.canEdit == true
    val problem: FileProblem? get() = file?.problem(UploadKind.Document)
    val titleTooLong: Boolean get() = title.length > 120
    val canSave: Boolean get() = canEdit && file != null && problem == null && !titleTooLong && !saving

    fun input(): DocumentInput = DocumentInput(
        category = category.ifEmpty { null },
        title = title.trim().ifEmpty { null },
    )
}

@HiltViewModel
class DocumentFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
    @ApplicationContext private val context: Context,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<DocumentFormRoute>()
    private val form = MutableStateFlow(DocumentFormState())
    val state: StateFlow<DocumentFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val vehicles = garage.vehicles.first().filter { it.access.canEdit }.sortedWith(GarageMath.vehicleOrder)
            val preselected = route.vehicleId?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id
            form.value = DocumentFormState(vehicles = vehicles, vehicleId = preselected, loaded = true)
        }
    }

    fun selectVehicle(id: String) = form.update { it.copy(vehicleId = id, dirty = true) }
    fun setCategory(value: String) = form.update { it.copy(category = value, dirty = true) }
    fun setTitle(text: String) = form.update { it.copy(title = text, dirty = true) }

    /** Reads name, size and type from the provider and the bytes; the form shows the result and any problem. */
    fun pick(uri: Uri) {
        viewModelScope.launch {
            val file = context.contentResolver.inspect(uri)
            form.update { it.copy(file = file, unreadable = file == null, dirty = true) }
        }
    }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        val vehicleId = s.vehicleId ?: return
        val file = s.file ?: return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val stream = context.contentResolver.open(file)
            if (stream == null) {
                form.update { it.copy(saving = false, file = null, unreadable = true) }
                return@launch
            }
            val result = writer.addDocument(vehicleId, s.input(), file.name, file.mimeType ?: "application/octet-stream", stream, file.sizeBytes)
            val queued = report(result)
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }
}

@Composable
fun DocumentFormScreen(onClose: () -> Unit, viewModel: DocumentFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    // The system file picker, limited to what the server stores (images and PDF); no permission needed (D-A21).
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::pick) }
    val launchPicker = { pickFile.launch(FileRules.pickerTypes(UploadKind.Document)) }

    val fileError = when {
        state.unreadable -> stringResource(R.string.file_unreadable)
        state.problem != null -> fileProblemText(state.problem!!, UploadKind.Document)
        else -> null
    }
    val validation = when {
        !state.loaded -> null
        state.vehicles.isEmpty() -> stringResource(R.string.form_no_editable_vehicles)
        !state.canEdit -> stringResource(R.string.view_only)
        state.file == null -> stringResource(R.string.document_form_needs_file)
        fileError != null -> fileError
        state.titleTooLong -> stringResource(R.string.form_fix_errors)
        else -> null
    }

    FormScreen(
        title = stringResource(R.string.document_form_title),
        onClose = onClose,
        saveLabel = stringResource(R.string.action_upload),
        canSave = state.canSave,
        saving = state.saving,
        onSave = viewModel::save,
        dirty = state.dirty && !state.done,
        validation = validation,
        snackbarHost = snackbar,
    ) {
        if (!state.loaded) {
            LoadingState(stringResource(R.string.loading))
            return@FormScreen
        }
        FormPicker(
            label = stringResource(R.string.field_vehicle),
            options = state.vehicles,
            selected = state.vehicle,
            optionLabel = { it.displayName },
            onSelect = { viewModel.selectVehicle(it.id) },
            enabled = state.vehicles.size > 1,
            placeholder = stringResource(R.string.form_no_editable_vehicles).takeIf { state.vehicles.isEmpty() },
        )
        if (state.vehicle != null && !state.canEdit) ViewOnlyNote(state.vehicle?.owner?.name)
        FilePickerField(file = state.file, kind = UploadKind.Document, onPick = launchPicker, error = fileError)
        val categoryLabels = DOCUMENT_CATEGORIES.associateWith { if (it.isEmpty()) stringResource(R.string.document_form_no_category) else documentCategoryLabel(it) }
        FormPicker(
            label = stringResource(R.string.wishlist_category),
            options = DOCUMENT_CATEGORIES,
            selected = state.category,
            optionLabel = { categoryLabels[it] ?: it },
            onSelect = viewModel::setCategory,
        )
        FormTextField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            label = stringResource(R.string.document_form_doc_title),
            supporting = stringResource(R.string.document_form_title_hint),
            error = if (state.titleTooLong) pluralStringResource(R.plurals.form_too_long, 120, 120) else null,
        )
        FormEndSpacer()
    }
}

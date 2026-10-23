package io.github.conchad.smartgarage.feature.notes

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
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.ui.components.forms.FormSkeleton
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.NoteInput
import io.github.conchad.smartgarage.core.data.NotePatch
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.Note
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.ui.components.DeniedSnackbar
import io.github.conchad.smartgarage.ui.components.forms.FormEndSpacer
import io.github.conchad.smartgarage.ui.components.forms.FormNotesField
import io.github.conchad.smartgarage.ui.components.forms.FormPicker
import io.github.conchad.smartgarage.ui.components.forms.FormScreen
import io.github.conchad.smartgarage.ui.components.forms.FormSwitchRow
import io.github.conchad.smartgarage.ui.components.forms.FormTextField
import io.github.conchad.smartgarage.ui.components.forms.VehicleChoice
import io.github.conchad.smartgarage.ui.navigation.NoteFormRoute
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The web's note editor: title, vehicle (or none for a general note), comma-separated tags, pin, content. */
data class NoteFormState(
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: String? = null,
    val editingId: String? = null,
    val title: String = "",
    val tags: String = "",
    val pinned: Boolean = false,
    val content: String = "",
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val done: Boolean = false,
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == vehicleId }
    val editing: Boolean get() = editingId != null

    /** Split on commas, trimmed, deduplicated, like the web's `parseTagsInput`. */
    val tagList: List<String> get() = parseTags(tags)
    val titleTooLong: Boolean get() = title.length > 160
    val tooManyTags: Boolean get() = tagList.size > 8
    val tagTooLong: Boolean get() = tagList.any { it.length > 30 }
    val contentTooLong: Boolean get() = content.length > 20_000
    val canSave: Boolean get() = title.isNotBlank() && !titleTooLong && !tooManyTags && !tagTooLong && !contentTooLong && !saving

    fun input() = NoteInput(
        title = title.trim(),
        content = content,
        vehicleId = vehicleId,
        isPinned = pinned,
        tags = tagList,
    )

    fun patch() = NotePatch(
        title = title.trim(),
        content = content,
        vehicleId = vehicleId,
        isPinned = pinned,
        tags = tagList,
        clear = if (vehicleId == null) setOf("vehicleId") else emptySet(),
    )

    companion object {
        fun parseTags(raw: String): List<String> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

@HiltViewModel
class NoteFormViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garage: GarageRepository,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val route = savedStateHandle.toRoute<NoteFormRoute>()
    private val form = MutableStateFlow(NoteFormState())
    val state: StateFlow<NoteFormState> = form.asStateFlow()

    init {
        viewModelScope.launch {
            // Notes are the user's own, so any vehicle in the garage (shared ones included) can be linked.
            val vehicles = garage.vehicles.first().sortedWith(GarageMath.vehicleOrder)
            val note: Note? = route.noteId?.let { id -> garage.notes.first().firstOrNull { it.id == id } }
            form.value = NoteFormState(
                vehicles = vehicles,
                vehicleId = (note?.vehicleId ?: route.vehicleId)?.takeIf { id -> vehicles.any { it.id == id } },
                editingId = note?.id,
                title = note?.title.orEmpty(),
                tags = note?.tags?.joinToString(", ").orEmpty(),
                pinned = note?.isPinned ?: false,
                content = note?.content.orEmpty(),
                loaded = true,
            )
        }
    }

    fun selectVehicle(id: String?) = form.update { it.copy(vehicleId = id, dirty = true) }
    fun setTitle(text: String) = form.update { it.copy(title = text, dirty = true) }
    fun setTags(text: String) = form.update { it.copy(tags = text, dirty = true) }
    fun setPinned(on: Boolean) = form.update { it.copy(pinned = on, dirty = true) }
    fun setContent(text: String) = form.update { it.copy(content = text, dirty = true) }

    fun save() {
        val s = form.value
        if (!s.canSave) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = s.editingId?.let { writer.updateNote(it, s.patch()) } ?: writer.addNote(s.input())
            val queued = report(result)
            form.update { it.copy(saving = false, done = queued, dirty = it.dirty && !queued) }
        }
    }
}

@Composable
fun NoteFormScreen(onClose: () -> Unit, viewModel: NoteFormViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.done) { if (state.done) onClose() }
    DeniedSnackbar(viewModel, snackbar)

    val validation = when {
        !state.loaded -> null
        state.title.isBlank() -> stringResource(R.string.note_form_needs_title)
        state.titleTooLong || state.tooManyTags || state.tagTooLong || state.contentTooLong -> stringResource(R.string.form_fix_errors)
        else -> null
    }
    val noVehicle = stringResource(R.string.note_no_vehicle)
    val choices = remember(state.vehicles, noVehicle) { listOf(VehicleChoice(null, noVehicle)) + state.vehicles.map { VehicleChoice(it, it.displayName) } }

    FormScreen(
        title = stringResource(if (state.editing) R.string.note_form_edit_title else R.string.note_form_title),
        onClose = onClose,
        saveLabel = stringResource(R.string.action_save),
        canSave = state.canSave,
        saving = state.saving,
        onSave = viewModel::save,
        dirty = state.dirty && !state.done,
        validation = validation,
        snackbarHost = snackbar,
    ) {
        if (!state.loaded) {
            FormSkeleton()
            return@FormScreen
        }
        FormTextField(
            value = state.title,
            onValueChange = viewModel::setTitle,
            label = stringResource(R.string.note_title),
            error = if (state.titleTooLong) pluralStringResource(R.plurals.form_too_long, 160, 160) else null,
        )
        FormPicker(
            label = stringResource(R.string.field_vehicle),
            options = choices,
            selected = choices.firstOrNull { it.vehicle?.id == state.vehicleId } ?: choices.first(),
            optionLabel = { it.label },
            onSelect = { viewModel.selectVehicle(it.vehicle?.id) },
            enabled = state.vehicles.isNotEmpty(),
        )
        FormTextField(
            value = state.tags,
            onValueChange = viewModel::setTags,
            label = stringResource(R.string.note_tags),
            supporting = stringResource(R.string.note_tags_hint),
            error = when {
                state.tooManyTags -> stringResource(R.string.note_tags_too_many)
                state.tagTooLong -> stringResource(R.string.note_tag_too_long)
                else -> null
            },
        )
        FormSwitchRow(label = stringResource(R.string.note_pin), checked = state.pinned, onCheckedChange = viewModel::setPinned)
        FormNotesField(
            value = state.content,
            onValueChange = viewModel::setContent,
            label = stringResource(R.string.note_content),
            supporting = stringResource(R.string.note_content_hint),
            error = if (state.contentTooLong) pluralStringResource(R.plurals.form_too_long, 20_000, 20_000) else null,
        )
        FormEndSpacer()
    }
}

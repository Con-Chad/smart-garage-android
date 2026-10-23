package io.github.conchad.smartgarage.feature.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.conchad.smartgarage.R
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.components.GroupCard
import io.github.conchad.smartgarage.ui.components.GroupHeader
import io.github.conchad.smartgarage.ui.components.NoteSheet
import io.github.conchad.smartgarage.ui.components.RowDivider
import io.github.conchad.smartgarage.ui.components.SearchField
import io.github.conchad.smartgarage.ui.components.forms.AddFab
import io.github.conchad.smartgarage.ui.glass.GlassFilterChip
import io.github.conchad.smartgarage.ui.components.EmptyAction

@Composable
internal fun NotesScreen(onBack: () -> Unit, actions: SectionActions, viewModel: NotesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sync by viewModel.syncInfo.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingIds.collectAsStateWithLifecycle()
    val fmt = state.formatter
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val add = { actions.onNewNote(state.selectedVehicleId) }
    val narrowed = (state.query.isNotBlank() || state.data.selectedTag != null) && state.data.unfiltered > 0
    SectionScaffold(
        title = stringResource(R.string.nav_notes),
        onBack = onBack,
        viewModel = viewModel,
        vehicles = state.vehicles,
        selectedVehicleId = state.selectedVehicleId,
        onSelectVehicle = viewModel::selectVehicle,
        loaded = state.loaded,
        sync = sync,
        onRefresh = viewModel::refresh,
        isEmpty = state.data.isEmpty,
        emptyIcon = if (narrowed) Icons.Outlined.Search else Icons.AutoMirrored.Outlined.StickyNote2,
        emptyTitle = stringResource(if (narrowed) R.string.search_no_matches_title else R.string.notes_empty_title),
        floatingActionButton = { if (state.loaded) AddFab(stringResource(R.string.action_new_note), onClick = add) },
        // The FAB is the "New note" verb; the empty state only offers to undo a search or tag that found nothing.
        emptyAction = if (narrowed) {
            {
                EmptyAction(stringResource(R.string.search_clear), Icons.Outlined.Close) {
                    viewModel.search("")
                    viewModel.selectTag(null)
                }
            }
        } else {
            null
        },
        filters = {
            if (state.loaded && state.data.unfiltered > 0 || state.query.isNotEmpty()) {
                SearchField(state.query, viewModel::search, stringResource(R.string.search_notes_hint))
            }
            if (state.data.tags.isNotEmpty()) {
                // Every tag in view; one at a time, tapping the active one clears it.
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                ) {
                    items(state.data.tags, key = { it }) { tag ->
                        val selected = state.data.selectedTag == tag
                        GlassFilterChip(selected = selected, onClick = { viewModel.selectTag(if (selected) null else tag) }, label = "#$tag")
                    }
                }
            }
        },
    ) {
        state.data.notes.groupBy { it.isPinned }.toSortedMap(compareByDescending { it }).forEach { (pinned, notes) ->
            item(key = "p$pinned", contentType = "header") { GroupHeader(stringResource(if (pinned) R.string.notes_pinned else R.string.notes_all)) }
            item(key = "p$pinned-list", contentType = "group") {
                GroupCard {
                    notes.forEachIndexed { index, note ->
                        if (index > 0) RowDivider()
                        SectionRow(
                            icon = if (note.isPinned) Icons.Outlined.PushPin else Icons.AutoMirrored.Outlined.StickyNote2,
                            title = note.title,
                            detail = note.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim(),
                            onClick = { open = note.id },
                            trailingDetail = note.updatedAt.toInstantOrNull()?.let(fmt::shortDate),
                            pending = note.id in pendingIds,
                        )
                    }
                }
            }
        }
    }
    state.data.notes.firstOrNull { it.id == open }?.let { note ->
        NoteSheet(
            note,
            state.vehicleById[note.vehicleId],
            fmt,
            onDismiss = { open = null },
            pending = note.id in pendingIds,
            onTogglePin = {
                open = null
                viewModel.setPinned(note, !note.isPinned)
            },
            onEdit = {
                open = null
                actions.onEditNote(note.id)
            },
            onDelete = {
                open = null
                viewModel.deleteNote(note.id)
            },
        )
    }
}

package io.github.conchad.smartgarage.feature.sections

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.PhotoStore
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.Document
import io.github.conchad.smartgarage.core.model.Expense
import io.github.conchad.smartgarage.core.model.FuelSegment
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.Note
import io.github.conchad.smartgarage.core.model.OdometerLog
import io.github.conchad.smartgarage.core.model.Reminder
import io.github.conchad.smartgarage.core.model.SectionMath
import io.github.conchad.smartgarage.core.model.TimelineEvent
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.WishlistItem
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

data class SectionUiState<T>(
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: String? = null,
    val data: T,
    val formatter: Formatter = Formatter(null),
    val loaded: Boolean = false,
) {
    val vehicleById: Map<String, Vehicle> = vehicles.associateBy { it.id }
}

/**
 * A screen under More: the garage's vehicles with an optional filter, plus the section's own [content]. [content]
 * emits a function from (vehicle ids in view, filter) to the data, so filtering never re-reads the cache.
 */
abstract class SectionViewModel<T>(private val garage: GarageRepository, private val initial: T) : ViewModel() {
    private val selected = MutableStateFlow<String?>(null)

    protected abstract fun content(): Flow<(Set<String>, String?) -> T>

    val state: StateFlow<SectionUiState<T>> by lazy {
        combine(garage.vehicles, garage.formatter, selected, content()) { all, fmt, vehicleId, build ->
            val vehicles = all.sortedWith(GarageMath.vehicleOrder)
            // A vehicle that disappeared (deleted or unshared) drops the filter rather than showing nothing.
            val filter = vehicleId?.takeIf { id -> vehicles.any { it.id == id } }
            SectionUiState(vehicles, filter, build(vehicles.mapTo(HashSet()) { it.id }, filter), fmt, loaded = true)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionUiState(data = initial))
    }

    fun selectVehicle(id: String?) {
        selected.value = id
    }

    protected fun String?.inView(ids: Set<String>, filter: String?): Boolean =
        if (this == null) filter == null else this in ids && (filter == null || this == filter)
}

private val german get() = Locale.getDefault().language == "de"

@HiltViewModel
class RemindersViewModel @Inject constructor(garage: GarageRepository) :
    SectionViewModel<List<Reminder>>(garage, emptyList()) {
    private val source = combine(garage.vehicles, garage.schedules, garage.templates, garage.inspections, garage.insurancePolicies) {
            vehicles, schedules, templates, inspections, policies ->
        { ids: Set<String>, filter: String? ->
            val inView = vehicles.filter { it.id.inView(ids, filter) }
            SectionMath.reminders(GarageMath.dueItems(schedules, inView, templates, german), inspections, policies, inView)
        }
    }

    override fun content() = source
}

data class HistoryData(
    val events: List<TimelineEvent> = emptyList(),
    val recordNames: Map<String, String> = emptyMap(),
    val segments: Map<String, FuelSegment> = emptyMap(),
)

@HiltViewModel
class HistoryViewModel @Inject constructor(garage: GarageRepository) : SectionViewModel<HistoryData>(garage, HistoryData()) {
    private val names = combine(garage.records, garage.schedules, garage.templates) { records, schedules, templates ->
        val scheduleById = schedules.associateBy { it.id }
        val templateById = templates.associateBy { it.id }
        records.mapNotNull { r -> GarageMath.recordName(r, scheduleById, templateById, german)?.let { r.id to it } }.toMap()
    }
    private val source = combine(garage.records, garage.fuelEntries, garage.expenses, garage.odometerLogs, names) {
            records, fuel, expenses, logs, recordNames ->
        { ids: Set<String>, filter: String? ->
            HistoryData(
                events = SectionMath.timeline(records, fuel, expenses, logs).filter { it.vehicleId.inView(ids, filter) },
                recordNames = recordNames,
                segments = GarageMath.fuelSegments(fuel),
            )
        }
    }

    override fun content() = source
}

data class OdometerData(val logs: List<OdometerLog> = emptyList(), val deltas: Map<String, Int> = emptyMap())

@HiltViewModel
class OdometerViewModel @Inject constructor(garage: GarageRepository) : SectionViewModel<OdometerData>(garage, OdometerData()) {
    private val source = garage.odometerLogs.map { logs ->
        val deltas = SectionMath.odometerDeltas(logs)
        val build = { ids: Set<String>, filter: String? ->
            OdometerData(
                logs.filter { it.vehicleId.inView(ids, filter) }.sortedByDescending { it.recordedAt.toInstantOrNull() ?: Instant.MIN },
                deltas,
            )
        }
        build
    }

    override fun content() = source
}

@HiltViewModel
class ExpensesViewModel @Inject constructor(garage: GarageRepository) : SectionViewModel<List<Expense>>(garage, emptyList()) {
    private val source = garage.expenses.map { expenses ->
        val build = { ids: Set<String>, filter: String? ->
            expenses.filter { it.vehicleId.inView(ids, filter) }.sortedByDescending { it.occurredAt.toInstantOrNull() ?: Instant.MIN }
        }
        build
    }

    override fun content() = source
}

/** What happened when the user tapped a document. */
sealed interface DocumentOpen {
    data object Idle : DocumentOpen
    data class Loading(val documentId: String) : DocumentOpen
    data class Image(val document: Document, val file: java.io.File) : DocumentOpen
    data object Failed : DocumentOpen
    data object NoViewer : DocumentOpen
}

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val garage: GarageRepository,
    private val photos: PhotoStore,
    @ApplicationContext private val context: Context,
) : SectionViewModel<List<Document>>(garage, emptyList()) {
    private val source = garage.documents.map { documents ->
        val build = { ids: Set<String>, filter: String? ->
            documents.filter { it.vehicleId.inView(ids, filter) }.sortedByDescending { it.createdAt }
        }
        build
    }

    override fun content() = source

    private val _open = MutableStateFlow<DocumentOpen>(DocumentOpen.Idle)
    val open: StateFlow<DocumentOpen> = _open.asStateFlow()

    /** Images show in the app; anything else (PDFs) goes to whatever viewer the phone has. */
    fun open(document: Document) {
        viewModelScope.launch {
            _open.value = DocumentOpen.Loading(document.id)
            val file = photos.file(garage.activeProfileId.first(), document.id)
            _open.value = when {
                file == null -> DocumentOpen.Failed
                document.isImage -> DocumentOpen.Image(document, file)
                else -> view(document, file)
            }
        }
    }

    fun dismiss() {
        _open.value = DocumentOpen.Idle
    }

    private fun view(document: Document, file: java.io.File): DocumentOpen {
        // The cached file has no extension; the display name gives the viewer a sensible title and type hint.
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file, document.originalFilename)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, document.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            DocumentOpen.Idle
        } catch (_: ActivityNotFoundException) {
            DocumentOpen.NoViewer
        }
    }
}

@HiltViewModel
class NotesViewModel @Inject constructor(garage: GarageRepository) : SectionViewModel<List<Note>>(garage, emptyList()) {
    private val source = garage.notes.map { notes ->
        val build = { ids: Set<String>, filter: String? ->
            // Notes without a vehicle are general notes: shown under "All vehicles" only.
            notes.filter { it.vehicleId.inView(ids, filter) }
                .sortedWith(compareByDescending<Note> { it.isPinned }.thenByDescending { it.updatedAt })
        }
        build
    }

    override fun content() = source
}

@HiltViewModel
class WishlistViewModel @Inject constructor(garage: GarageRepository) : SectionViewModel<List<WishlistItem>>(garage, emptyList()) {
    private val source = garage.wishlist.map { items ->
        val build = { ids: Set<String>, filter: String? ->
            items.filter { it.vehicleId.inView(ids, filter) }
                .sortedWith(compareByDescending<WishlistItem> { it.priority }.thenByDescending { it.createdAt })
        }
        build
    }

    override fun content() = source
}

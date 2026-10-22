package io.github.conchad.smartgarage.feature.sections

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.data.GarageRepository
import io.github.conchad.smartgarage.core.data.GarageWriter
import io.github.conchad.smartgarage.core.data.NotePatch
import io.github.conchad.smartgarage.core.data.PhotoStore
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.model.CategoryTotal
import io.github.conchad.smartgarage.core.model.ChartMath
import io.github.conchad.smartgarage.core.model.ChartPoint
import io.github.conchad.smartgarage.core.model.Document
import io.github.conchad.smartgarage.core.model.Expense
import io.github.conchad.smartgarage.core.model.FuelSegment
import io.github.conchad.smartgarage.core.model.GarageMath
import io.github.conchad.smartgarage.core.model.MonthTotal
import io.github.conchad.smartgarage.core.model.Note
import io.github.conchad.smartgarage.core.model.OdometerLog
import io.github.conchad.smartgarage.core.model.Reminder
import io.github.conchad.smartgarage.core.model.SearchMatch
import io.github.conchad.smartgarage.core.model.SectionMath
import io.github.conchad.smartgarage.core.model.TimelineEvent
import io.github.conchad.smartgarage.core.model.Vehicle
import io.github.conchad.smartgarage.core.model.WishlistItem
import io.github.conchad.smartgarage.core.model.toInstantOrNull
import io.github.conchad.smartgarage.ui.sync.SyncControl
import io.github.conchad.smartgarage.ui.sync.WritingViewModel
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
    /** The search text as typed; the data is already filtered by it. */
    val query: String = "",
) {
    val vehicleById: Map<String, Vehicle> = vehicles.associateBy { it.id }

    /** An expense or document needs a vehicle the user may edit; notes and wishlist items are the user's own. */
    val canEditAny: Boolean get() = vehicles.any { it.access.canEdit }
}

/**
 * A screen under More: the garage's vehicles with an optional filter, plus the section's own [content]. [content]
 * emits a function from (vehicle ids in view, filter) to the data, so filtering never re-reads the cache. Writes
 * (the deletes from a row's sheet) go through [WritingViewModel], so every section shows pending marks and denials.
 */
abstract class SectionViewModel<T>(
    private val garage: GarageRepository,
    private val initial: T,
    control: SyncControl,
    writer: GarageWriter,
) : WritingViewModel(control, writer) {
    private val selected = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    /** What the section shows for (vehicle ids in view, vehicle filter, search terms). Sections without a search ignore the terms. */
    protected abstract fun content(): Flow<(Set<String>, String?, List<String>) -> T>

    val state: StateFlow<SectionUiState<T>> by lazy {
        combine(garage.vehicles, garage.formatter, selected, query, content()) { all, fmt, vehicleId, text, build ->
            val vehicles = all.sortedWith(GarageMath.vehicleOrder)
            // A vehicle that disappeared (deleted or unshared) drops the filter rather than showing nothing.
            val filter = vehicleId?.takeIf { id -> vehicles.any { it.id == id } }
            val data = build(vehicles.mapTo(HashSet()) { it.id }, filter, SearchMatch.terms(text))
            SectionUiState(vehicles, filter, data, fmt, loaded = true, query = text)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SectionUiState(data = initial))
    }

    fun selectVehicle(id: String?) {
        selected.value = id
    }

    /** Search text; the filtering runs here (D-A23), so the list only ever receives what matches. */
    fun search(text: String) {
        query.value = text
    }

    protected fun String?.inView(ids: Set<String>, filter: String?): Boolean =
        if (this == null) filter == null else this in ids && (filter == null || this == filter)
}

private val german get() = Locale.getDefault().language == "de"

@HiltViewModel
class RemindersViewModel @Inject constructor(garage: GarageRepository, control: SyncControl, writer: GarageWriter) :
    SectionViewModel<List<Reminder>>(garage, emptyList(), control, writer) {
    private val source = combine(garage.vehicles, garage.schedules, garage.templates, garage.inspections, garage.insurancePolicies) {
            vehicles, schedules, templates, inspections, policies ->
        { ids: Set<String>, filter: String?, _: List<String> ->
            val inView = vehicles.filter { it.id.inView(ids, filter) }
            SectionMath.reminders(GarageMath.dueItems(schedules, inView, templates, german), inspections, policies, inView)
        }
    }

    override fun content() = source

    /** The web's "remove": the schedule stops reminding; its records stay. */
    fun deleteSchedule(id: String) = perform { deleteSchedule(id) }
}

data class HistoryData(
    val events: List<TimelineEvent> = emptyList(),
    val recordNames: Map<String, String> = emptyMap(),
    val segments: Map<String, FuelSegment> = emptyMap(),
    /** Events in view before the search narrowed them; tells "nothing matches" from "nothing happened". */
    val unfiltered: Int = 0,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(garage: GarageRepository, control: SyncControl, writer: GarageWriter) :
    SectionViewModel<HistoryData>(garage, HistoryData(), control, writer) {
    private val names = combine(garage.records, garage.schedules, garage.templates) { records, schedules, templates ->
        val scheduleById = schedules.associateBy { it.id }
        val templateById = templates.associateBy { it.id }
        records.mapNotNull { r -> GarageMath.recordName(r, scheduleById, templateById, german)?.let { r.id to it } }.toMap()
    }
    private val source = combine(garage.records, garage.fuelEntries, garage.expenses, garage.odometerLogs, names) {
            records, fuel, expenses, logs, recordNames ->
        { ids: Set<String>, filter: String?, terms: List<String> ->
            val inView = SectionMath.timeline(records, fuel, expenses, logs).filter { it.vehicleId.inView(ids, filter) }
            HistoryData(
                events = inView.filter { event ->
                    when (event) {
                        is TimelineEvent.Service -> SearchMatch.record(event.record, recordNames[event.record.id], terms)
                        is TimelineEvent.Fuel -> SearchMatch.fuel(event.entry, terms)
                        is TimelineEvent.Cost -> SearchMatch.expense(event.expense, terms)
                        is TimelineEvent.Odometer -> SearchMatch.odometer(event.log, terms)
                    }
                },
                recordNames = recordNames,
                segments = GarageMath.fuelSegments(fuel),
                unfiltered = inView.size,
            )
        }
    }

    override fun content() = source
}

data class OdometerData(
    val logs: List<OdometerLog> = emptyList(),
    val deltas: Map<String, Int> = emptyMap(),
    /** Readings of the one vehicle in view, oldest first, for the chart (D-A23); null with several vehicles in view. */
    val series: List<ChartPoint>? = null,
)

@HiltViewModel
class OdometerViewModel @Inject constructor(garage: GarageRepository, control: SyncControl, writer: GarageWriter) :
    SectionViewModel<OdometerData>(garage, OdometerData(), control, writer) {
    private val source = garage.odometerLogs.map { logs ->
        val deltas = SectionMath.odometerDeltas(logs)
        val build = { ids: Set<String>, filter: String?, _: List<String> ->
            val inView = logs.filter { it.vehicleId.inView(ids, filter) }
            OdometerData(
                inView.sortedByDescending { it.recordedAt.toInstantOrNull() ?: Instant.MIN },
                deltas,
                series = if (filter != null || ids.size == 1) ChartMath.odometerSeries(inView) else null,
            )
        }
        build
    }

    override fun content() = source
}

/** The expenses in view plus the web's cost overview for them: the last twelve months and the split by category. */
data class ExpensesData(
    val expenses: List<Expense> = emptyList(),
    val months: List<MonthTotal> = emptyList(),
    val categories: List<CategoryTotal> = emptyList(),
    /** The currency most of the expenses in the window use; the totals are shown in it. */
    val currency: String? = null,
) {
    val isEmpty: Boolean get() = expenses.isEmpty()
}

@HiltViewModel
class ExpensesViewModel @Inject constructor(garage: GarageRepository, control: SyncControl, writer: GarageWriter) :
    SectionViewModel<ExpensesData>(garage, ExpensesData(), control, writer) {
    private val source = combine(garage.expenses, garage.formatter) { expenses, fmt ->
        val build = { ids: Set<String>, filter: String?, _: List<String> ->
            val inView = expenses.filter { it.vehicleId.inView(ids, filter) }
            val now = Instant.now()
            ExpensesData(
                expenses = inView.sortedByDescending { it.occurredAt.toInstantOrNull() ?: Instant.MIN },
                months = ChartMath.monthlyTotals(inView, fmt.zone, now),
                categories = ChartMath.categoryTotals(inView, fmt.zone, now),
                currency = inView.groupingBy { it.currency }.eachCount().maxByOrNull { it.value }?.key,
            )
        }
        build
    }

    override fun content() = source

    fun deleteExpense(id: String) = perform { deleteExpense(id) }
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
    control: SyncControl,
    writer: GarageWriter,
) : SectionViewModel<List<Document>>(garage, emptyList(), control, writer) {
    private val source = garage.documents.map { documents ->
        val build = { ids: Set<String>, filter: String?, _: List<String> ->
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

    fun deleteDocument(id: String) = perform { deleteDocument(id) }

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

/** Notes in view, pinned first; [tags] are every tag on the notes in view (before the tag filter), for the chip row. */
data class NotesData(
    val notes: List<Note> = emptyList(),
    val tags: List<String> = emptyList(),
    val selectedTag: String? = null,
    /** Notes in view before search and tag filter narrowed them. */
    val unfiltered: Int = 0,
) {
    val isEmpty: Boolean get() = notes.isEmpty()
}

@HiltViewModel
class NotesViewModel @Inject constructor(garage: GarageRepository, control: SyncControl, writer: GarageWriter) :
    SectionViewModel<NotesData>(garage, NotesData(), control, writer) {
    private val tag = MutableStateFlow<String?>(null)

    private val source = combine(garage.notes, tag) { notes, selectedTag ->
        val build = { ids: Set<String>, filter: String?, terms: List<String> ->
            // Notes without a vehicle are general notes: shown under "All vehicles" only.
            val inView = notes.filter { it.vehicleId.inView(ids, filter) }
            val tags = inView.flatMap { it.tags }.distinct().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            // A tag that no longer exists on any note in view drops the filter rather than showing nothing.
            val activeTag = selectedTag?.takeIf { it in tags }
            NotesData(
                notes = inView
                    .filter { (activeTag == null || activeTag in it.tags) && SearchMatch.note(it, terms) }
                    .sortedWith(compareByDescending<Note> { it.isPinned }.thenByDescending { it.updatedAt }),
                tags = tags,
                selectedTag = activeTag,
                unfiltered = inView.size,
            )
        }
        build
    }

    override fun content() = source

    fun selectTag(value: String?) {
        tag.value = value
    }

    /** The pin toggle from the note's sheet; only `isPinned` goes in the patch, so nothing else is touched. */
    fun setPinned(note: Note, pinned: Boolean) = perform { updateNote(note.id, NotePatch(isPinned = pinned)) }

    fun deleteNote(id: String) = perform { deleteNote(id) }
}

@HiltViewModel
class WishlistViewModel @Inject constructor(garage: GarageRepository, control: SyncControl, writer: GarageWriter) :
    SectionViewModel<List<WishlistItem>>(garage, emptyList(), control, writer) {
    private val source = garage.wishlist.map { items ->
        val build = { ids: Set<String>, filter: String?, _: List<String> ->
            items.filter { it.vehicleId.inView(ids, filter) }
                .sortedWith(compareByDescending<WishlistItem> { it.priority }.thenByDescending { it.createdAt })
        }
        build
    }

    override fun content() = source

    fun deleteWishlistItem(id: String) = perform { deleteWishlistItem(id) }
}

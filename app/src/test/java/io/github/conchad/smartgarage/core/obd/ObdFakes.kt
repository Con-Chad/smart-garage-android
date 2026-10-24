package io.github.conchad.smartgarage.core.obd

import io.github.conchad.smartgarage.core.cache.CachedRow
import io.github.conchad.smartgarage.core.cache.OutboxEntry
import io.github.conchad.smartgarage.core.model.OdometerLog
import io.github.conchad.smartgarage.core.obd.data.ObdReading
import io.github.conchad.smartgarage.core.obd.data.ObdReadingDao
import io.github.conchad.smartgarage.core.obd.data.ReadingState
import io.github.conchad.smartgarage.core.obd.link.AdapterConnection
import io.github.conchad.smartgarage.core.obd.link.AdapterTransport
import io.github.conchad.smartgarage.core.obd.link.CloseReason
import io.github.conchad.smartgarage.core.obd.link.LinkState
import io.github.conchad.smartgarage.core.obd.trip.TripStore
import io.github.conchad.smartgarage.core.outbox.GarageStore
import io.github.conchad.smartgarage.core.outbox.StagedFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/** An [AdapterConnection] over any [ElmLink]: Ready at once (or [initial]), and [close] / [drop] end `incoming`. */
class TestConnection(private val inner: ElmLink, scope: CoroutineScope, initial: LinkState = LinkState.Ready) : AdapterConnection, ElmLink {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<LinkState> = mutableState
    override val link: ElmLink get() = this
    private val received = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = received.receiveAsFlow()
    override val maxWriteBytes: Int get() = inner.maxWriteBytes
    private val forward: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { inner.incoming.collect { received.send(it) } }
    var closed = false
        private set

    override suspend fun write(bytes: ByteArray) {
        if (state.value !is LinkState.Closed) inner.write(bytes)
    }

    /** The adapter went away. */
    fun drop(reason: CloseReason = CloseReason.DISCONNECTED) {
        if (state.value is LinkState.Closed) return
        mutableState.value = LinkState.Closed(reason)
        received.close()
        forward.cancel()
    }

    override fun close() {
        closed = true
        drop(CloseReason.CLOSED)
    }
}

/** Hands out connections made by [connect] and remembers them. */
class TestTransport(private val connect: (waitForDevice: Boolean) -> TestConnection) : AdapterTransport {
    val opened = mutableListOf<TestConnection>()
    override fun open(adapter: io.github.conchad.smartgarage.core.obd.link.ObdAdapter, waitForDevice: Boolean): AdapterConnection =
        connect(waitForDevice).also { opened += it }
}

class FakeTripStore(var manual: OdometerLog? = null) : TripStore {
    val readings = mutableListOf<ObdReading>()
    val queued = mutableListOf<Pair<Long, Int>>()
    var anchor: CodesClearedAnchor? = null

    override suspend fun record(reading: ObdReading): Long {
        val id = readings.size + 1L
        readings += reading.copy(id = id)
        return id
    }

    override suspend fun queue(readingId: Long, km: Int) {
        queued += readingId to km
        val i = readings.indexOfFirst { it.id == readingId }
        readings[i] = readings[i].copy(state = ReadingState.QUEUED)
    }

    override suspend fun latestManual(vehicleId: String): OdometerLog? = manual
    override suspend fun saveAnchor(anchor: CodesClearedAnchor) {
        this.anchor = anchor
    }

    val queuedKm: List<Int> get() = queued.map { it.second }
}

class FakeReadingDao : ObdReadingDao {
    val rows = MutableStateFlow<List<ObdReading>>(emptyList())

    override suspend fun insert(reading: ObdReading): Long {
        val id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1
        rows.value = rows.value + reading.copy(id = id)
        return id
    }

    override suspend fun setState(ids: List<Long>, state: ReadingState, outboxId: String?) {
        rows.value = rows.value.map { if (it.id in ids) it.copy(state = state, outboxId = outboxId) else it }
    }

    override suspend fun inState(profileId: String, state: ReadingState) =
        rows.value.filter { it.profileId == profileId && it.state == state }.sortedBy { it.readAt }

    override fun observeQueued(profileId: String): Flow<Int> = rows.map { list -> list.count { it.profileId == profileId && it.state == ReadingState.QUEUED } }
    override fun observeRecent(profileId: String, limit: Int): Flow<List<ObdReading>> = rows.map { recentOf(it, profileId, limit) }
    override suspend fun recent(profileId: String, limit: Int) = recentOf(rows.value, profileId, limit)

    override suspend fun prune(profileId: String, keep: Int) {
        val kept = recentOf(rows.value, profileId, keep).map { it.id }.toSet()
        rows.value = rows.value.filter { it.profileId != profileId || it.state == ReadingState.QUEUED || it.id in kept }
    }

    override suspend fun clear(profileId: String) {
        rows.value = rows.value.filter { it.profileId != profileId }
    }

    private fun recentOf(list: List<ObdReading>, profileId: String, limit: Int) =
        list.filter { it.profileId == profileId }.sortedByDescending { it.readAt }.take(limit)

    fun state(id: Long) = rows.value.first { it.id == id }.state
}

class MemoryGarageStore : GarageStore {
    val rows = LinkedHashMap<Pair<String, String>, CachedRow>()
    val outbox = mutableListOf<OutboxEntry>()
    private var nextSeq = 1L

    fun add(resource: String, id: String, vehicleId: String?, json: String) {
        rows[resource to id] = CachedRow(resource, id, vehicleId, "2026-10-01T00:00:00Z", json)
    }

    fun vehicle(id: String, role: String = "OWNER", canEdit: Boolean = true) = add(
        "vehicles", id, id,
        """{"id":"$id","ownerUserId":"u1","access":{"role":"$role","canEdit":$canEdit},"make":"Honda","model":"HR-V","year":2016}""",
    )

    override suspend fun row(resource: String, id: String): CachedRow? = rows[resource to id]
    override suspend fun rowsOf(resource: String, vehicleId: String) = rows.values.filter { it.resource == resource && it.vehicleId == vehicleId }
    override suspend fun rowsReferencing(id: String) = rows.values.filter { it.vehicleId == id || id in it.json }
    override suspend fun upsert(rows: List<CachedRow>) = rows.forEach { this.rows[it.resource to it.id] = it }
    override suspend fun deleteRow(resource: String, id: String, cascade: Boolean) {
        rows.remove(resource to id)
        if (cascade) rows.values.removeAll { it.vehicleId == id }
    }

    override suspend fun entries() = outbox.sortedBy { it.seq }
    override suspend fun insert(entry: OutboxEntry): Long = (nextSeq++).also { outbox += entry.copy(seq = it) }
    override suspend fun update(entries: List<OutboxEntry>) = entries.forEach { e -> outbox.replaceAll { if (it.seq == e.seq) e else it } }
    override suspend fun delete(seq: Long) {
        outbox.removeAll { it.seq == seq }
    }

    override suspend fun forgetSince() = Unit
    override suspend fun <T> transaction(block: suspend () -> T): T = block()
}

class NoFiles : StagedFiles {
    override suspend fun stage(key: String, content: InputStream) = "staged/$key"
    override suspend fun read(path: String): ByteArray? = null
    override suspend fun discard(path: String?) = Unit
    override suspend fun publish(path: String, documentId: String) = Unit
    override suspend fun rename(documentId: String, newId: String) = Unit
}

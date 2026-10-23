package io.github.conchad.smartgarage.core.outbox

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.cache.CachedRow
import io.github.conchad.smartgarage.core.cache.MetaEntry
import io.github.conchad.smartgarage.core.cache.OutboxEntry
import io.github.conchad.smartgarage.core.cache.OutboxOp
import io.github.conchad.smartgarage.core.cache.OutboxState
import io.github.conchad.smartgarage.core.data.DocumentInput
import io.github.conchad.smartgarage.core.data.FuelEntryInput
import io.github.conchad.smartgarage.core.data.GarageWrites
import io.github.conchad.smartgarage.core.data.OdometerInput
import io.github.conchad.smartgarage.core.data.SchedulePatch
import io.github.conchad.smartgarage.core.data.VehicleInput
import io.github.conchad.smartgarage.core.data.WriteDenied
import io.github.conchad.smartgarage.core.data.WriteResult
import io.github.conchad.smartgarage.core.data.WriteSession
import io.github.conchad.smartgarage.core.model.FuelEntry
import io.github.conchad.smartgarage.core.network.ApiClient
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.network.HttpClientFactory
import io.github.conchad.smartgarage.core.network.NetworkFailure
import io.github.conchad.smartgarage.core.network.ServerConnection
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.core.sync.SyncEngine
import io.github.conchad.smartgarage.core.sync.SyncOutcome
import io.github.conchad.smartgarage.core.sync.SyncStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.InputStream
import java.net.ConnectException
import java.time.Instant

class OutboxTest {

    // ── Fakes ────────────────────────────────────────────────────────────────────────────────────────────────

    private class MemoryStore : GarageStore {
        val rows = LinkedHashMap<Pair<String, String>, CachedRow>()
        val outbox = mutableListOf<OutboxEntry>()
        var since: String? = "2026-10-20T00:00:00Z"
        private var nextSeq = 1L

        fun add(resource: String, id: String, vehicleId: String?, json: String) {
            rows[resource to id] = CachedRow(resource, id, vehicleId, "2026-10-01T00:00:00Z", json)
        }

        fun entry(key: String): OutboxEntry = outbox.first { it.key == key }

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
        override suspend fun delete(seq: Long) { outbox.removeAll { it.seq == seq } }
        override suspend fun forgetSince() { since = null }
        override suspend fun <T> transaction(block: suspend () -> T): T = block()
    }

    private class MemoryFiles : StagedFiles {
        val staged = mutableMapOf<String, ByteArray>()
        val published = mutableMapOf<String, ByteArray>()

        override suspend fun stage(key: String, content: InputStream) = "staged/$key".also { staged[it] = content.readBytes() }
        override suspend fun read(path: String) = staged[path]
        override suspend fun discard(path: String?) { staged.remove(path) }
        override suspend fun publish(path: String, documentId: String) { staged[path]?.let { published[documentId] = it } }
        override suspend fun rename(documentId: String, newId: String) { published.remove(documentId)?.let { published[newId] = it } }
    }

    private class SyncStoreFake(private val store: MemoryStore) : SyncStore {
        var applied = false
        override suspend fun nextSince() = store.since
        override suspend fun apply(fullResync: Boolean, upserts: List<CachedRow>, deletions: Map<String, List<String>>, meta: List<MetaEntry>) {
            applied = true
        }
    }

    private class Call(val line: String, val key: String?, val body: String, val contentType: String?)

    // ── Fixture ──────────────────────────────────────────────────────────────────────────────────────────────

    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val connection = ServerConnection("https://garage.example.com", token = "sgd_x")
    private val store = MemoryStore()
    private val files = MemoryFiles()
    private var keys = 0
    private var kicks = 0
    private val session = WriteSession(store, files, userId = "u1", now = { now }, newKey = { "k${++keys}" }, onQueued = { kicks++ })
    private val writes = GarageWrites { session }
    private val outbox = session.outbox

    private val calls = mutableListOf<Call>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun vehicle(id: String, role: String, canEdit: Boolean) = store.add(
        "vehicles", id, id,
        """{"id":"$id","ownerUserId":"u1","access":{"role":"$role","canEdit":$canEdit},"make":"Mazda","updatedAt":"2026-10-01T00:00:00Z"}""",
    )

    private fun api(handler: suspend MockRequestHandleScope.(call: Call) -> HttpResponseData): ApiClient = ApiClient(
        HttpClientFactory.create(
            "test",
            MockEngine { request ->
                val call = request.toCall()
                calls += call
                handler(call)
            },
        ),
    )

    private suspend fun HttpRequestData.toCall() = Call(
        line = "${method.value} ${url.encodedPathAndQuery}",
        key = headers["Idempotency-Key"],
        body = String(body.toByteArray()),
        contentType = body.contentType?.toString(),
    )

    private fun MockRequestHandleScope.ok(body: String, status: HttpStatusCode = HttpStatusCode.OK) = respond(body, status, json)
    private fun MockRequestHandleScope.error(status: HttpStatusCode, code: String, message: String) =
        respond("""{"error":{"code":"$code","message":"$message"}}""", status, json)

    private val emptyPage = """{"serverTime":"2026-10-21T11:59:00Z","fullResync":false,"hasMore":false,"changes":{},"deletions":{}}"""

    private suspend fun replay(api: ApiClient) = OutboxReplay(api, connection, store, files).run()

    private fun fuel(filledAt: String = "2026-10-21T10:00:00Z") = FuelEntryInput(filledAt = filledAt, totalCostCents = 5000, liters = 40.0)

    private fun field(json: String, name: String) = ApiJson.parseToJsonElement(json).jsonObject[name]?.jsonPrimitive?.content

    // ── Enqueue ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a queued fill-up shows at once and viewers are refused`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        vehicle("v2", "VIEWER", canEdit = false)
        vehicle("v3", "EDITOR", canEdit = true)

        val result = writes.addFuelEntry("v1", fuel())

        val id = (result as WriteResult.Queued).id
        assertThat(TempIds.isTemp(id)).isTrue()
        val row = store.row("fuelEntries", id)!!
        assertThat(row.pendingKey).isEqualTo("k1")
        assertThat(row.vehicleId).isEqualTo("v1")
        val decoded = ApiJson.decodeFromString(FuelEntry.serializer(), row.json)
        assertThat(decoded.liters).isEqualTo(40.0)
        assertThat(decoded.vehicleId).isEqualTo("v1")
        val entry = store.entry("k1")
        assertThat(entry.op).isEqualTo(OutboxOp.CREATE)
        assertThat(field(entry.body, "vehicleId")).isEqualTo("v1")
        assertThat(kicks).isEqualTo(1)

        assertThat(writes.addFuelEntry("v2", fuel())).isEqualTo(WriteResult.Denied(WriteDenied.ViewOnly))
        assertThat(writes.deleteVehicle("v3")).isEqualTo(WriteResult.Denied(WriteDenied.OwnerOnly))
        assertThat(writes.addFuelEntry("nope", fuel())).isEqualTo(WriteResult.Denied(WriteDenied.Unknown))
        assertThat(store.outbox).hasSize(1)
        assertThat(kicks).isEqualTo(1)
    }

    @Test
    fun `an update merges onto the cached row and a delete removes it`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        store.add("schedules", "s1", "v1", """{"id":"s1","vehicleId":"v1","intervalKm":5000,"notes":"old","category":"OIL"}""")
        store.add("fuelEntries", "f1", "v1", """{"id":"f1","vehicleId":"v1","filledAt":"2026-10-01T00:00:00Z"}""")

        writes.updateSchedule("s1", SchedulePatch(intervalKm = 10_000, clear = setOf("notes")))
        writes.deleteFuelEntry("f1")

        val schedule = ApiJson.parseToJsonElement(store.row("schedules", "s1")!!.json).jsonObject
        assertThat(schedule["intervalKm"]?.jsonPrimitive?.content).isEqualTo("10000")
        assertThat(schedule["category"]?.jsonPrimitive?.content).isEqualTo("OIL")
        assertThat(schedule["notes"]?.jsonPrimitive?.contentOrNullSafe()).isNull()
        assertThat(store.row("schedules", "s1")!!.pendingKey).isEqualTo("k1")
        assertThat(store.entry("k1").body).isEqualTo("""{"intervalKm":10000,"notes":null}""")

        assertThat(store.row("fuelEntries", "f1")).isNull()
        with(store.entry("k2")) {
            assertThat(op).isEqualTo(OutboxOp.DELETE)
            assertThat(method).isEqualTo("DELETE")
            assertThat(WriteRoutes.path(this)).isEqualTo("/api/v1/fuel/f1")
        }
    }

    @Test
    fun `deleting a row created offline cancels the create instead of sending both`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        val id = (writes.addFuelEntry("v1", fuel()) as WriteResult.Queued).id

        writes.deleteFuelEntry(id)

        assertThat(store.outbox).isEmpty()
        assertThat(store.row("fuelEntries", id)).isNull()
    }

    // ── Replay ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `replay sends entries in order with their keys, before the pull`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        val fuelId = (writes.addFuelEntry("v1", fuel()) as WriteResult.Queued).id
        writes.addOdometerLog("v1", OdometerInput(odometerKm = 12_000))
        val api = api { call ->
            when (call.line) {
                "POST /api/v1/fuel" -> ok("""{"id":"f9","vehicleId":"v1","filledAt":"2026-10-21T10:00:00Z","updatedAt":"2026-10-21T12:00:01Z"}""", HttpStatusCode.Created)
                "POST /api/v1/odometer" -> ok("""{"id":"o9","vehicleId":"v1","odometerKm":12000,"recordedAt":"2026-10-21T12:00:00Z"}""", HttpStatusCode.Created)
                else -> ok(emptyPage)
            }
        }
        val pull = SyncStoreFake(store)

        val outcome = SyncEngine.session(api, connection, pull, store, files, { now })

        assertThat(calls.map { it.line })
            .containsExactly("POST /api/v1/fuel", "POST /api/v1/odometer", "GET /api/v1/sync?since=2026-10-20T00%3A00%3A00Z")
            .inOrder()
        assertThat(calls.take(2).map { it.key }).containsExactly("k1", "k2").inOrder()
        assertThat(calls[0].contentType).isEqualTo("application/json")
        assertThat(outcome).isInstanceOf(SyncOutcome.Success::class.java)
        assertThat(pull.applied).isTrue()
        assertThat(store.outbox).isEmpty()
        assertThat(store.row("fuelEntries", fuelId)).isNull()
        with(store.row("fuelEntries", "f9")!!) {
            assertThat(pendingKey).isNull()
            assertThat(updatedAt).isEqualTo("2026-10-21T12:00:01Z")
        }
        assertThat(store.row("odometerLogs", "o9")).isNotNull()
    }

    @Test
    fun `an unreachable server stops the replay, keeps order and skips the pull, and the retry reuses the keys`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        writes.addFuelEntry("v1", fuel())
        writes.addOdometerLog("v1", OdometerInput(odometerKm = 12_000))
        var online = false
        val api = api { call ->
            if (!online) throw ConnectException("refused")
            when (call.line) {
                "POST /api/v1/fuel" -> ok("""{"id":"f9","vehicleId":"v1"}""", HttpStatusCode.Created)
                "POST /api/v1/odometer" -> ok("""{"id":"o9","vehicleId":"v1"}""", HttpStatusCode.Created)
                else -> ok(emptyPage)
            }
        }
        val pull = SyncStoreFake(store)

        val offline = SyncEngine.session(api, connection, pull, store, files, { now })

        assertThat(offline).isEqualTo(SyncOutcome.Failed(ServerProblem.Unreachable(NetworkFailure.Refused)))
        assertThat(calls.map { it.line }).containsExactly("POST /api/v1/fuel")
        assertThat(pull.applied).isFalse()
        assertThat(store.outbox.map { it.state }).containsExactly(OutboxState.PENDING, OutboxState.PENDING)
        assertThat(store.entry("k1").attempts).isEqualTo(1)
        assertThat(store.entry("k2").attempts).isEqualTo(0)

        online = true
        calls.clear()
        val back = SyncEngine.session(api, connection, pull, store, files, { now })

        assertThat(back).isInstanceOf(SyncOutcome.Success::class.java)
        assertThat(calls.map { it.line }).containsExactly("POST /api/v1/fuel", "POST /api/v1/odometer", "GET /api/v1/sync?since=2026-10-20T00%3A00%3A00Z").inOrder()
        assertThat(calls.take(2).map { it.key }).containsExactly("k1", "k2").inOrder()
        assertThat(store.outbox).isEmpty()
    }

    @Test
    fun `a 5xx or 429 stops the replay without failing the entry`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        writes.addFuelEntry("v1", fuel())
        writes.addOdometerLog("v1", OdometerInput(odometerKm = 12_000))

        val outcome = replay(api { error(HttpStatusCode.ServiceUnavailable, "internal", "down") })

        assertThat(outcome).isEqualTo(ReplayOutcome.Stopped(ServerProblem.Unexpected(503, "down")))
        assertThat(calls).hasSize(1)
        assertThat(store.outbox.map { it.state }).containsExactly(OutboxState.PENDING, OutboxState.PENDING)
        assertThat(store.entry("k1").lastStatus).isEqualTo(503)
    }

    @Test
    fun `a created row gets its server id everywhere`() = runTest {
        val vehicleId = (writes.addVehicle(VehicleInput(make = "Mazda", model = "MX-5", productionYear = 2019, currentOdometerKm = 1000)) as WriteResult.Queued).id
        // Rights on the offline vehicle come from its optimistic row: the creator owns it.
        val fuelId = (writes.addFuelEntry(vehicleId, fuel()) as WriteResult.Queued).id
        assertThat(store.row("fuelEntries", fuelId)!!.vehicleId).isEqualTo(vehicleId)
        val api = api { call ->
            when (call.line) {
                "POST /api/v1/vehicles" -> ok("""{"id":"v9","ownerUserId":"u1","access":{"role":"OWNER","canEdit":true},"make":"Mazda","model":"MX-5"}""", HttpStatusCode.Created)
                // Stop here so the fill-up's remapped state is observable.
                else -> error(HttpStatusCode.ServiceUnavailable, "internal", "down")
            }
        }

        replay(api)

        assertThat(store.row("vehicles", vehicleId)).isNull()
        assertThat(store.row("vehicles", "v9")!!.pendingKey).isNull()
        val fuelRow = store.row("fuelEntries", fuelId)!!
        assertThat(fuelRow.vehicleId).isEqualTo("v9")
        assertThat(field(fuelRow.json, "vehicleId")).isEqualTo("v9")
        assertThat(fuelRow.pendingKey).isEqualTo("k2")
        assertThat(field(store.entry("k2").body, "vehicleId")).isEqualTo("v9")
        assertThat(field(calls[1].body, "vehicleId")).isEqualTo("v9")
    }

    @Test
    fun `a vehicle created offline hands its temp and server ids to the local prefs hook`() = runTest {
        val vehicleId = (writes.addVehicle(VehicleInput(make = "Mazda", model = "MX-5", productionYear = 2019, currentOdometerKm = 1000)) as WriteResult.Queued).id
        val api = api { call ->
            when (call.line) {
                "POST /api/v1/vehicles" -> ok("""{"id":"v9","ownerUserId":"u1","access":{"role":"OWNER","canEdit":true},"make":"Mazda","model":"MX-5"}""", HttpStatusCode.Created)
                else -> error(HttpStatusCode.ServiceUnavailable, "internal", "down")
            }
        }
        val adopted = mutableListOf<Pair<String, String>>()

        OutboxReplay(api, connection, store, files) { temp, server -> adopted += temp to server }.run()

        assertThat(adopted).containsExactly(vehicleId to "v9")
    }

    @Test
    fun `a rejected create is marked failed, dropped locally, and the queue moves on`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        val fuelId = (writes.addFuelEntry("v1", fuel(filledAt = "not a date")) as WriteResult.Queued).id
        writes.addOdometerLog("v1", OdometerInput(odometerKm = 12_000))
        val api = api { call ->
            when (call.line) {
                "POST /api/v1/fuel" -> error(HttpStatusCode.UnprocessableEntity, "validation_failed", "The request body is invalid")
                else -> ok("""{"id":"o9","vehicleId":"v1"}""", HttpStatusCode.Created)
            }
        }

        val outcome = replay(api)

        assertThat(outcome).isEqualTo(ReplayOutcome.Done)
        assertThat(calls.map { it.line }).containsExactly("POST /api/v1/fuel", "POST /api/v1/odometer").inOrder()
        with(store.entry("k1")) {
            assertThat(state).isEqualTo(OutboxState.FAILED)
            assertThat(lastStatus).isEqualTo(422)
            assertThat(lastError).isEqualTo("The request body is invalid")
        }
        assertThat(store.row("fuelEntries", fuelId)).isNull()
        assertThat(store.outbox).hasSize(1)
        assertThat(store.row("odometerLogs", "o9")).isNotNull()
    }

    @Test
    fun `a failed create takes everything built on its temp id with it`() = runTest {
        val vehicleId = (writes.addVehicle(VehicleInput(make = "Mazda", model = "MX-5", productionYear = 2019, currentOdometerKm = 1000)) as WriteResult.Queued).id
        val fuelId = (writes.addFuelEntry(vehicleId, fuel()) as WriteResult.Queued).id

        replay(api { error(HttpStatusCode.UnprocessableEntity, "validation_failed", "make: required") })

        assertThat(calls).hasSize(1)
        assertThat(store.outbox.map { it.state }).containsExactly(OutboxState.FAILED, OutboxState.FAILED)
        assertThat(store.entry("k2").lastCode).isEqualTo(DEPENDENCY_FAILED)
        assertThat(store.rows).isEmpty()
        assertThat(store.row("fuelEntries", fuelId)).isNull()

        // The dependent can only be dismissed; the vehicle itself can be retried under a fresh key.
        assertThat(outbox.retry("k2")).isFalse()
        assertThat(outbox.retry("k1")).isTrue()
        val retried = store.outbox.first { it.op == OutboxOp.CREATE && it.resource == "vehicles" }
        assertThat(retried.key).isEqualTo("k3")
        assertThat(retried.state).isEqualTo(OutboxState.PENDING)
        assertThat(store.row("vehicles", vehicleId)!!.pendingKey).isEqualTo("k3")
        outbox.dismiss("k2")
        assertThat(store.outbox).hasSize(1)
    }

    @Test
    fun `a rejected update is re-fetched so the cache matches the server`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        store.add("schedules", "s1", "v1", """{"id":"s1","vehicleId":"v1","intervalKm":5000}""")
        store.add("schedules", "s2", "v1", """{"id":"s2","vehicleId":"v1","intervalKm":5000}""")
        writes.updateSchedule("s1", SchedulePatch(intervalKm = 10_000))
        writes.updateSchedule("s2", SchedulePatch(intervalKm = 10_000))
        val api = api { call ->
            when (call.line) {
                "PATCH /api/v1/schedules/s1", "PATCH /api/v1/schedules/s2" -> error(HttpStatusCode.Forbidden, "forbidden", "You have view-only access to this vehicle")
                "GET /api/v1/schedules/s1" -> ok("""{"id":"s1","vehicleId":"v1","intervalKm":7000,"updatedAt":"2026-10-21T11:00:00Z"}""")
                else -> error(HttpStatusCode.NotFound, "not_found", "Not found")
            }
        }

        val outcome = replay(api)

        assertThat(outcome).isEqualTo(ReplayOutcome.Done)
        assertThat(calls.map { it.line }).containsExactly(
            "PATCH /api/v1/schedules/s1", "GET /api/v1/schedules/s1",
            "PATCH /api/v1/schedules/s2", "GET /api/v1/schedules/s2",
        ).inOrder()
        assertThat(store.outbox.map { it.state }).containsExactly(OutboxState.FAILED, OutboxState.FAILED)
        with(store.row("schedules", "s1")!!) {
            assertThat(field(json, "intervalKm")).isEqualTo("7000")
            assertThat(pendingKey).isNull()
        }
        assertThat(store.row("schedules", "s2")).isNull()
    }

    @Test
    fun `a delete whose target is already gone counts as done`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        store.add("fuelEntries", "f1", "v1", """{"id":"f1","vehicleId":"v1"}""")
        writes.deleteFuelEntry("f1")

        val outcome = replay(api { error(HttpStatusCode.NotFound, "not_found", "Not found") })

        assertThat(outcome).isEqualTo(ReplayOutcome.Done)
        assertThat(store.outbox).isEmpty()
        assertThat(store.row("fuelEntries", "f1")).isNull()
    }

    @Test
    fun `a revoked token signs out mid-replay`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        writes.addFuelEntry("v1", fuel())

        assertThat(replay(api { error(HttpStatusCode.Unauthorized, "unauthorized", "revoked") })).isEqualTo(ReplayOutcome.SignedOut)
    }

    @Test
    fun `a document goes out as multipart from the staged file and opens offline meanwhile`() = runTest {
        vehicle("v1", "OWNER", canEdit = true)
        val bytes = "%PDF-1.4 fake".toByteArray()
        val id = (writes.addDocument("v1", DocumentInput(title = "Invoice"), "inv.pdf", "application/pdf", bytes.inputStream(), bytes.size.toLong()) as WriteResult.Queued).id

        assertThat(files.published[id]).isEqualTo(bytes)
        assertThat(store.row("documents", id)!!.pendingKey).isEqualTo("k1")
        assertThat(field(store.row("documents", id)!!.json, "originalFilename")).isEqualTo("inv.pdf")
        val api = api { call ->
            assertThat(call.line).isEqualTo("POST /api/v1/documents")
            assertThat(call.contentType).startsWith("multipart/form-data")
            assertThat(call.body).contains("name=\"vehicleId\"")
            assertThat(call.body).contains("name=\"title\"")
            assertThat(call.body).contains("filename=\"inv.pdf\"")
            assertThat(call.body).contains("%PDF-1.4 fake")
            ok("""{"id":"d9","vehicleId":"v1","originalFilename":"inv.pdf","mimeType":"application/pdf","createdAt":"2026-10-21T12:00:00Z"}""", HttpStatusCode.Created)
        }

        assertThat(replay(api)).isEqualTo(ReplayOutcome.Done)

        assertThat(store.row("documents", id)).isNull()
        assertThat(store.row("documents", "d9")).isNotNull()
        assertThat(files.published.keys).containsExactly("d9")
        assertThat(files.staged).isEmpty()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content
}

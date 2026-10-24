package io.github.conchad.smartgarage.core.obd.upload

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.data.GarageWrites
import io.github.conchad.smartgarage.core.data.WriteSession
import io.github.conchad.smartgarage.core.network.ApiJson
import io.github.conchad.smartgarage.core.network.NetworkFailure
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.core.obd.FakeReadingDao
import io.github.conchad.smartgarage.core.obd.MemoryGarageStore
import io.github.conchad.smartgarage.core.obd.NoFiles
import io.github.conchad.smartgarage.core.obd.data.ObdReading
import io.github.conchad.smartgarage.core.obd.data.ReadingState
import io.github.conchad.smartgarage.core.sync.SyncOutcome
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.Instant

class ObdUploaderTest {
    private val dao = FakeReadingDao()
    private val store = MemoryGarageStore().apply { vehicle("v1") }
    private var keys = 0
    private val session = WriteSession(store, NoFiles(), userId = "u1", now = { Instant.parse("2026-10-24T12:00:00Z") }, newKey = { "k${++keys}" })
    private var outcome: SyncOutcome = SyncOutcome.Success(0, 0, fullResync = false)
    private var cellular = false
    private val synced = mutableListOf<Long>()
    private var syncs = 0

    private val uploader = ObdUploader(
        readings = dao,
        writes = { GarageWrites { session } },
        withdraw = { _, id -> session.outbox.withdraw("odometerLogs", id) },
        sync = { syncs++; outcome },
        cellularAllowed = { cellular },
        onSynced = { _, at -> synced += at },
        clock = { 42 },
    )

    private suspend fun reading(km: Int?, state: ReadingState = ReadingState.QUEUED, vehicleId: String = "v1", at: Long = km?.toLong() ?: 0) =
        dao.insert(ObdReading(profileId = "p1", vehicleId = vehicleId, km = km, rawReply = "7E8…", readAt = at, tripId = "t1", state = state))

    private fun body(i: Int = 0) = ApiJson.parseToJsonElement(store.outbox[i].body).jsonObject

    @Test
    fun `the highest queued reading goes to the outbox as source obd, older ones are superseded`() = runTest {
        val low = reading(84_041, at = 1_000)
        val high = reading(84_050, at = 2_000)
        val local = reading(84_045, state = ReadingState.LOCAL)

        assertThat(uploader.run("p1")).isEqualTo(ObdUploader.Result.DONE)

        assertThat(store.outbox).hasSize(1)
        with(body()) {
            assertThat(this["odometerKm"]!!.jsonPrimitive.content).isEqualTo("84050")
            assertThat(this["source"]!!.jsonPrimitive.content).isEqualTo("obd")
            assertThat(this["recordedAt"]!!.jsonPrimitive.content).isEqualTo(Instant.ofEpochMilli(2_000).toString())
            assertThat(this["vehicleId"]!!.jsonPrimitive.content).isEqualTo("v1")
        }
        assertThat(dao.state(high)).isEqualTo(ReadingState.SENT)
        assertThat(dao.rows.value.first { it.id == high }.outboxId).isEqualTo(store.outbox.single().targetId)
        assertThat(dao.state(low)).isEqualTo(ReadingState.SUPERSEDED)
        assertThat(dao.state(local)).isEqualTo(ReadingState.LOCAL)
        assertThat(syncs).isEqualTo(1)
        assertThat(synced).containsExactly(42L)
    }

    @Test
    fun `server unreachable with cellular off takes the reading back out of the outbox`() = runTest {
        val id = reading(84_050)
        outcome = SyncOutcome.Failed(ServerProblem.Unreachable(NetworkFailure.Refused))

        assertThat(uploader.run("p1")).isEqualTo(ObdUploader.Result.RETRY)

        // Nothing left for the app's ordinary (possibly mobile-data) syncs to send; the reading waits for Wi-Fi again.
        assertThat(store.outbox).isEmpty()
        assertThat(store.rows.keys.none { it.first == "odometerLogs" }).isTrue()
        assertThat(dao.state(id)).isEqualTo(ReadingState.QUEUED)
        assertThat(synced).isEmpty()

        outcome = SyncOutcome.Success(1, 0, fullResync = false)
        assertThat(uploader.run("p1")).isEqualTo(ObdUploader.Result.DONE)
        assertThat(dao.state(id)).isEqualTo(ReadingState.SENT)
        assertThat(store.outbox).hasSize(1) // the fake sync doesn't replay; the outbox owns delivery from here
    }

    @Test
    fun `with cellular allowed a failed sync leaves the reading in the outbox`() = runTest {
        cellular = true
        val id = reading(84_050)
        outcome = SyncOutcome.Failed(ServerProblem.Unreachable(NetworkFailure.Refused))

        assertThat(uploader.run("p1")).isEqualTo(ObdUploader.Result.RETRY)
        assertThat(store.outbox).hasSize(1)
        assertThat(dao.state(id)).isEqualTo(ReadingState.SENT)
    }

    @Test
    fun `a view-only vehicle rejects its readings, an uncached one keeps them queued`() = runTest {
        store.vehicle("shared", role = "VIEWER", canEdit = false)
        val viewer = reading(10_000, vehicleId = "shared")
        val unknown = reading(20_000, vehicleId = "gone")
        val noKm = reading(null)

        uploader.run("p1")

        assertThat(dao.state(viewer)).isEqualTo(ReadingState.REJECTED)
        assertThat(dao.state(unknown)).isEqualTo(ReadingState.QUEUED)
        assertThat(dao.state(noKm)).isEqualTo(ReadingState.QUEUED)
        assertThat(store.outbox).isEmpty()
    }

    @Test
    fun `pick prefers the highest km, then the latest`() {
        fun r(id: Long, km: Int, at: Long) = ObdReading(id, "p1", "v1", km, rawReply = "", readAt = at, tripId = "t")
        val (send, rest) = ObdUploader.pick(listOf(r(1, 100, 5), r(2, 120, 1), r(3, 120, 2)))
        assertThat(send.id).isEqualTo(3)
        assertThat(rest.map { it.id }).containsExactly(1L, 2L)
    }
}

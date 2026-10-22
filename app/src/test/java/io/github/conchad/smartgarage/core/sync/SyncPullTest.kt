package io.github.conchad.smartgarage.core.sync

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.cache.CachedRow
import io.github.conchad.smartgarage.core.cache.MetaEntry
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import io.github.conchad.smartgarage.core.network.ApiClient
import io.github.conchad.smartgarage.core.network.HttpClientFactory
import io.github.conchad.smartgarage.core.network.ServerConnection
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant

class SyncPullTest {

    private class FakeStore(private val since: String? = null) : SyncStore {
        var applied: Applied? = null

        data class Applied(
            val fullResync: Boolean,
            val upserts: List<CachedRow>,
            val deletions: Map<String, List<String>>,
            val meta: List<MetaEntry>,
        )

        override suspend fun nextSince() = since

        override suspend fun apply(
            fullResync: Boolean,
            upserts: List<CachedRow>,
            deletions: Map<String, List<String>>,
            meta: List<MetaEntry>,
        ) {
            applied = Applied(fullResync, upserts, deletions, meta)
        }
    }

    private val connection = ServerConnection("https://garage.example.com", token = "sgd_x")
    private val now = Instant.parse("2026-10-21T12:00:00Z")
    private val urls = mutableListOf<String>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun api(vararg pages: Pair<HttpStatusCode, String>): ApiClient {
        var index = 0
        return ApiClient(
            HttpClientFactory.create(
                "test",
                MockEngine { request ->
                    urls += request.url.encodedPathAndQuery
                    val (status, body) = pages[index++]
                    respond(body, status, json)
                },
            ),
        )
    }

    private val page1 = """{"serverTime":"2026-10-21T11:59:00Z","fullResync":true,"hasMore":true,"cursor":"c1",
        "changes":{"vehicles":[{"id":"v1","make":"Mazda","updatedAt":"2026-10-01T00:00:00Z"}],
                   "fuelEntries":[{"id":"f1","vehicleId":"v1"},{"id":"f2","vehicleId":"v1"}]},
        "deletions":{},
        "preferences":{"distanceUnit":"mi","volumeUnit":"gal","currency":"USD"},
        "noteTags":["oil"]}"""

    private val page2 = """{"serverTime":"2026-10-21T11:59:00Z","fullResync":true,"hasMore":false,"nextSince":"2026-10-21T11:58:00Z",
        "changes":{"schedules":[{"id":"s1","vehicleId":"v1"}]},
        "deletions":{"fuelEntries":["f2"]}}"""

    @Test
    fun `collects every page and applies them in one go`() = runTest {
        val store = FakeStore()
        var prefs: DisplayPreferences? = null

        val outcome = SyncEngine.pull(api(HttpStatusCode.OK to page1, HttpStatusCode.OK to page2), connection, store, { now }) {
            prefs = it
        }

        assertThat(urls).containsExactly("/api/v1/sync", "/api/v1/sync?cursor=c1").inOrder()
        val applied = store.applied!!
        assertThat(applied.fullResync).isTrue()
        // f2 changed on page 1 and was deleted on page 2: it must not be written.
        assertThat(applied.upserts.map { it.resource to it.id })
            .containsExactly("vehicles" to "v1", "fuelEntries" to "f1", "schedules" to "s1")
        assertThat(applied.upserts.first { it.id == "v1" }.vehicleId).isEqualTo("v1")
        assertThat(applied.deletions).containsExactly("fuelEntries", listOf("f2"))
        assertThat(applied.meta).containsExactly(
            MetaEntry(SyncEngine.META_NEXT_SINCE, "2026-10-21T11:58:00Z"),
            MetaEntry(SyncEngine.META_LAST_SYNC, now.toString()),
            MetaEntry(SyncEngine.META_NOTE_TAGS, """["oil"]"""),
        )
        assertThat(prefs?.distanceUnit).isEqualTo("mi")
        assertThat(outcome).isEqualTo(SyncOutcome.Success(upserted = 3, deleted = 1, fullResync = true))
    }

    @Test
    fun `incremental sync sends the stored since`() = runTest {
        val store = FakeStore(since = "2026-10-20T00:00:00Z")
        val empty = """{"serverTime":"2026-10-21T11:59:00Z","fullResync":false,"hasMore":false,"changes":{},"deletions":{}}"""

        SyncEngine.pull(api(HttpStatusCode.OK to empty), connection, store, { now })

        assertThat(urls.single()).isEqualTo("/api/v1/sync?since=2026-10-20T00%3A00%3A00Z")
        // Without nextSince the server time becomes the next watermark.
        assertThat(store.applied!!.meta.first()).isEqualTo(MetaEntry(SyncEngine.META_NEXT_SINCE, "2026-10-21T11:59:00Z"))
    }

    @Test
    fun `a failure on a later page leaves the cache untouched`() = runTest {
        val store = FakeStore()

        val outcome = SyncEngine.pull(
            api(HttpStatusCode.OK to page1, HttpStatusCode.InternalServerError to """{"error":{"code":"internal","message":"x"}}"""),
            connection,
            store,
            { now },
        )

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed::class.java)
        assertThat(store.applied).isNull()
    }

    @Test
    fun `a rejected token reports signed out`() = runTest {
        val store = FakeStore()

        val outcome = SyncEngine.pull(
            api(HttpStatusCode.Unauthorized to """{"error":{"code":"unauthorized","message":"revoked"}}"""),
            connection,
            store,
            { now },
        )

        assertThat(outcome).isEqualTo(SyncOutcome.SignedOut)
        assertThat(store.applied).isNull()
    }

    @Test
    fun `rows with path-like ids are dropped and counted, the rest still applies`() = runTest {
        val store = FakeStore()
        val page = """{"serverTime":"2026-10-21T11:59:00Z","fullResync":false,"hasMore":false,
            "changes":{"vehicles":[{"id":"v1","make":"Mazda"},{"id":"../../databases/cache-other.db","make":"Evil"}],
                       "documents":[{"id":"d1","vehicleId":"v1"},{"id":"d2","vehicleId":"../x"},{"id":"..","vehicleId":"v1"}]},
            "deletions":{}}"""

        val outcome = SyncEngine.pull(api(HttpStatusCode.OK to page), connection, store, { now })

        assertThat(outcome).isEqualTo(SyncOutcome.Success(upserted = 2, deleted = 0, fullResync = false, rejected = 3))
        assertThat(store.applied!!.upserts.map { it.resource to it.id }).containsExactly("vehicles" to "v1", "documents" to "d1")
    }
}

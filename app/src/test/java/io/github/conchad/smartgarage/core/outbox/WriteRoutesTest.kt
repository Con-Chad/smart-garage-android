package io.github.conchad.smartgarage.core.outbox

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.cache.OutboxEntry
import io.github.conchad.smartgarage.core.cache.OutboxOp
import io.github.conchad.smartgarage.core.network.ApiJson
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class WriteRoutesTest {

    private fun entry(targetId: String, method: String = "PATCH", subpath: String? = null) = OutboxEntry(
        key = "k1", resource = "fuelEntries", op = OutboxOp.UPDATE, method = method, subpath = subpath,
        targetId = targetId, body = "{}", createdAt = "2026-10-21T12:00:00Z",
    )

    @Test
    fun `ids are a single encoded path segment`() {
        assertThat(WriteRoutes.path(entry("f1"))).isEqualTo("/api/v1/fuel/f1")
        assertThat(WriteRoutes.path(entry("f1", subpath = "receipt"))).isEqualTo("/api/v1/fuel/f1/receipt")
        assertThat(WriteRoutes.path(entry("../auth/logout"))).isEqualTo("/api/v1/fuel/..%2Fauth%2Flogout")
        assertThat(WriteRoutes.path(entry("a?x=1#y"))).doesNotContain("?")
        assertThat(WriteRoutes.itemPath("vehicles", "v/1")).isEqualTo("/api/v1/vehicles/v%2F1")
    }

    @Test
    fun `rows with unusable ids are dropped at the boundary`() {
        fun row(json: String) = ServerRows.toCachedRow("fuelEntries", ApiJson.parseToJsonElement(json).jsonObject)

        assertThat(row("""{"id":"f1","vehicleId":"v1"}""")?.id).isEqualTo("f1")
        assertThat(row("""{"id":"f1"}""")?.vehicleId).isNull()
        assertThat(row("""{"id":"../x","vehicleId":"v1"}""")).isNull()
        assertThat(row("""{"id":"f1","vehicleId":"../../v"}""")).isNull()
        assertThat(row("""{"id":"","vehicleId":"v1"}""")).isNull()
        assertThat(row("""{"vehicleId":"v1"}""")).isNull()
        assertThat(ServerRows.toCachedRow("vehicles", ApiJson.parseToJsonElement("""{"id":"/abs"}""").jsonObject)).isNull()
    }
}

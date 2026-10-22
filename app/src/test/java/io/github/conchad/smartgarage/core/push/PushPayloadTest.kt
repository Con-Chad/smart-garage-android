package io.github.conchad.smartgarage.core.push

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The contract's payload (PUSH.md §4) as the app reads it: ids and the web path are validated, the text is kept. */
class PushPayloadTest {

    private fun parse(json: String) = PushPayload.parse(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `full maintenance payload parses with both ids and the path`() {
        val payload = parse(
            """{"type":"maintenance_due","title":"1 maintenance item","body":"• Honda Civic: Brake fluid — overdue",
               "vehicleId":"clxv1abc","scheduleId":"clxs9xyz","url":"/maintenance"}""",
        )

        assertThat(payload).isEqualTo(
            PushPayload(
                type = "maintenance_due",
                title = "1 maintenance item",
                body = "• Honda Civic: Brake fluid — overdue",
                vehicleId = "clxv1abc",
                scheduleId = "clxs9xyz",
                url = "/maintenance",
            ),
        )
    }

    @Test
    fun `optional ids may be missing and unknown types are kept`() {
        val payload = parse("""{"type":"something_new","title":"Hello","body":"Line 1\nLine 2","url":"/"}""")

        assertThat(payload).isEqualTo(PushPayload(type = "something_new", title = "Hello", body = "Line 1\nLine 2", url = "/"))
        assertThat(payload!!.vehicleId).isNull()
        assertThat(payload.scheduleId).isNull()
    }

    @Test
    fun `an invalid id is rejected while the alert text is kept`() {
        val payload = parse("""{"type":"maintenance_due","title":"T","body":"B","vehicleId":"../etc","scheduleId":"ok_1","url":"/maintenance"}""")

        assertThat(payload).isNotNull()
        assertThat(payload!!.vehicleId).isNull()
        assertThat(payload.scheduleId).isEqualTo("ok_1")
    }

    @Test
    fun `a url that is not a plain site path is dropped`() {
        assertThat(parse("""{"type":"test","title":"T","body":"B","url":"https://evil.example/x"}""")!!.url).isNull()
        assertThat(parse("""{"type":"test","title":"T","body":"B","url":"/maintenance?x=1"}""")!!.url).isNull()
        assertThat(parse("""{"type":"test","title":"T","body":"B","url":"//evil.example"}""")!!.url).isNull()
        assertThat(parse("""{"type":"test","title":"T","body":"B","url":"/odometer"}""")!!.url).isEqualTo("/odometer")
    }

    @Test
    fun `garbage, non-JSON and empty text are not shown`() {
        assertThat(PushPayload.parse(byteArrayOf(0x01, 0x02, 0x7f))).isNull()
        assertThat(parse("""{"title":"only a title"}""")).isNull()
        assertThat(parse("""{"type":"test","title":"","body":"B"}""")).isNull()
        assertThat(parse("""{"type":"test","title":"T","body":"   "}""")).isNull()
    }

    @Test
    fun `an intent target needs a valid profile id and drops bad ids`() {
        assertThat(PushTarget.validated(null, "v", null, null)).isNull()
        assertThat(PushTarget.validated("../x", "v", null, null)).isNull()
        assertThat(PushTarget.validated("8d1c7f5e-1a2b-4c3d-9e8f-0a1b2c3d4e5f", "bad/id", "s1", "javascript:alert(1)"))
            .isEqualTo(PushTarget("8d1c7f5e-1a2b-4c3d-9e8f-0a1b2c3d4e5f", vehicleId = null, scheduleId = "s1", url = null))
    }
}

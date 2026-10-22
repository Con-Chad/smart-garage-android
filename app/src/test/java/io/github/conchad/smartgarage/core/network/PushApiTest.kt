package io.github.conchad.smartgarage.core.network

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/** The four push calls of docs/mobile/PUSH.md as they leave the device. */
class PushApiTest {

    private val connection = ServerConnection(baseUrl = "https://garage.example.com/", token = "sgd_abc")
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ApiClient(HttpClientFactory.create("test", MockEngine { request -> requests += request; handler(request) }))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg extra: Pair<String, String>) =
        respond(
            body,
            status,
            headersOf(*(listOf(HttpHeaders.ContentType to listOf("application/json")) + extra.map { it.first to listOf(it.second) }).toTypedArray()),
        )

    private val subscription = PushSubscriptionRequest(
        endpoint = "https://ntfy.sh/upAbCdEf12?up=1",
        keys = PushKeysDto(p256dh = "BPublicKeyBase64Url", auth = "AuthSecret16Bytes"),
    )

    @Test
    fun `register is a PUT to the device's own push resource with endpoint and keys`() = runTest {
        val api = client {
            json("""{"push":{"deviceId":"dev1","endpoint":"https://ntfy.sh/upAbCdEf12?up=1","createdAt":"2026-10-22T19:00:00Z","failedAt":null,"failureCount":0}}""")
        }

        val result = api.registerPush(connection, "dev1", subscription)

        assertThat(result).isEqualTo(ApiResult.Ok(PushRegistrationResponse(PushInfoDto(deviceId = "dev1", createdAt = "2026-10-22T19:00:00Z", failureCount = 0))))
        val sent = requests.single()
        assertThat(sent.method).isEqualTo(HttpMethod.Put)
        assertThat(sent.url.toString()).isEqualTo("https://garage.example.com/api/v1/devices/dev1/push")
        assertThat(sent.headers[HttpHeaders.Authorization]).isEqualTo("Bearer sgd_abc")
        val body = Json.parseToJsonElement((sent.body as TextContent).text).jsonObject
        assertThat(sent.body.contentType?.contentSubtype).isEqualTo("json")
        assertThat(body["endpoint"]!!.jsonPrimitive.content).isEqualTo("https://ntfy.sh/upAbCdEf12?up=1")
        assertThat(body["keys"]!!.jsonObject["p256dh"]!!.jsonPrimitive.content).isEqualTo("BPublicKeyBase64Url")
        assertThat(body["keys"]!!.jsonObject["auth"]!!.jsonPrimitive.content).isEqualTo("AuthSecret16Bytes")
    }

    @Test
    fun `an endpoint the server refuses comes back with its code and message`() = runTest {
        val api = client {
            json("""{"error":{"code":"endpoint_rejected","message":"Push endpoint rejected: private address"}}""", HttpStatusCode.UnprocessableEntity)
        }

        val result = api.registerPush(connection, "dev1", subscription)

        assertThat(result).isEqualTo(ApiResult.HttpError(422, "endpoint_rejected", "Push endpoint rejected: private address"))
    }

    @Test
    fun `unregister is a DELETE and 204 is success`() = runTest {
        val api = client { respond("", HttpStatusCode.NoContent) }

        val result = api.unregisterPush(connection, "dev1")

        assertThat(result).isEqualTo(ApiResult.Ok(Unit))
        val sent = requests.single()
        assertThat(sent.method).isEqualTo(HttpMethod.Delete)
        assertThat(sent.url.toString()).isEqualTo("https://garage.example.com/api/v1/devices/dev1/push")
    }

    @Test
    fun `test push is a POST and a gone subscription is reported by code`() = runTest {
        val ok = client { json("""{"ok":true,"deviceId":"dev1"}""") }
        assertThat(ok.testPush(connection, "dev1")).isEqualTo(ApiResult.Ok(PushTestResponse(ok = true, deviceId = "dev1")))
        val sent = requests.single()
        assertThat(sent.method).isEqualTo(HttpMethod.Post)
        assertThat(sent.url.toString()).isEqualTo("https://garage.example.com/api/v1/devices/dev1/push/test")

        val gone = client { json("""{"error":{"code":"push_subscription_gone","message":"distributor says 410"}}""", HttpStatusCode.BadGateway) }
        assertThat(gone.testPush(connection, "dev1")).isEqualTo(ApiResult.HttpError(502, "push_subscription_gone", "distributor says 410"))

        val limited = client { json("""{"error":{"code":"rate_limited"}}""", HttpStatusCode.TooManyRequests, HttpHeaders.RetryAfter to "600") }
        assertThat(limited.testPush(connection, "dev1")).isEqualTo(ApiResult.HttpError(429, "rate_limited", null, retryAfterSeconds = 600))
    }

    @Test
    fun `the VAPID key is fetched from the server`() = runTest {
        val api = client { json("""{"publicKey":"BKeyKeyKey","algorithm":"ES256","curve":"P-256"}""") }

        val result = api.pushVapid(connection)

        assertThat(result).isEqualTo(ApiResult.Ok(VapidKeyResponse("BKeyKeyKey", "ES256", "P-256")))
        assertThat(requests.single().url.toString()).isEqualTo("https://garage.example.com/api/v1/push/vapid")
    }

    @Test
    fun `a device id that is not a server id never becomes a path`() = runTest {
        val api = client { respond("", HttpStatusCode.NoContent) }

        assertThrows(InvalidServerIdException::class.java) {
            kotlinx.coroutines.runBlocking { api.unregisterPush(connection, "../admin") }
        }
        assertThat(requests).isEmpty()
    }
}

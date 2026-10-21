package io.github.conchad.smartgarage.core.network

import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ApiClientTest {

    private val connection = ServerConnection(
        baseUrl = "https://garage.example.com",
        cfAccess = CfAccessCredentials("id.access", "secret"),
    )
    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ApiClient(HttpClientFactory.create("test", MockEngine { request -> requests += request; handler(request) }))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg extra: Pair<String, String>) =
        respond(
            body,
            status,
            headersOf(*(listOf(HttpHeaders.ContentType to listOf("application/json; charset=utf-8")) + extra.map { it.first to listOf(it.second) }).toTypedArray()),
        )

    private fun MockRequestHandleScope.html(status: HttpStatusCode, vararg headers: Pair<String, String>) =
        respond("<html></html>", status, headersOf(*(listOf(HttpHeaders.ContentType to listOf("text/html")) + headers.map { it.first to listOf(it.second) }).toTypedArray()))

    private val healthy = """{"service":"smart-garage","status":"ok","version":"1.4.0","apiVersion":1,"database":"connected"}"""

    @Test
    fun `health ok sends the Cloudflare service token`() = runTest {
        val result = client { json(healthy, extra = arrayOf("cf-ray" to "abc")) }.health(connection)

        assertThat(result).isEqualTo(HealthResult.Ok(version = "1.4.0", behindCloudflare = true))
        val sent = requests.single()
        assertThat(sent.url.toString()).isEqualTo("https://garage.example.com/api/v1/health")
        assertThat(sent.headers["CF-Access-Client-Id"]).isEqualTo("id.access")
        assertThat(sent.headers["CF-Access-Client-Secret"]).isEqualTo("secret")
        assertThat(sent.headers[HttpHeaders.Authorization]).isNull()
    }

    @Test
    fun `Cloudflare Access 403 and login redirect are access denied`() = runTest {
        val forbidden = client { html(HttpStatusCode.Forbidden, "cf-ray" to "abc", HttpHeaders.Server to "cloudflare") }
        assertThat(forbidden.health(connection)).isEqualTo(HealthResult.Problem(ServerProblem.AccessDenied))

        val redirect = client {
            html(HttpStatusCode.Found, HttpHeaders.Location to "https://team.cloudflareaccess.com/cdn-cgi/access/login/garage")
        }
        assertThat(redirect.health(connection)).isEqualTo(HealthResult.Problem(ServerProblem.AccessDenied))
    }

    @Test
    fun `other redirects are reported with their target and not followed`() = runTest {
        val result = client { html(HttpStatusCode.MovedPermanently, HttpHeaders.Location to "https://garage.example.com/") }
            .health(connection)

        assertThat(result).isEqualTo(HealthResult.Problem(ServerProblem.Redirected("https://garage.example.com/")))
        assertThat(requests).hasSize(1)
    }

    @Test
    fun `Cloudflare challenge, tunnel errors and foreign servers are told apart`() = runTest {
        assertThat(client { html(HttpStatusCode.Forbidden, "cf-mitigated" to "challenge", "cf-ray" to "x") }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.Challenge))
        assertThat(client { html(HttpStatusCode.fromValue(530), "cf-ray" to "x") }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.GatewayError(530)))
        assertThat(client { html(HttpStatusCode.BadGateway) }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.GatewayError(502)))
        assertThat(client { html(HttpStatusCode.NotFound) }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.NotSmartGarage(404)))
        assertThat(client { json("""{"service":"other"}""") }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.NotSmartGarage(200)))
    }

    @Test
    fun `Smart Garage with another API version or a dead database`() = runTest {
        assertThat(client { json("""{"service":"smart-garage","apiVersion":2,"database":"connected"}""") }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.UnsupportedApi(2)))
        assertThat(
            client {
                json("""{"service":"smart-garage","apiVersion":1,"database":"disconnected"}""", HttpStatusCode.ServiceUnavailable)
            }.health(connection),
        ).isEqualTo(HealthResult.Problem(ServerProblem.DatabaseDown))
    }

    @Test
    fun `network failures are classified`() = runTest {
        assertThat(client { throw UnknownHostException("nope") }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.Unreachable(NetworkFailure.Dns)))
        assertThat(client { throw ConnectException("refused") }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.Unreachable(NetworkFailure.Refused)))
        assertThat(client { throw SSLHandshakeException("bad cert") }.health(connection))
            .isEqualTo(HealthResult.Problem(ServerProblem.Unreachable(NetworkFailure.Tls)))
    }

    @Test
    fun `login maps Smart Garage errors and sends no bearer`() = runTest {
        val api = client {
            json("""{"error":{"code":"rate_limited","message":"Too many"}}""", HttpStatusCode.TooManyRequests, "retry-after" to "600")
        }
        val result = api.login(connection, LoginRequest("connor", "pw", "Pixel"))

        assertThat(result).isEqualTo(ApiResult.HttpError(429, "rate_limited", "Too many", retryAfterSeconds = 600))
        val sent = requests.single()
        assertThat(sent.method.value).isEqualTo("POST")
        assertThat(sent.url.toString()).isEqualTo("https://garage.example.com/api/v1/auth/login")
        assertThat(sent.headers[HttpHeaders.Authorization]).isNull()
    }

    @Test
    fun `authenticated calls send the bearer token and logout accepts 204`() = runTest {
        val api = client { respond("", HttpStatusCode.NoContent) }
        val result = api.logout(connection.copy(token = "sgd_abc"))

        assertThat(result).isEqualTo(ApiResult.Ok(Unit))
        assertThat(requests.single().method.value).isEqualTo("POST")
        assertThat(requests.single().headers[HttpHeaders.Authorization]).isEqualTo("Bearer sgd_abc")
    }

    @Test
    fun `error without JSON from the origin is not mistaken for Smart Garage`() = runTest {
        val result = client { respondError(HttpStatusCode.InternalServerError) }.me(connection.copy(token = "t"))
        assertThat(result).isEqualTo(ApiResult.Failed(ServerProblem.NotSmartGarage(500)))
    }
}

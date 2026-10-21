package io.github.conchad.smartgarage.core.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Cloudflare Access service token, sent on every request when a profile is behind Access. */
data class CfAccessCredentials(val clientId: String, val clientSecret: String) {
    override fun toString() = "CfAccessCredentials(clientId=$clientId, clientSecret=***)"
}

/** Everything needed to talk to one server as one device. */
data class ServerConnection(
    val baseUrl: String,
    val cfAccess: CfAccessCredentials? = null,
    val token: String? = null,
) {
    override fun toString() = "ServerConnection(baseUrl=$baseUrl, cfAccess=${cfAccess != null}, token=${token != null})"
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    /** Smart Garage answered with its JSON error shape. */
    data class HttpError(
        val status: Int,
        val code: String?,
        val message: String?,
        val retryAfterSeconds: Long? = null,
    ) : ApiResult<Nothing>

    /** The request never got a Smart Garage answer. */
    data class Failed(val problem: ServerProblem) : ApiResult<Nothing>
}

sealed interface HealthResult {
    data class Ok(val version: String?, val behindCloudflare: Boolean) : HealthResult
    data class Problem(val problem: ServerProblem) : HealthResult
}

/**
 * Thin wrapper around the shared [HttpClient]: applies a [ServerConnection]'s headers and turns every outcome into
 * an [ApiResult]. Redirects are never followed, so tokens can't leak to another host.
 */
@Singleton
class ApiClient @Inject constructor(@PublishedApi internal val http: HttpClient) {

    suspend fun health(connection: ServerConnection): HealthResult {
        val response = try {
            http.get(connection.url("/api/v1/health")) { applyHeaders(connection) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return HealthResult.Problem(ServerProblem.Unreachable(e.toNetworkFailure()))
        }
        if (!response.isJson()) return HealthResult.Problem(response.classifyForeign())

        val body = try {
            response.body<HealthResponse>()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return HealthResult.Problem(ServerProblem.NotSmartGarage(response.status.value))
        }
        if (body.service != "smart-garage") {
            // Old servers without /api/v1 answer 404 with Next's JSON-less page; anything else is a different app.
            return HealthResult.Problem(ServerProblem.NotSmartGarage(response.status.value))
        }
        if (body.apiVersion != SUPPORTED_API_VERSION) {
            return HealthResult.Problem(ServerProblem.UnsupportedApi(body.apiVersion))
        }
        if (!response.status.isSuccess() || body.database != "connected") {
            return HealthResult.Problem(ServerProblem.DatabaseDown)
        }
        return HealthResult.Ok(version = body.version, behindCloudflare = response.headers["cf-ray"] != null)
    }

    suspend fun login(connection: ServerConnection, request: LoginRequest): ApiResult<LoginResponse> =
        send(connection) {
            method = HttpMethod.Post
            url(connection.url("/api/v1/auth/login"))
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    suspend fun me(connection: ServerConnection): ApiResult<MeResponse> =
        send(connection) { url(connection.url("/api/v1/me")) }

    suspend fun logout(connection: ServerConnection): ApiResult<Unit> =
        send(connection) {
            method = HttpMethod.Post
            url(connection.url("/api/v1/auth/logout"))
        }

    /** One `/sync` page: `since` starts a session, `cursor` continues it. */
    suspend fun sync(connection: ServerConnection, since: String?, cursor: String?): ApiResult<SyncPageDto> =
        send(connection) {
            url(connection.url("/api/v1/sync"))
            if (cursor != null) parameter("cursor", cursor) else if (since != null) parameter("since", since)
        }

    /** Raw bytes of a document or vehicle photo; null when it isn't available (offline, gone, no access). */
    suspend fun documentContent(connection: ServerConnection, documentId: String): ByteArray? = try {
        val response = http.get(connection.url("/api/v1/documents/$documentId/content")) { applyHeaders(connection) }
        if (response.status.isSuccess() && !response.isJson()) response.body<ByteArray>() else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** One row by id, in the same shape as `/sync` delivers it. */
    suspend fun fetch(connection: ServerConnection, path: String): ApiResult<JsonObject> =
        send(connection) { url(connection.url(path)) }

    /**
     * One queued write (D-A16). [idempotencyKey] is sent as `Idempotency-Key`, so a retry after a dropped
     * connection gets the stored answer instead of applying twice (D-033). The value is the written row, or null
     * for a 204.
     */
    suspend fun write(
        connection: ServerConnection,
        method: HttpMethod,
        path: String,
        idempotencyKey: String,
        json: String?,
    ): ApiResult<JsonObject?> = sendWrite(connection) {
        this.method = method
        url(connection.url(path))
        header(IDEMPOTENCY_KEY, idempotencyKey)
        if (json != null) setBody(TextContent(json, ContentType.Application.Json))
    }

    /** A multipart upload: the text [fields] plus the file in the part named `file`. */
    suspend fun writeMultipart(
        connection: ServerConnection,
        path: String,
        idempotencyKey: String,
        fields: Map<String, String>,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<JsonObject?> = sendWrite(connection) {
        method = HttpMethod.Post
        url(connection.url(path))
        header(IDEMPOTENCY_KEY, idempotencyKey)
        setBody(
            MultiPartFormDataContent(
                formData {
                    fields.forEach { (name, value) -> append(name, value) }
                    val partHeaders = Headers.build {
                        append(HttpHeaders.ContentType, mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                    }
                    append("file", bytes, partHeaders)
                },
            ),
        )
    }

    private suspend fun sendWrite(
        connection: ServerConnection,
        configure: HttpRequestBuilder.() -> Unit,
    ): ApiResult<JsonObject?> {
        val response: HttpResponse = try {
            http.request {
                configure()
                applyHeaders(connection)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Failed(ServerProblem.Unreachable(e.toNetworkFailure()))
        }
        if (response.status == HttpStatusCode.NoContent) return ApiResult.Ok(null)
        return readResult(response) { response.body<JsonObject>() }
    }

    suspend inline fun <reified T> send(
        connection: ServerConnection,
        crossinline configure: HttpRequestBuilder.() -> Unit,
    ): ApiResult<T> {
        val response: HttpResponse = try {
            // Not named `build`: HttpRequestBuilder.build() would shadow it and silently send a bare GET.
            http.request {
                configure()
                applyHeaders(connection)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Failed(ServerProblem.Unreachable(e.toNetworkFailure()))
        }
        return readResult(response) { response.body<T>() }
    }

    @PublishedApi
    internal suspend fun <T> readResult(response: HttpResponse, decode: suspend () -> T): ApiResult<T> {
        val status = response.status
        if (status == HttpStatusCode.NoContent) {
            @Suppress("UNCHECKED_CAST")
            return ApiResult.Ok(Unit as T)
        }
        if (!response.isJson()) return ApiResult.Failed(response.classifyForeign())

        return try {
            if (status.isSuccess()) {
                ApiResult.Ok(decode())
            } else {
                val error = response.body<ApiErrorBody>().error
                ApiResult.HttpError(
                    status = status.value,
                    code = error?.code,
                    message = error?.message,
                    retryAfterSeconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ApiResult.Failed(ServerProblem.Unexpected(status.value, null))
        }
    }

    @PublishedApi
    internal fun HttpRequestBuilder.applyHeaders(connection: ServerConnection) {
        connection.cfAccess?.let {
            header("CF-Access-Client-Id", it.clientId)
            header("CF-Access-Client-Secret", it.clientSecret)
        }
        connection.token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
    }
}

fun ServerConnection.url(path: String): String = baseUrl.trimEnd('/') + path

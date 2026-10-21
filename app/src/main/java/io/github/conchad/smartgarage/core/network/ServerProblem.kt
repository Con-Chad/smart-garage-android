package io.github.conchad.smartgarage.core.network

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Why a request didn't reach Smart Garage, or reached something else instead.
 *
 * The brief asks the setup screen to tell "Cloudflare Access rejected you" apart from "server unreachable" and
 * from "Smart Garage login failed". Everything that isn't Smart Garage's own JSON lands here.
 */
sealed interface ServerProblem {
    /** No HTTP response at all. */
    data class Unreachable(val kind: NetworkFailure) : ServerProblem

    /** Cloudflare Access answered: no service token, a wrong one, or a policy that isn't "Service Auth". */
    data object AccessDenied : ServerProblem

    /** Cloudflare served a bot/WAF challenge instead of forwarding the request. */
    data object Challenge : ServerProblem

    /** A proxy or the tunnel answered but couldn't reach Smart Garage behind it (502/503/504, Cloudflare 52x/530). */
    data class GatewayError(val status: Int) : ServerProblem

    /** The server redirects elsewhere, typically http → https or a different path. */
    data class Redirected(val location: String?) : ServerProblem

    /** Something answered, but it isn't Smart Garage's API. */
    data class NotSmartGarage(val status: Int) : ServerProblem

    /** Smart Garage answered, but speaks an API version this app doesn't know. */
    data class UnsupportedApi(val apiVersion: Int?) : ServerProblem

    /** Smart Garage is up but its database isn't. */
    data object DatabaseDown : ServerProblem

    /** Smart Garage returned an error the caller didn't expect. */
    data class Unexpected(val status: Int, val message: String?) : ServerProblem
}

enum class NetworkFailure { Dns, Refused, Timeout, Tls, Other }

@PublishedApi
internal fun Throwable.toNetworkFailure(): NetworkFailure {
    var cause: Throwable? = this
    while (cause != null) {
        when (cause) {
            is UnknownHostException -> return NetworkFailure.Dns
            is ConnectTimeoutException, is SocketTimeoutException, is HttpRequestTimeoutException,
            is java.net.SocketTimeoutException -> return NetworkFailure.Timeout
            is ConnectException, is NoRouteToHostException -> return NetworkFailure.Refused
            is SSLException -> return NetworkFailure.Tls
        }
        cause = cause.cause
    }
    return NetworkFailure.Other
}

internal fun HttpResponse.isJson(): Boolean = contentType()?.match(ContentType.Application.Json) == true

/**
 * Explains a response that isn't Smart Garage's JSON. Cloudflare marks its own responses with `cf-ray` and
 * `server: cloudflare`; Access login redirects point at `*.cloudflareaccess.com` or `/cdn-cgi/access/`.
 */
internal fun HttpResponse.classifyForeign(): ServerProblem {
    val code = status.value
    val fromCloudflare = headers["cf-ray"] != null || headers[HttpHeaders.Server].equals("cloudflare", ignoreCase = true)
    val location = headers[HttpHeaders.Location]

    if (headers["cf-mitigated"].equals("challenge", ignoreCase = true)) return ServerProblem.Challenge
    if (code in 300..399) {
        val toAccess = location != null &&
            ("cloudflareaccess.com" in location || "/cdn-cgi/access/" in location)
        return if (toAccess) ServerProblem.AccessDenied else ServerProblem.Redirected(location)
    }
    if (fromCloudflare && (code == 401 || code == 403)) return ServerProblem.AccessDenied
    if (code in GATEWAY_STATUSES || (fromCloudflare && code in 520..530)) return ServerProblem.GatewayError(code)
    return ServerProblem.NotSmartGarage(code)
}

private val GATEWAY_STATUSES = setOf(502, 503, 504)

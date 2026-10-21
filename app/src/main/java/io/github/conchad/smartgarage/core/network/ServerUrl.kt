package io.github.conchad.smartgarage.core.network

import java.net.URI
import java.net.URISyntaxException

/**
 * Normalizes what the user typed into a base URL (`https://host[:port][/path]`, no trailing slash).
 *
 * Plain http is accepted only for hosts that can't be on the public internet (private/loopback/CGNAT IPs,
 * `localhost`, `.local`, `.lan`, `.home.arpa`, `.internal`, `.ts.net`). The device token and the Cloudflare
 * service token would otherwise travel in clear text (D-A04).
 */
object ServerUrl {
    sealed interface Result {
        data class Valid(val url: String, val host: String, val isHttps: Boolean) : Result
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason { Empty, Malformed, UnsupportedScheme, CleartextPublicHost }

    fun parse(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.Empty)
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"

        val uri = try {
            URI(withScheme)
        } catch (_: URISyntaxException) {
            return Result.Invalid(Reason.Malformed)
        }
        val scheme = uri.scheme?.lowercase() ?: return Result.Invalid(Reason.Malformed)
        if (scheme != "https" && scheme != "http") return Result.Invalid(Reason.UnsupportedScheme)
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return Result.Invalid(Reason.Malformed)
        if (uri.userInfo != null) return Result.Invalid(Reason.Malformed)
        if (scheme == "http" && !isPrivateHost(host)) return Result.Invalid(Reason.CleartextPublicHost)

        val port = if (uri.port == -1) "" else ":${uri.port}"
        // Keep a path prefix for servers behind a reverse proxy sub-path, but drop a pasted API suffix.
        val path = (uri.rawPath ?: "").trimEnd('/').removeSuffix("/api/v1").removeSuffix("/api").trimEnd('/')
        return Result.Valid(url = "$scheme://$host$port$path", host = host, isHttps = scheme == "https")
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost") return true
        if (PRIVATE_SUFFIXES.any { h.endsWith(it) }) return true
        ipv4Octets(h)?.let { (a, b) ->
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127) // CGNAT range, used by Tailscale
        }
        if (':' in h) {
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe8") ||
                h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
        }
        return false
    }

    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".ts.net")

    private fun ipv4Octets(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = parts.map { part -> part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null }
        return octets[0] to octets[1]
    }
}

package io.github.conchad.smartgarage.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

object HttpClientFactory {
    /**
     * No logging plugin on purpose: requests carry the device token and the Cloudflare secret.
     *
     * Redirects are never followed, at both layers: Ktor's `followRedirects = false` and, for the production engine,
     * OkHttp's own `followRedirects`/`followSslRedirects` (Ktor's OkHttp engine already turns them off, but a dependency
     * update must not be able to change that quietly). A `Location` is reported as `ServerProblem.Redirected`, so the
     * bearer token and the Cloudflare headers only ever go to the profile's own host (D-A28).
     */
    fun create(userAgent: String, engine: HttpClientEngine? = null): HttpClient {
        val config: HttpClientConfig<*>.() -> Unit = {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 60_000
            }
            install(ContentNegotiation) { json(ApiJson) }
            install(UserAgent) { agent = userAgent }
        }
        if (engine != null) return HttpClient(engine, config)
        return HttpClient(OkHttp) {
            config()
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                }
            }
        }
    }
}

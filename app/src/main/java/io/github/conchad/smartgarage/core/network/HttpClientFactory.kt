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
    /** No logging plugin on purpose: requests carry the device token and the Cloudflare secret. */
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
        return if (engine != null) HttpClient(engine, config) else HttpClient(OkHttp, config)
    }
}

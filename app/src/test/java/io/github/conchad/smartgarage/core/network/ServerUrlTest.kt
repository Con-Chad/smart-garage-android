package io.github.conchad.smartgarage.core.network

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.network.ServerUrl.Reason
import io.github.conchad.smartgarage.core.network.ServerUrl.Result
import org.junit.Test

class ServerUrlTest {

    private fun valid(input: String) = ServerUrl.parse(input) as Result.Valid
    private fun reason(input: String) = (ServerUrl.parse(input) as Result.Invalid).reason

    @Test
    fun `adds https when no scheme is given`() {
        assertThat(valid("garage.example.com").url).isEqualTo("https://garage.example.com")
    }

    @Test
    fun `trims whitespace, trailing slashes and a pasted api suffix`() {
        assertThat(valid("  https://Garage.Example.com/  ").url).isEqualTo("https://garage.example.com")
        assertThat(valid("https://garage.example.com/api/v1/").url).isEqualTo("https://garage.example.com")
    }

    @Test
    fun `keeps port and reverse-proxy path`() {
        assertThat(valid("http://192.168.50.129:3000").url).isEqualTo("http://192.168.50.129:3000")
        assertThat(valid("https://example.com/garage/").url).isEqualTo("https://example.com/garage")
    }

    @Test
    fun `allows plain http only for private hosts`() {
        listOf(
            "http://192.168.1.10:3000",
            "http://10.0.0.5",
            "http://172.20.1.1",
            "http://100.101.102.103", // Tailscale
            "http://localhost:3000",
            "http://lysandor.local",
            "http://garage.tail1234.ts.net",
            "http://[fd00::1]:3000",
        ).forEach { assertThat(ServerUrl.parse(it)).isInstanceOf(Result.Valid::class.java) }

        assertThat(reason("http://garage.example.com")).isEqualTo(Reason.CleartextPublicHost)
        assertThat(reason("http://172.32.0.1")).isEqualTo(Reason.CleartextPublicHost)
        assertThat(reason("http://8.8.8.8")).isEqualTo(Reason.CleartextPublicHost)
    }

    @Test
    fun `rejects junk`() {
        assertThat(reason("")).isEqualTo(Reason.Empty)
        assertThat(reason("ftp://example.com")).isEqualTo(Reason.UnsupportedScheme)
        assertThat(reason("https://")).isEqualTo(Reason.Malformed)
        assertThat(reason("https://user:pw@example.com")).isEqualTo(Reason.Malformed)
        assertThat(reason("not a url")).isEqualTo(Reason.Malformed)
    }
}

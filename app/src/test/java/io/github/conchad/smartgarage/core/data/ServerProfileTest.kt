package io.github.conchad.smartgarage.core.data

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.network.ApiJson
import org.junit.Test

class ServerProfileTest {

    private fun profile(url: String, optIn: Boolean = false) =
        ServerProfile(id = "p1", name = "Garage", baseUrl = url, backgroundSyncOnCleartext = optIn)

    @Test
    fun `https profiles always sync in the background`() {
        assertThat(profile("https://garage.example.com").isCleartext).isFalse()
        assertThat(profile("https://garage.example.com").syncsInBackground).isTrue()
    }

    @Test
    fun `http profiles sync in the background only after opting in`() {
        assertThat(profile("http://192.168.1.10:3000").isCleartext).isTrue()
        assertThat(profile("http://192.168.1.10:3000").syncsInBackground).isFalse()
        assertThat(profile("HTTP://garage.lan").syncsInBackground).isFalse()
        assertThat(profile("http://192.168.1.10:3000", optIn = true).syncsInBackground).isTrue()
    }

    @Test
    fun `stored profiles without the flag still parse and default to off`() {
        val stored = """{"id":"p1","name":"Garage","baseUrl":"http://garage.lan"}"""
        val parsed = ApiJson.decodeFromString(ServerProfile.serializer(), stored)
        assertThat(parsed.backgroundSyncOnCleartext).isFalse()
        assertThat(parsed.syncsInBackground).isFalse()
    }
}

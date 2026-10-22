package io.github.conchad.smartgarage.core.network

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerIdsTest {

    @Test
    fun `accepts cuid, uuid-like and temp ids`() {
        listOf(
            "clx3k9z0a0001qzrmn831i7rn",
            "0e3a6d0c-8f13-4a35-9f1e-6b7f1d8e0c2a",
            "tmp-0e3a6d0c-8f13-4a35-9f1e-6b7f1d8e0c2a",
            "a",
            "A_b-9",
            "x".repeat(64),
        ).forEach { assertWithMessage(it).that(ServerIds.isValid(it)).isTrue() }
    }

    @Test
    fun `rejects path escapes, separators, empty, overlong and unicode`() {
        listOf(
            "../x",
            "..",
            ".",
            "/abs",
            "a/b",
            "a\\b",
            "",
            "x".repeat(65),
            "ünicode",
            "日本",
            "a b",
            "a.part",
            "a%2F..%2Fb",
            "a\u0000b",
            "a\nb",
        ).forEach { assertWithMessage(it).that(ServerIds.isValid(it)).isFalse() }
    }

    @Test
    fun `require returns the id or throws the typed error without echoing the id`() {
        assertThat(ServerIds.require("clx3k9z0a0001qzrmn831i7rn")).isEqualTo("clx3k9z0a0001qzrmn831i7rn")
        val error = assertThrows(InvalidServerIdException::class.java) { ServerIds.require("../../databases/cache.db") }
        assertThat(error.message).doesNotContain("databases")
    }
}

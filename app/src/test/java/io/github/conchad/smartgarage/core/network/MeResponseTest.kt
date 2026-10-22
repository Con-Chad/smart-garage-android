package io.github.conchad.smartgarage.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `/me.features` is optional (D-A27): servers from before the receipt scanner don't send it. */
class MeResponseTest {

    private val user = """"user":{"id":"u1","username":"connor","displayName":"Connor","role":"ADMIN"}"""

    @Test
    fun `me without features parses with every flag off`() {
        val me = ApiJson.decodeFromString(MeResponse.serializer(), """{$user,"server":{"version":"1.4.0","apiVersion":1}}""")

        assertThat(me.features).isEqualTo(MeFeatures())
        assertThat(me.features.receiptScanner).isFalse()
    }

    @Test
    fun `me with receiptScanner true parses true`() {
        val me = ApiJson.decodeFromString(MeResponse.serializer(), """{$user,"features":{"receiptScanner":true}}""")

        assertThat(me.features.receiptScanner).isTrue()
    }

    @Test
    fun `unknown feature flags are ignored`() {
        val me = ApiJson.decodeFromString(MeResponse.serializer(), """{$user,"features":{"receiptScanner":false,"somethingNew":true}}""")

        assertThat(me.features.receiptScanner).isFalse()
    }
}

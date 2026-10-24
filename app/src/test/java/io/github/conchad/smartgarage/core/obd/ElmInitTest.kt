package io.github.conchad.smartgarage.core.obd

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ElmInitTest {
    @Test
    fun `ELM-style identities pass, garbage does not`() {
        listOf("ELM327 v1.5", "ELM327 v2.3", "STN2255 v5.10.3", "OBDLink CX r1.2", "WiCAN", "OBDII to RS232 Interpreter", "v2.1")
            .forEach { assertWithMessage(it).that(ElmInit.looksLikeElm(it)).isTrue() }
        listOf("", "?", "OK", "hello", "\u0001\u0002ELM327", "AT+GMR")
            .forEach { assertWithMessage(it).that(ElmInit.looksLikeElm(it)).isFalse() }
    }

    @Test
    fun `profile commands are split on semicolons and lose ones that would undo the setup`() {
        val ford = OdometerProfiles.custom().copy(
            initCommands = "ATE0;ATL0;ATH0;ATR1;ATST32;ATSP6;ATCAF1;",
            headerCommand = "ATSH000726;STCAFCP726,72E;",
        )
        assertThat(ElmInit.profileCommands(ford))
            .containsExactly("ATE0", "ATL0", "ATR1", "ATST32", "ATSP6", "ATCAF1", "ATSH000726", "STCAFCP726,72E").inOrder()
        assertThat(ElmInit.profileCommands(OdometerProfiles.HONDA_CLUSTER)).containsExactly("ATSP7", "ATST96", "ATSHDA60F1").inOrder()
        assertThat(ElmInit.protocolOf(listOf("ATSP6", "ATST96", "AT SP 7"))).isEqualTo(7)
        assertThat(ElmInit.protocolOf(listOf("ATSP0"))).isNull()
        assertThat(ElmInit.protocolOf(listOf("ATSPA6"))).isNull()
        assertThat(ElmInit.protocolOf(listOf("ATST96"))).isNull()
    }

    @Test
    fun `init runs reset, identity, base commands, then the profile, and reports the CAN ID width`() = runTest {
        val link = ScriptedElmLink(backgroundScope) { c, _ ->
            when (c) {
                "ATZ" -> "ATZ\r\r\rELM327 v2.3\r\r>"
                "ATI" -> "ELM327 v2.3\r\r>"
                "ATAT1" -> "?\r\r>"
                else -> "OK\r\r>"
            }
        }
        val adapter = ElmInit.run(ElmSession(link, backgroundScope), OdometerProfiles.HONDA_CLUSTER)
        assertThat(link.events.filter { it.startsWith("write") }.map { it.removePrefix("write ") })
            .containsExactly("ATZ", "ATI", "ATE0", "ATL0", "ATS0", "ATH1", "ATAT1", "ATSP7", "ATST96", "ATSHDA60F1").inOrder()
        assertThat(adapter.identity).isEqualTo("ELM327 v2.3")
        assertThat(adapter.format).isEqualTo(ElmFormat(headers = true, canIdChars = 8))
        assertThat(adapter.rejected).containsExactly("ATAT1")
    }

    @Test
    fun `something that isn't an ELM is refused`() = runTest {
        val link = ScriptedElmLink(backgroundScope) { _, _ -> "AT+OK\r\r>" }
        val e = runCatching { ElmInit.run(ElmSession(link, backgroundScope), OdometerProfiles.DEFAULT) }.exceptionOrNull()
        assertThat((e as ElmInitException).reason).isEqualTo(ElmInitException.Reason.NOT_ELM)

        val silent = ScriptedElmLink(backgroundScope) { _, _ -> null }
        val e2 = runCatching { ElmInit.run(ElmSession(silent, backgroundScope), OdometerProfiles.DEFAULT) }.exceptionOrNull()
        assertThat((e2 as ElmInitException).reason).isEqualTo(ElmInitException.Reason.NO_REPLY)
    }
}

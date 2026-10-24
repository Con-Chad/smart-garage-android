package io.github.conchad.smartgarage.core.obd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ElmReplyTest {
    private fun status(raw: String, command: String = "0100") = ElmReply.of(command, raw).status

    @Test
    fun `echo, blank lines, prompt, NULs and SEARCHING are stripped`() {
        val reply = ElmReply.of("01 A6", "01A6\r\u0000SEARCHING...\r\r7E8 06 41 A6 00 0C D6 59\r\n\r\n>")
        assertThat(reply.lines).containsExactly("7E8 06 41 A6 00 0C D6 59")
        assertThat(ElmReply.of("0131", "SEARCHING...7E8 04 41 31 04 D2\r\r>").lines).containsExactly("7E8 04 41 31 04 D2")
        assertThat(ElmReply.of("ATZ", "ATZ\r\r\rELM327 v1.5\r\r>").lines).containsExactly("ELM327 v1.5")
        assertThat(ElmReply.of("0100", "BUS INIT: ...OK\r41 00 BE 3F A8 13\r\r>").lines).containsExactly("41 00 BE 3F A8 13")
    }

    @Test
    fun `replies are classified`() {
        assertThat(status("OK\r\r>", "ATE0")).isEqualTo(ElmStatus.OK)
        assertThat(status("41 00 BE 3F A8 13\r\r>")).isEqualTo(ElmStatus.DATA)
        assertThat(status("00F\r0:627022002A10\r1:03000001489A01\r2:0055\r\r>")).isEqualTo(ElmStatus.DATA)
        assertThat(status("18DAF160037F2231\r\r>")).isEqualTo(ElmStatus.DATA)
        assertThat(status("ELM327 v1.5\r\r>", "ATI")).isEqualTo(ElmStatus.TEXT)
        assertThat(status("12.6V\r\r>", "ATRV")).isEqualTo(ElmStatus.TEXT)
        assertThat(status("\r\r>")).isEqualTo(ElmStatus.EMPTY)
        assertThat(status("NO DATA\r\r>")).isEqualTo(ElmStatus.NO_DATA)
        assertThat(status("?\r\r>", "ATXYZ")).isEqualTo(ElmStatus.UNKNOWN_COMMAND)
        assertThat(status("CAN ERROR\r\r>")).isEqualTo(ElmStatus.CAN_ERROR)
        assertThat(status("7E8 06 41\rSTOPPED\r\r>")).isEqualTo(ElmStatus.STOPPED)
        assertThat(status("BUFFER FULL\r\r>")).isEqualTo(ElmStatus.BUFFER_FULL)
        assertThat(status("SEARCHING...\rUNABLE TO CONNECT\r\r>")).isEqualTo(ElmStatus.UNABLE_TO_CONNECT)
        assertThat(status("BUS INIT: ...ERROR\r\r>")).isEqualTo(ElmStatus.BUS_INIT_ERROR)
        assertThat(status("BUS ERROR\r\r>")).isEqualTo(ElmStatus.BUS_ERROR)
        assertThat(status("7E8 06 41 00 <DATA ERROR\r\r>")).isEqualTo(ElmStatus.BUS_ERROR)
        assertThat(status("ERR94\r\r>")).isEqualTo(ElmStatus.BUS_ERROR)
        assertThat(status("LV RESET\r\r>")).isEqualTo(ElmStatus.BUS_ERROR)
    }

    @Test
    fun `only timeouts and transient bus errors are retryable`() {
        assertThat(ElmStatus.entries.filter { it.retryable })
            .containsExactly(ElmStatus.CAN_ERROR, ElmStatus.STOPPED, ElmStatus.BUFFER_FULL, ElmStatus.TIMEOUT)
    }
}

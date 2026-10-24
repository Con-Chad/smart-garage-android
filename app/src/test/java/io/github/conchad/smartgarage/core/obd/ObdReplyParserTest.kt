package io.github.conchad.smartgarage.core.obd

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ObdReplyParserTest {
    private val honda = ObdRequest.parse("2270229")
    private val a6 = ObdRequest.parse("01A6")
    private val clarity = ByteExpression.parse("[B12:B14]")

    // 62 70 22 + 12 data bytes; the odometer 01 48 A2 = 84 130 km is data bytes 6..8.
    private val hondaData = listOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x01, 0x48, 0xA2, 0x77, 0x88, 0x99)

    /** What WiCAN builds from the ATH1 frames: every byte after the CAN ID, PCI bytes included. */
    private val hondaWican = listOf(0x10, 0x0F, 0x62, 0x70, 0x22, 0x11, 0x22, 0x33) +
        listOf(0x21, 0x44, 0x55, 0x66, 0x01, 0x48, 0xA2, 0x77) +
        listOf(0x22, 0x88, 0x99, 0x00, 0x00, 0x00, 0x00, 0x00)

    private fun positive(result: ObdParse): ObdResponse {
        assertThat(result).isInstanceOf(ObdParse.Positive::class.java)
        return (result as ObdParse.Positive).response
    }

    @Test
    fun `request keeps ELM's response-count suffix out of the bytes`() {
        assertThat(honda.bytes).containsExactly(0x22, 0x70, 0x22).inOrder()
        assertThat(honda.responseCount).isEqualTo(9)
        assertThat(honda.text).isEqualTo("2270229")
        assertThat(honda.positiveService).isEqualTo(0x62)
        assertThat(ObdRequest.parse("01 A6").echo).containsExactly(0xA6)
        assertThat(ObdRequest.parse("01A6").responseCount).isNull()
        assertThat(ObdRequest.parseOrNull("ATZ")).isNull()
    }

    @Test
    fun `ATH1 29-bit without spaces keeps WiCAN numbering`() {
        val lines = listOf("18DAF160100F627022112233", "18DAF160214455660148A277", "18DAF1602288990000000000")
        val r = positive(ObdReplyParser.parse(lines, honda, ElmFormat(canIdChars = 8)))
        assertThat(r.ecu).isEqualTo("18DAF160")
        assertThat(r.bytes).isEqualTo(hondaWican)
        assertThat(r.payload).isEqualTo(hondaData)
        assertThat(clarity.evaluate(r.bytes)).isEqualTo(84_130.0)
        // Without the protocol hint the even line length still says 29-bit.
        assertThat(positive(ObdReplyParser.parse(lines, honda)).bytes).isEqualTo(hondaWican)
    }

    @Test
    fun `ATH1 spaced, as four ID tokens or one`() {
        val fourTokens = listOf(
            "18 DA F1 60 10 0F 62 70 22 11 22 33",
            "18 DA F1 60 21 44 55 66 01 48 A2 77",
            "18 DA F1 60 22 88 99 00 00 00 00 00",
        )
        val oneToken = fourTokens.map { it.replaceFirst("18 DA F1 60", "18DAF160") }
        for (lines in listOf(fourTokens, oneToken)) {
            val r = positive(ObdReplyParser.parse(lines, honda, ElmFormat(canIdChars = 8)))
            assertThat(r.ecu).isEqualTo("18DAF160")
            assertThat(r.bytes).isEqualTo(hondaWican)
        }
    }

    @Test
    fun `ATH0 numbered lines rebuild the same numbering`() {
        val spaced = listOf("00F", "0: 62 70 22 11 22 33", "1: 44 55 66 01 48 A2 77", "2: 88 99")
        val compact = listOf("00F", "0:627022112233", "1:4455660148A277", "2:8899")
        for (lines in listOf(spaced, compact)) {
            val r = positive(ObdReplyParser.parse(lines, honda, ElmFormat(headers = false)))
            assertThat(r.ecu).isNull()
            // ATH0 prints no padding, so the rebuilt array is the ATH1 one without the trailing 00s.
            assertThat(r.bytes).isEqualTo(hondaWican.take(19))
            assertThat(r.payload).isEqualTo(hondaData)
            assertThat(clarity.evaluate(r.bytes)).isEqualTo(84_130.0)
        }
        // Numbered lines are recognised even when headers were expected.
        assertThat(positive(ObdReplyParser.parse(spaced, honda)).bytes).isEqualTo(hondaWican.take(19))
    }

    @Test
    fun `single frame in every format gives B0 = length`() {
        val expected = listOf(0x06, 0x41, 0xA6, 0x00, 0x0C, 0xD6, 0x59)
        val standard = ByteExpression.parse("[B3:B6]/10")
        val cases = listOf(
            listOf("7E80641A6000CD659") to ElmFormat(canIdChars = 3),
            listOf("7E80641A6000CD659") to ElmFormat(),
            listOf("7E8 06 41 A6 00 0C D6 59") to ElmFormat(),
            listOf("18DAF1100641A6000CD659") to ElmFormat(canIdChars = 8),
            listOf("41 A6 00 0C D6 59") to ElmFormat(headers = false),
            listOf("41A6000CD659") to ElmFormat(headers = false),
        )
        for ((lines, format) in cases) {
            val r = positive(ObdReplyParser.parse(lines, a6, format))
            assertThat(r.bytes).isEqualTo(expected)
            assertThat(r.payload).containsExactly(0x00, 0x0C, 0xD6, 0x59).inOrder()
            assertThat(standard.evaluate(r.bytes)).isWithin(1e-9).of(84_130.5)
        }
    }

    @Test
    fun `an adapter that ignored ATH1 is still read`() {
        val r = positive(ObdReplyParser.parse(listOf("41A6000CD659"), a6, ElmFormat()))
        assertThat(r.ecu).isNull()
        assertThat(r.bytes.first()).isEqualTo(0x06)
    }

    @Test
    fun `SEARCHING prefix from the raw reply is ignored`() {
        val r = positive(ObdReplyParser.parse("01A6\rSEARCHING...\r7E8 06 41 A6 00 0C D6 59\r\r>", a6))
        assertThat(r.ecu).isEqualTo("7E8")
    }

    @Test
    fun `several ECUs - the one that answers the request wins, then the lowest header`() {
        val both = listOf("7E9 06 41 A6 00 00 00 10", "7E8 06 41 A6 00 0C D6 59")
        val r = positive(ObdReplyParser.parse(both, a6))
        assertThat(r.ecu).isEqualTo("7E8")
        assertThat(r.ecus).containsExactly("7E8", "7E9").inOrder()

        // The lowest header refuses; the cluster on a higher one answers, frames interleaved.
        val mixed = listOf(
            "18DAF110037F2231",
            "18DAF160100F627022112233",
            "18DAF11903410D32",
            "18DAF160214455660148A277",
            "18DAF1602288990000000000",
        )
        val m = positive(ObdReplyParser.parse(mixed, honda, ElmFormat(canIdChars = 8)))
        assertThat(m.ecu).isEqualTo("18DAF160")
        assertThat(m.bytes).isEqualTo(hondaWican)
    }

    @Test
    fun `negative responses are recognised, response pending is skipped when an answer follows`() {
        val refused = ObdReplyParser.parse(listOf("18DAF160037F2231"), honda, ElmFormat(canIdChars = 8))
        assertThat(refused).isEqualTo(ObdParse.Negative("18DAF160", 0x22, 0x31))
        assertThat((refused as ObdParse.Negative).codeName).isEqualTo("requestOutOfRange")
        assertThat(refused.pending).isFalse()

        val pendingOnly = ObdReplyParser.parse(listOf("18DAF160037F2278"), honda, ElmFormat(canIdChars = 8))
        assertThat((pendingOnly as ObdParse.Negative).pending).isTrue()

        val pendingThenAnswer = listOf(
            "18DAF160037F2278",
            "18DAF160100F627022112233",
            "18DAF160214455660148A277",
            "18DAF1602288990000000000",
        )
        val r = positive(ObdReplyParser.parse(pendingThenAnswer, honda, ElmFormat(canIdChars = 8)))
        assertThat(r.bytes).isEqualTo(hondaWican)
        assertThat(clarity.evaluate(r.bytes)).isEqualTo(84_130.0)

        // Without headers too.
        assertThat(ObdReplyParser.parse(listOf("7F 22 31"), honda, ElmFormat(headers = false)))
            .isEqualTo(ObdParse.Negative(null, 0x22, 0x31))
    }

    @Test
    fun `data for another request is unmatched, missing frames are invalid`() {
        val other = ObdReplyParser.parse(listOf("7E8 03 41 0D 32"), a6)
        assertThat(other).isInstanceOf(ObdParse.Unmatched::class.java)
        assertThat((other as ObdParse.Unmatched).response.bytes).containsExactly(0x03, 0x41, 0x0D, 0x32).inOrder()

        val truncated = ObdReplyParser.parse(listOf("18DAF160100F627022112233", "18DAF160214455660148A277"), honda, ElmFormat(canIdChars = 8))
        assertThat(truncated).isInstanceOf(ObdParse.Invalid::class.java)
        assertThat(ObdReplyParser.parse(listOf("NO DATA"), a6)).isInstanceOf(ObdParse.Invalid::class.java)
    }

    @Test
    fun `ATDPN and the protocol give the CAN ID width`() {
        assertThat(ElmFormat.parseDpn("A6")).isEqualTo(6)
        assertThat(ElmFormat.parseDpn("7")).isEqualTo(7)
        assertThat(ElmFormat.parseDpn("?")).isNull()
        assertThat(ElmFormat.canIdChars(6)).isEqualTo(3)
        assertThat(ElmFormat.canIdChars(8)).isEqualTo(3)
        assertThat(ElmFormat.canIdChars(7)).isEqualTo(8)
        assertThat(ElmFormat.canIdChars(9)).isEqualTo(8)
        assertThat(ElmFormat.canIdChars(0)).isNull()
    }
}

package io.github.conchad.smartgarage.core.obd

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

class ByteExpressionTest {
    // The sample array from wican-fw's expression_parser.c test table.
    private val data = listOf(0xFF, 0x00, 0xF0, 0x0F, 0xAA, 0x55, 0x33, 0x77, 0x88, 0xCC, 0x99, 0xEE, 0x44, 0x85, 0x06, 0x00, 0xEF)

    private fun eval(expression: String, v: Double = 0.0) = ByteExpression.parse(expression).evaluate(data, v)

    private fun error(expression: String, bytes: List<Int> = data): String =
        assertThrows(ByteExpressionException::class.java) { ByteExpression.parse(expression).evaluate(bytes) }.message!!

    @Test
    fun `WiCAN's own test cases give WiCAN's results`() {
        val cases = listOf(
            "3 + 5" to 8.0,
            "10 - 2 * 3" to 4.0,
            "(3 + 5) * 2" to 16.0,
            "10 / 2" to 5.0,
            "(2 + 3) * (7 - 2)" to 25.0,
            "((5 + 5) / 2) * 3 - 1" to 14.0,
            "3 & 1" to 1.0,
            "3 | 4" to 7.0,
            "5 ^ 2" to 7.0,
            "2 << 1" to 4.0,
            "8 >> 2" to 2.0,
            "B2 << 4" to 3840.0,
            "B3 >> 1" to 7.0,
            "B0" to 255.0,
            "B5" to 85.0,
            "B6 + B7" to (0x33 + 0x77).toDouble(),
            "B8 - B9" to (0x88 - 0xCC).toDouble(),
            "B5 * B12" to 85.0 * 68.0,
            "B4:1" to 1.0,
            "B4:7" to 1.0,
            "[B1:B3]" to 0x00F00F.toDouble(),
            "[B5:B6]" to 0x5533.toDouble(),
            "[B0:B4]" to 0xFF00F00FAAL.toDouble(),
            "(B1 + B2) * (B3 - B4) / 2" to ((0x00 + 0xF0) * (0x0F - 0xAA)) / 2.0,
            "B10 + B11 * B12" to (0x99 + 0xEE * 0x44).toDouble(),
            "[B3:B6] >> 8" to (0x0FAA5533 shr 8).toDouble(),
            "((B0 & B1) | B2) ^ B3" to (((0xFF and 0x00) or 0xF0) xor 0x0F).toDouble(),
            "(B4 + B5) << (B6 & 3)" to ((0xAA + 0x55) shl (0x33 and 3)).toDouble(),
            "S0" to -1.0,
            "S2" to -16.0,
            "[S1:S3]" to 0x00F00F.toDouble(),
            "S4 + S5" to (0xAA.toByte() + 0x55.toByte()).toDouble(),
            "S11 - S14" to (0xEE.toByte() - 0x06.toByte()).toDouble(),
            "[S12:S15]" to 0x44850600.toDouble(),
            "S16" to 0xEF.toByte().toDouble(),
        )
        for ((expression, expected) in cases) {
            assertWithMessage(expression).that(eval(expression)).isWithin(1e-9).of(expected)
        }
        assertThat(eval("B0 / B2")).isWithin(1e-9).of(255.0 / 240.0)
        assertThat(eval("V + 2", v = 3.3)).isWithin(1e-9).of(5.3)
        assertThat(eval("(3 + V) * (B0 & 15) / 2", v = 3.3)).isWithin(1e-9).of((3.3 + 3) * 15 / 2)
    }

    @Test
    fun `precedence low to high is or-xor, and, shifts, add, multiply, all left to right`() {
        assertThat(eval("3 | 4 & 1")).isEqualTo(3.0)
        assertThat(eval("1 << 2 + 1")).isEqualTo(8.0)
        assertThat(eval("2 ^ 3 | 4")).isEqualTo(5.0)
        assertThat(eval("6 & 3 << 1")).isEqualTo(6.0)
        assertThat(eval("8 - 2 - 1")).isEqualTo(5.0)
        assertThat(eval("16 / 4 / 2")).isEqualTo(2.0)
        assertThat(eval("1 + 2 * 3 - 4 / 2")).isEqualTo(5.0)
    }

    @Test
    fun `ranges are big-endian and signed ranges are sized by span`() {
        val bytes = listOf(0x10, 0x0F, 0x62, 0x70, 0x22, 0x11, 0x22, 0x33, 0x21, 0x44, 0x55, 0x66, 0x01, 0x48, 0xA2)
        assertThat(ByteExpression.parse("[B12:B14]").evaluate(bytes)).isEqualTo(84_130.0)
        assertThat(ByteExpression.parse("[B 12 : B 14]").evaluate(bytes)).isEqualTo(84_130.0)
        assertThat(eval("[S0:S1]")).isEqualTo(-256.0)
        assertThat(eval("[S2:S2]")).isEqualTo(-16.0)
        // A 3-byte signed span is an int32 of 24 bits: never negative, as in WiCAN.
        assertThat(eval("[S0:S2]")).isEqualTo(0xFF00F0.toDouble())
        assertThat(eval("[S0:S3]")).isEqualTo(0xFF00F00F.toInt().toDouble())
        assertThat(eval("[B0:B7]")).isEqualTo(0xFF00F00FAA553377uL.toDouble())
        assertThat(eval("[S0:S7]")).isEqualTo(0xFF00F00FAA553377uL.toLong().toDouble())
    }

    @Test
    fun `bits, hex literals and unary minus`() {
        assertThat(eval("B4:0")).isEqualTo(0.0)
        assertThat(eval("B3:3 + B3:4")).isEqualTo(1.0)
        assertThat(eval("B0 & 0x0F")).isEqualTo(15.0)
        assertThat(eval("0X10 * 2")).isEqualTo(32.0)
        assertThat(eval("-B1 - 5")).isEqualTo(-5.0)
        assertThat(eval("2 * -3")).isEqualTo(-6.0)
        assertThat(eval("-(1 + 2) * 2")).isEqualTo(-6.0)
        assertThat(eval("+4 - -4")).isEqualTo(8.0)
        assertThat(eval(".5 + 1.25")).isEqualTo(1.75)
        assertThat(eval("(([B10:B12]>>4)*0.75)-79323.25")).isWithin(1e-6).of(((0x99EE44 shr 4) * 0.75) - 79323.25)
    }

    @Test
    fun `errors name the problem`() {
        assertThat(error("B5", listOf(1, 2, 3))).contains("B5 reads B5 but the reply has 3 bytes (B0–B2)")
        assertThat(error("[B12:B14]", List(13) { 0 })).contains("[B12:B14] reads B13 but the reply has 13 bytes")
        assertThat(error("B0", emptyList())).contains("the reply is empty")
        assertThat(error("10 / 0")).contains("division by zero")
        assertThat(error("B5 / (B1 * 2)")).contains("division by zero")
        assertThat(error("[B0:B8]")).contains("longer than 8 bytes")
        assertThat(error("[B3:B1]")).contains("backwards")
        assertThat(error("[B1:S3]")).contains("B or S on both ends")
        assertThat(error("[B1]")).contains("expected [Bn:Bm]")
        assertThat(error("(1 + 2")).contains("without a matching ')'")
        assertThat(error("1 + 2)")).contains("without a matching '('")
        assertThat(error("1 +")).contains("ends with an operator")
        assertThat(error("B1:")).contains("bit digit")
        assertThat(error("B")).contains("needs a byte number")
        assertThat(error("1 < 2")).contains("unexpected '<'")
        assertThat(error("B1 B2")).contains("missing operator")
        assertThat(error("* 2")).contains("missing operand")
        assertThat(error("x")).contains("unexpected 'x' at position 1")
        assertThat(error("0x")).contains("hex digits")
        assertThat(error("1.2.3")).contains("bad number")
        assertThat(error("  ")).contains("empty")
    }

    @Test
    fun `maxIndex is the highest byte read`() {
        assertThat(ByteExpression.parse("[B12:B14]").maxIndex).isEqualTo(14)
        assertThat(ByteExpression.parse("B3:1 + S20 * [B4:B5]").maxIndex).isEqualTo(20)
        assertThat(ByteExpression.parse("V * 2").maxIndex).isEqualTo(-1)
        assertThat(ByteExpression.parseOrNull("B1 +")).isNull()
    }
}

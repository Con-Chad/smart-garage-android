package io.github.conchad.smartgarage.core.obd

class ByteExpressionException(message: String) : IllegalArgumentException(message)

/**
 * WiCAN's byte expression language (wican-fw `main/expression_parser.c`), so an expression pasted from a WiCAN vehicle
 * profile gives the same number here. Bytes are numbered the WiCAN way, see [ObdResponse.bytes].
 *
 * Exactly as WiCAN:
 * - numbers with "." (`0.75`), `V` (always 0 here, as WiCAN passes it for PIDs);
 * - `Bn` an unsigned byte, `Bn:b` bit b (one digit) of it, `Sn` the byte as int8;
 * - `[Bn:Bm]` bytes n..m big-endian unsigned, `[Sn:Sm]` the same cast to int8/int16/int32/int64 by span (1, 2, 3–4,
 *   5–8 bytes; a 3-byte span is NOT sign-extended, as in WiCAN); at most 8 bytes;
 * - `( ) + - * / & | ^ << >>`, all left-associative, precedence low to high: `| ^`, `&`, `<< >>`, `+ -`, `* /`;
 * - bitwise and shift operands are cast to a 32-bit int (wrapping; WiCAN's C cast is undefined out of range), and
 *   a shift count is taken mod 32 (undefined in C);
 * - division by zero is an error.
 *
 * Extensions (WiCAN rejects these, so they never change a WiCAN expression's value): hex literals (`0xFF`, which
 * WiCAN's docs show but its parser refuses), unary minus/plus, whitespace inside brackets, and errors that name the
 * problem: an index past the reply, a bit digit missing, a reversed range.
 */
class ByteExpression private constructor(val text: String, private val root: Node) {
    /** The highest byte index the expression reads, or -1 when it reads none. */
    val maxIndex: Int = root.maxIndex()

    /** Evaluates against a reply's WiCAN-numbered [bytes] (0–255 each). */
    fun evaluate(bytes: List<Int>, v: Double = 0.0): Double = root.eval(bytes, v)

    override fun toString() = text

    private sealed interface Node {
        fun eval(b: List<Int>, v: Double): Double
        fun maxIndex(): Int = -1
    }

    private class Num(val value: Double) : Node {
        override fun eval(b: List<Int>, v: Double) = value
    }

    private data object Var : Node {
        override fun eval(b: List<Int>, v: Double) = v
    }

    private class ByteAt(val index: Int, val bit: Int?, val signed: Boolean) : Node {
        override fun eval(b: List<Int>, v: Double): Double {
            val value = b.at(index, if (signed) "S$index" else "B$index")
            return when {
                signed -> value.toByte().toDouble()
                bit != null -> ((value shr bit) and 1).toDouble()
                else -> value.toDouble()
            }
        }
        override fun maxIndex() = index
    }

    private class Range(val start: Int, val end: Int, val signed: Boolean) : Node {
        override fun eval(b: List<Int>, v: Double): Double {
            val letter = if (signed) "S" else "B"
            var sum = 0L
            for (j in start..end) sum = sum or (b.at(j, "[$letter$start:$letter$end]").toLong() shl ((end - j) * 8))
            if (!signed) return sum.toULong().toDouble()
            return when (end - start) {
                0 -> sum.toByte().toDouble()
                1 -> sum.toShort().toDouble()
                2, 3 -> sum.toInt().toDouble()
                else -> sum.toDouble()
            }
        }
        override fun maxIndex() = end
    }

    private class Negate(val operand: Node) : Node {
        override fun eval(b: List<Int>, v: Double) = -operand.eval(b, v)
        override fun maxIndex() = operand.maxIndex()
    }

    private class Binary(val op: Char, val left: Node, val right: Node) : Node {
        override fun eval(b: List<Int>, v: Double): Double {
            val l = left.eval(b, v)
            val r = right.eval(b, v)
            return when (op) {
                '+' -> l + r
                '-' -> l - r
                '*' -> l * r
                '/' -> if (r == 0.0) throw ByteExpressionException("division by zero") else l / r
                '&' -> (l.cInt() and r.cInt()).toDouble()
                '|' -> (l.cInt() or r.cInt()).toDouble()
                '^' -> (l.cInt() xor r.cInt()).toDouble()
                '<' -> (l.cInt() shl r.cInt()).toDouble()
                '>' -> (l.cInt() shr r.cInt()).toDouble()
                else -> error("operator $op")
            }
        }
        override fun maxIndex() = maxOf(left.maxIndex(), right.maxIndex())
    }

    companion object {
        fun parse(text: String): ByteExpression = ByteExpression(text, Parser(text).parse())

        fun parseOrNull(text: String): ByteExpression? = runCatching { parse(text) }.getOrNull()

        private fun Double.cInt(): Int = toLong().toInt()

        private fun List<Int>.at(index: Int, what: String): Int {
            if (index >= size) {
                val have = if (isEmpty()) "the reply is empty" else "the reply has $size bytes (B0–B${size - 1})"
                throw ByteExpressionException("$what reads B$index but $have")
            }
            return this[index] and 0xFF
        }

        /** WiCAN's precedence; [UNARY] binds tighter than any binary operator. */
        private fun precedence(op: Char): Int = when (op) {
            '|', '^' -> 1
            '&' -> 2
            '<', '>' -> 3
            '+', '-' -> 4
            '*', '/' -> 5
            UNARY -> 6
            else -> 0
        }

        private const val UNARY = 'u'
        private val RANGE = Regex("""\[\s*([BS])\s*(\d+)\s*:\s*([BS])\s*(\d+)\s*]""")
    }

    /** Shunting-yard like WiCAN's: pops while the stacked operator's precedence is >= the incoming one. */
    private class Parser(private val s: String) {
        private var i = 0
        private val operands = ArrayList<Node>()
        private val operators = ArrayList<Char>()
        private var expectOperand = true

        fun parse(): Node {
            if (s.isBlank()) throw ByteExpressionException("the expression is empty")
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || c == '.' -> operand(number())
                    c == 'V' -> { i++; operand(Var) }
                    c == '[' -> operand(range())
                    c == 'B' || c == 'S' -> operand(byte(c == 'S'))
                    c == '(' -> {
                        if (!expectOperand) fail("missing operator before '('")
                        operators += '('
                        i++
                    }
                    c == ')' -> {
                        if (expectOperand) fail("missing operand before ')'")
                        while (operators.isNotEmpty() && operators.last() != '(') apply(operators.removeAt(operators.lastIndex))
                        if (operators.isEmpty()) fail("')' without a matching '('")
                        operators.removeAt(operators.lastIndex)
                        i++
                    }
                    c in "+-*/&|^" || (c == '<' || c == '>') && s.getOrNull(i + 1) == c -> operator(c)
                    else -> fail("unexpected '$c'")
                }
            }
            if (expectOperand) fail("the expression ends with an operator")
            while (operators.isNotEmpty()) {
                val op = operators.removeAt(operators.lastIndex)
                if (op == '(') throw ByteExpressionException("'(' without a matching ')'")
                apply(op)
            }
            return operands.single()
        }

        private fun operand(node: Node) {
            if (!expectOperand) fail("missing operator")
            operands += node
            expectOperand = false
        }

        private fun operator(c: Char) {
            val start = i
            i += if (c == '<' || c == '>') 2 else 1
            if (expectOperand) {
                // Extension: a sign in front of an operand.
                when (c) {
                    '-' -> operators += UNARY
                    '+' -> Unit
                    else -> fail("missing operand before '${s.substring(start, i)}'", start)
                }
                return
            }
            while (operators.isNotEmpty() && operators.last() != '(' && precedence(operators.last()) >= precedence(c)) {
                apply(operators.removeAt(operators.lastIndex))
            }
            operators += c
            expectOperand = true
        }

        private fun apply(op: Char) {
            if (op == UNARY) {
                operands += Negate(operands.removeAt(operands.lastIndex))
                return
            }
            val right = operands.removeAt(operands.lastIndex)
            val left = operands.removeAt(operands.lastIndex)
            operands += Binary(op, left, right)
        }

        private fun number(): Node {
            val start = i
            if (s[i] == '0' && s.getOrNull(i + 1)?.lowercaseChar() == 'x') {
                i += 2
                while (i < s.length && s[i].isHex()) i++
                val digits = s.substring(start + 2, i)
                if (digits.isEmpty()) fail("'0x' needs hex digits", start)
                return Num(digits.toLongOrNull(16)?.toDouble() ?: fail("hex number too large", start))
            }
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return Num(s.substring(start, i).toDoubleOrNull() ?: fail("bad number '${s.substring(start, i)}'", start))
        }

        private fun range(): Node {
            val m = RANGE.matchAt(s, i) ?: fail("expected [Bn:Bm] or [Sn:Sm]")
            val (l1, a, l2, b) = m.destructured
            if (l1 != l2) fail("a range uses B or S on both ends")
            val start = a.toIntOrNull() ?: fail("index too large")
            val end = b.toIntOrNull() ?: fail("index too large")
            if (end < start) fail("range [$l1$start:$l2$end] runs backwards")
            if (end - start > 7) fail("range [$l1$start:$l2$end] is longer than 8 bytes")
            i = m.range.last + 1
            return Range(start, end, signed = l1 == "S")
        }

        private fun byte(signed: Boolean): Node {
            val start = i
            i++
            while (i < s.length && s[i].isDigit()) i++
            val index = s.substring(start + 1, i).toIntOrNull() ?: fail("'${s[start]}' needs a byte number", start)
            if (signed || s.getOrNull(i) != ':') return ByteAt(index, bit = null, signed = signed)
            val bit = s.getOrNull(i + 1)?.takeIf { it.isDigit() } ?: fail("B$index: needs one bit digit (0-9)", start)
            i += 2
            return ByteAt(index, bit = bit - '0', signed = false)
        }

        private fun Char.isHex() = isDigit() || lowercaseChar() in 'a'..'f'

        private fun fail(message: String, at: Int = i): Nothing =
            throw ByteExpressionException("$message at position ${at + 1} of \"$s\"")
    }
}

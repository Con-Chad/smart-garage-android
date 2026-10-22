package io.github.conchad.smartgarage.ui.glass

import androidx.compose.ui.graphics.Color

/**
 * Turns a vehicle's free-text color ("Forest Green", "Silber metallic", "#1a5e2a") into the tint the aurora drifts
 * in (D-A24). Pure Kotlin on packed ARGB ints so it is unit-testable; [color] wraps the result for Compose.
 *
 * Rules, in order: a hex literal wins; otherwise the longest matching color word (EN or DE, accent-insensitive)
 * anywhere in the text; "dark"/"light" ("dunkel"/"hell") shift it; "metallic"/"pearl" desaturate a little.
 * Black, white and greys map to a cool steel so the aurora is still visible rather than invisible or blinding.
 * Null when nothing matched, so the caller can fall back to the theme primary.
 */
object VehicleTint {
    /** Steel blue-grey used for black, white, grey and silver cars: still an aurora, never a black void. */
    const val STEEL: Int = 0xFF9FB3C8.toInt()

    private val words: List<Pair<String, Int>> = listOf(
        // greens
        "forest green" to 0xFF1F6B3A, "dark green" to 0xFF1F6B3A, "british racing green" to 0xFF1B4D3E,
        "racing green" to 0xFF1B4D3E, "lime" to 0xFF7FC241, "mint" to 0xFF7FD8B8, "olive" to 0xFF7A7A3A,
        "green" to 0xFF2E9A55, "grün" to 0xFF2E9A55, "gruen" to 0xFF2E9A55, "dunkelgrün" to 0xFF1F6B3A,
        // blues
        "navy" to 0xFF1F3A7A, "marine" to 0xFF1F3A7A, "sky blue" to 0xFF7CB8E8, "light blue" to 0xFF7CB8E8,
        "hellblau" to 0xFF7CB8E8, "dunkelblau" to 0xFF1F3A7A, "teal" to 0xFF1F8A8A, "petrol" to 0xFF1F6E78,
        "türkis" to 0xFF2BB5B5, "turquoise" to 0xFF2BB5B5, "cyan" to 0xFF2BB5C8, "blue" to 0xFF2F6FD6, "blau" to 0xFF2F6FD6,
        // reds
        "maroon" to 0xFF7A1F2E, "burgundy" to 0xFF7A1F2E, "bordeaux" to 0xFF7A1F2E, "weinrot" to 0xFF7A1F2E,
        "crimson" to 0xFFC8203A, "scarlet" to 0xFFD8302A, "red" to 0xFFD0302C, "rot" to 0xFFD0302C,
        "pink" to 0xFFE88AB5, "rosa" to 0xFFE88AB5, "magenta" to 0xFFD03A8C,
        // oranges, yellows, golds
        "orange" to 0xFFE87A2A, "yellow" to 0xFFE8C62A, "gelb" to 0xFFE8C62A, "gold" to 0xFFD4AF37,
        "champagne" to 0xFFE2CBA0, "beige" to 0xFFD2BD9A, "tan" to 0xFFC8A878, "sand" to 0xFFD2BD9A,
        "bronze" to 0xFFB07A3A, "copper" to 0xFFB8703A, "kupfer" to 0xFFB8703A,
        // browns, purples
        "brown" to 0xFF7A5230, "braun" to 0xFF7A5230, "mocha" to 0xFF6E4E3A, "purple" to 0xFF7A3AB8,
        "lila" to 0xFF9A5AD8, "violet" to 0xFF7A3AB8, "violett" to 0xFF7A3AB8, "plum" to 0xFF6E3A6E,
        // neutrals → steel
        "silver" to STEEL, "silber" to STEEL, "grey" to STEEL, "gray" to STEEL, "grau" to STEEL,
        "anthracite" to STEEL, "anthrazit" to STEEL, "graphite" to STEEL, "graphit" to STEEL,
        "charcoal" to STEEL, "black" to STEEL, "schwarz" to STEEL, "white" to STEEL, "weiß" to STEEL,
        "weiss" to STEEL, "pearl" to STEEL, "perl" to STEEL, "titan" to STEEL, "platinum" to STEEL,
    ).map { (word, argb) -> word.fold() to argb.toInt() }

    private val compoundPrefix = Regex("\\b(dunkel|hell|perl|tief|signal|nacht|mitternachts|metallic)(?=[a-z])")

    private val hex = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\\b|\\b([0-9a-fA-F]{6})\\b")

    /** Packed ARGB for [text], or null when it names no color. */
    fun argb(text: String?): Int? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        hex.find(raw)?.let { m ->
            val digits = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val full = if (digits.length == 3) digits.map { "$it$it" }.joinToString("") else digits
            return (0xFF000000L or full.toLong(16)).toInt()
        }
        // German compounds ("Dunkelrot", "Perlweiss", "Nachtblau") are split at their modifier so the color word stands alone.
        val folded = raw.fold().replace(compoundPrefix, "$1 ")
        val match = words.filter { (word, _) -> folded.containsWord(word) }.maxByOrNull { it.first.length } ?: return null
        var color = match.second
        if (color != STEEL) {
            if (folded.containsWord("dark") || folded.contains("dunkel")) color = mix(color, 0xFF000000.toInt(), 0.28f)
            if (folded.containsWord("light") || folded.contains("hell") || folded.containsWord("pale")) color = mix(color, 0xFFFFFFFF.toInt(), 0.3f)
            if (folded.contains("metallic") || folded.contains("metallik") || folded.contains("pearl") || folded.contains("perl")) {
                color = mix(color, STEEL, 0.2f)
            }
        }
        return color
    }

    /** [argb] as a Compose color, or [fallback] when the text names no color. */
    fun color(text: String?, fallback: Color): Color = argb(text)?.let { Color(it) } ?: fallback

    /** Three related colors for the aurora ribbons: the tint, a hue-shifted neighbour and a lighter cousin. */
    fun palette(tint: Color): List<Color> {
        val hsl = toHsl(tint)
        val (h, s, l) = hsl
        val a = fromHsl(h, (s * 0.9f).coerceIn(0.2f, 1f), l.coerceIn(0.45f, 0.75f))
        val b = fromHsl((h + 24f) % 360f, (s * 0.8f).coerceIn(0.2f, 1f), (l + 0.1f).coerceIn(0.5f, 0.8f))
        val c = fromHsl((h - 20f + 360f) % 360f, (s * 0.7f).coerceIn(0.15f, 1f), (l + 0.2f).coerceIn(0.55f, 0.85f))
        return listOf(a, b, c)
    }

    private fun String.fold(): String = lowercase()
        .replace("ü", "u").replace("ö", "o").replace("ä", "a").replace("ß", "ss")
        .replace(Regex("[^a-z0-9#]+"), " ").trim()

    private fun String.containsWord(word: String): Boolean {
        var from = 0
        while (true) {
            val at = indexOf(word, from)
            if (at < 0) return false
            val before = at == 0 || this[at - 1] == ' '
            val afterIndex = at + word.length
            val after = afterIndex >= length || this[afterIndex] == ' '
            if (before && after) return true
            from = at + 1
        }
    }

    internal fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun toHsl(c: Color): Triple<Float, Float, Float> {
        val r = c.red
        val g = c.green
        val b = c.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        val d = max - min
        if (d < 1e-4f) return Triple(0f, 0f, l)
        val s = d / (1f - kotlin.math.abs(2f * l - 1f))
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        return Triple((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
    }
}

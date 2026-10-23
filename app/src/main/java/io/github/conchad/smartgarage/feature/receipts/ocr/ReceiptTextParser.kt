package io.github.conchad.smartgarage.feature.receipts.ocr

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns on-device OCR text into receipt fields (total, fuel volume/price, date/time, vendor, currency,
 * fuel grade, address). Pure Kotlin and stateless: safe to call from any thread.
 *
 * Handles English and German fuel, workshop and general expense receipts, both decimal conventions
 * (1.234,56 / 1,234.56), 3-decimal fuel prices (1,799 and 1,79⁹), US gallons, UK pence-per-litre and
 * common OCR confusions (O/0, l/I/1, S/5) inside numbers.
 */
class ReceiptTextParser @Inject constructor() {

    /**
     * @param lines OCR lines in reading order (top to bottom).
     * @param locale hint used only to break dd/MM vs MM/dd ties when the currency gives no clue.
     */
    fun parse(lines: List<OcrLine>, locale: Locale = Locale.getDefault()): ParsedReceipt {
        val rows = buildRows(lines)
        if (rows.isEmpty()) return ParsedReceipt(warnings = listOf(ParseWarning.NO_TEXT))

        val warnings = linkedSetOf<ParseWarning>()
        val conf = linkedMapOf<ReceiptField, Double>()

        val currency = detectCurrency(rows)
        currency?.let { conf[ReceiptField.CURRENCY] = it.second }

        val volume = findVolume(rows)
        val price = findPrice(rows, volume)
        val total = findTotal(rows)
        val date = findDate(rows, monthFirst(locale, currency?.first))
        val time = findTime(rows, date?.row)
        val vendor = findVendor(rows)
        val address = findAddress(rows)
        val grade = if (volume != null || price != null) findFuelGrade(rows) else null

        var totalConf = total?.conf
        var volumeConf = volume?.conf
        var priceConf = price?.conf
        var pricePerLiter = price?.cents
        var priceDerived = false

        if (volume?.fromGallons == true) warnings += ParseWarning.VOLUME_CONVERTED_FROM_GALLONS
        if (price?.fromGallons == true) warnings += ParseWarning.PRICE_CONVERTED_FROM_GALLONS
        if (total != null && !total.labelled) warnings += ParseWarning.TOTAL_UNLABELLED

        if (total != null && volume != null) {
            if (pricePerLiter == null) {
                pricePerLiter = round2(total.cents / volume.liters)
                priceDerived = true
                priceConf = minOf(total.conf, volume.conf) * 0.8
                warnings += ParseWarning.PRICE_DERIVED
            } else {
                val expected = volume.liters * pricePerLiter
                if (abs(expected - total.cents) <= total.cents * 0.02) {
                    totalConf = boost(totalConf)
                    volumeConf = boost(volumeConf)
                    priceConf = boost(priceConf)
                } else {
                    warnings += ParseWarning.TOTAL_INCONSISTENT
                    totalConf = totalConf?.times(0.7)
                    volumeConf = volumeConf?.times(0.7)
                    priceConf = priceConf?.times(0.7)
                }
            }
        }

        totalConf?.let { conf[ReceiptField.TOTAL] = it }
        volumeConf?.let { conf[ReceiptField.VOLUME] = it }
        if (pricePerLiter != null) priceConf?.let { conf[ReceiptField.PRICE_PER_LITER] = it }
        date?.let { conf[ReceiptField.DATE] = it.conf; if (it.ambiguous) warnings += ParseWarning.DATE_AMBIGUOUS }
        time?.let { conf[ReceiptField.TIME] = it.second }
        vendor?.let { conf[ReceiptField.VENDOR] = it.second }
        address?.let { conf[ReceiptField.ADDRESS] = it.second }
        grade?.let { conf[ReceiptField.FUEL_GRADE] = it.second }

        if (rows.map { it.confidence }.average() < 0.6) warnings += ParseWarning.LOW_OCR_CONFIDENCE

        return ParsedReceipt(
            totalCents = total?.cents,
            volumeLiters = volume?.liters,
            volumeFromGallons = volume?.fromGallons == true,
            pricePerLiterCents = pricePerLiter,
            pricePerLiterDerived = priceDerived,
            date = date?.date,
            time = time?.first,
            vendor = vendor?.first,
            currency = currency?.first,
            fuelGrade = grade?.first,
            address = address?.first,
            confidence = conf.mapValues { (_, v) -> round2(v.coerceIn(0.0, 1.0)) },
            warnings = warnings.toList(),
        )
    }

    // ---------------------------------------------------------------- rows

    private class Row(val index: Int, raw: String, val confidence: Double) {
        val norm: String = normalize(raw)
        val folded: String = fold(norm.lowercase(Locale.ROOT))
    }

    private fun buildRows(lines: List<OcrLine>): List<Row> {
        val usable = lines.filter { it.text.isNotBlank() }
        if (usable.isEmpty()) return emptyList()
        val groups = if (usable.all { it.centerY != null }) groupByRow(usable) else usable.map { listOf(it) }
        return groups.mapIndexed { i, group ->
            val ordered = if (group.all { it.centerX != null }) group.sortedBy { it.centerX } else group
            Row(i, ordered.joinToString("  ") { it.text.trim() }, ordered.minOf { it.confidence.toDouble() }.coerceIn(0.0, 1.0))
        }
    }

    private fun groupByRow(lines: List<OcrLine>): List<List<OcrLine>> {
        val heights = lines.mapNotNull { it.height?.takeIf { h -> h > 0f } }.sorted()
        val tolerance = if (heights.isNotEmpty()) {
            heights[heights.size / 2] * 0.5f
        } else {
            val gaps = lines.map { it.centerY!! }.sorted().zipWithNext { a, b -> b - a }.filter { it > 0f }.sorted()
            if (gaps.isEmpty()) 0f else gaps[(gaps.size * 3) / 4] * 0.4f
        }
        val sorted = lines.withIndex().sortedWith(compareBy({ it.value.centerY }, { it.index })).map { it.value }
        val rows = mutableListOf<MutableList<OcrLine>>()
        var rowY = 0f
        for (line in sorted) {
            val y = line.centerY!!
            if (rows.isNotEmpty() && abs(y - rowY) <= tolerance) {
                rows.last() += line
                rowY = rows.last().map { it.centerY!! }.average().toFloat()
            } else {
                rows += mutableListOf(line)
                rowY = y
            }
        }
        return rows
    }

    // ---------------------------------------------------------------- currency

    private fun detectCurrency(rows: List<Row>): Pair<String, Double>? {
        val counts = linkedMapOf<String, Int>()
        for (row in rows) for ((code, re) in CURRENCY_PATTERNS) {
            val n = re.findAll(row.norm).count()
            if (n > 0) counts[code] = (counts[code] ?: 0) + n
        }
        val best = counts.maxByOrNull { it.value } ?: return null
        return best.key to if (best.value >= 2) 0.9 else 0.75
    }

    private fun monthFirst(locale: Locale, currency: String?): Boolean = when (currency) {
        "USD" -> true
        "EUR", "GBP", "CHF" -> false
        else -> locale.country.uppercase(Locale.ROOT) in MONTH_FIRST_COUNTRIES
    }

    // ---------------------------------------------------------------- volume

    private class VolumeHit(val liters: Double, val fromGallons: Boolean, val conf: Double)

    private fun findVolume(rows: List<Row>): VolumeHit? {
        for (row in rows) {
            for (m in VOLUME_UNIT.findAll(row.norm)) {
                val n = parseNumber(m.groupValues[1], threeDecimalsIsFraction = true) ?: continue
                val gallons = m.groupValues[2].lowercase(Locale.ROOT).startsWith("gal")
                volumeHit(n.value, gallons, if (n.decimals >= 2) 0.85 else 0.6, row)?.let { return it }
            }
        }
        // Label form: "Menge 45,67" / "GALLONS 12.345" (no unit after the number).
        for ((i, row) in rows.withIndex()) {
            val label = VOLUME_LABEL.find(row.folded) ?: continue
            if (PRICE_MARKER.containsMatchIn(row.folded)) continue
            val gallons = label.value.startsWith("gal")
            val candidates = numbersIn(row.norm, threeDecimalsIsFraction = true).filter { it.decimals in 2..3 }
            val source = candidates.ifEmpty {
                rows.getOrNull(i + 1)?.takeIf { lettersOnly(it.folded).length <= 3 }
                    ?.let { next -> numbersIn(next.norm, true).filter { it.decimals in 2..3 } }.orEmpty()
            }
            for (n in source) volumeHit(n.value, gallons, 0.7, row)?.let { return it }
        }
        return null
    }

    private fun volumeHit(value: Double, gallons: Boolean, base: Double, row: Row): VolumeHit? {
        val liters = if (gallons) value * LITERS_PER_GALLON else value
        if (liters !in 0.3..500.0) return null
        return VolumeHit(round3(liters), gallons, adjust(base, row.confidence))
    }

    // ---------------------------------------------------------------- price per litre

    private class PriceHit(val cents: Double, val fromGallons: Boolean, val conf: Double)

    private fun findPrice(rows: List<Row>, volume: VolumeHit?): PriceHit? {
        val volumeInGallons = volume?.fromGallons == true
        for (row in rows) {
            for (m in PRICE_UNIT.findAll(row.norm)) {
                val n = parseNumber(m.groupValues[1], threeDecimalsIsFraction = true) ?: continue
                val minor = m.groupValues[2].lowercase(Locale.ROOT).let { it == "ct" || it == "cent" || it == "p" }
                val gallons = m.groupValues[3].lowercase(Locale.ROOT).startsWith("g")
                priceHit(n.value, minor, gallons, 0.85, row)?.let { return it }
            }
        }
        for (row in rows) {
            val label = PRICE_LABEL.find(row.folded) ?: continue
            // A bare "Preis 3,50" is only a per-litre price on a receipt that also shows a volume.
            val explicitUnit = label.groups[3] != null || label.value.startsWith("pp")
            if (!explicitUnit && volume == null) continue
            val gallons = volumeInGallons || label.value.contains("gal") || label.value.startsWith("ppg") ||
                Regex("""/\s*g\b""").containsMatchIn(label.value)
            for (n in numbersIn(stripSpans(row.norm, VOLUME_UNIT), true).filter { it.decimals in 1..3 }) {
                priceHit(n.value, false, gallons, 0.7, row)?.let { return it }
            }
        }
        return null
    }

    private fun priceHit(value: Double, minorUnits: Boolean, gallons: Boolean, base: Double, row: Row): PriceHit? {
        val perUnitCents = if (minorUnits || value > 20) value else value * 100
        val perLiter = if (gallons) perUnitCents / LITERS_PER_GALLON else perUnitCents
        if (perLiter !in 15.0..600.0) return null
        return PriceHit(round2(perLiter), gallons, adjust(base, row.confidence))
    }

    // ---------------------------------------------------------------- total

    private class TotalHit(val cents: Long, val labelled: Boolean, val conf: Double)

    private fun findTotal(rows: List<Row>): TotalHit? {
        var best: Triple<Double, Int, Long>? = null // score, row, cents
        for ((i, row) in rows.withIndex()) {
            val score = totalLabelScore(row.folded)
            if (score <= 0.0) continue
            var amounts = moneyIn(row)
            if (amounts.isEmpty()) {
                val next = rows.getOrNull(i + 1)
                if (next != null && totalLabelScore(next.folded) == 0.0 &&
                    lettersOnly(CURRENCY_WORDS.replace(next.folded, "")).length <= 3
                ) {
                    amounts = moneyIn(next)
                }
            }
            val cents = amounts.lastOrNull() ?: continue
            if (best == null || score > best.first || (score == best.first && i > best.second)) {
                best = Triple(score, i, cents)
            }
        }
        best?.let { (score, i, cents) ->
            val base = when {
                score >= 4.0 -> 0.9
                score >= 3.0 -> 0.85
                score >= 2.5 -> 0.75
                else -> 0.7
            }
            return TotalHit(cents, labelled = true, conf = adjust(base, rows[i].confidence))
        }
        // Fallback: largest plausible amount on an unlabelled, non-ID row.
        var fallback: Pair<Long, Row>? = null
        for (row in rows) {
            if (totalLabelScore(row.folded) < 0.0 || ID_ROW.containsMatchIn(row.folded)) continue
            val max = moneyIn(row).maxOrNull() ?: continue
            if (fallback == null || max > fallback.first) fallback = max to row
        }
        return fallback?.let { (cents, row) -> TotalHit(cents, labelled = false, conf = adjust(0.35, row.confidence)) }
    }

    /** < 0: excluded row (subtotal, tax, change...); 0: no label; > 0: strength of the total label. */
    private fun totalLabelScore(f: String): Double {
        val totalWord = GENERIC_TOTAL.containsMatchIn(f) || DUE_TOTAL.containsMatchIn(f)
        if (EXCLUDED_AMOUNT.containsMatchIn(f) && !(totalWord && INCLUSIVE.containsMatchIn(f))) return -1.0
        return when {
            GRAND_TOTAL.containsMatchIn(f) -> 5.0
            DUE_TOTAL.containsMatchIn(f) -> 4.0
            GENERIC_TOTAL.containsMatchIn(f) -> 3.0
            WEAK_TOTAL.containsMatchIn(f) -> 2.5
            PAYMENT.containsMatchIn(f) -> 2.0
            else -> 0.0
        }
    }

    /** Amounts with exactly two decimals, ignoring volumes, unit prices, dates, times and percentages. */
    private fun moneyIn(row: Row): List<Long> {
        var t = row.norm
        for (re in listOf(VOLUME_UNIT, PRICE_UNIT, DATE_DMY, DATE_ISO, TIME, PERCENT)) t = stripSpans(t, re)
        return numbersIn(t, threeDecimalsIsFraction = false)
            .filter { it.decimals == 2 && it.value > 0.0 }
            .map { (it.value * 100).roundToLong() }
    }

    // ---------------------------------------------------------------- date / time

    private class DateHit(val date: LocalDate, val conf: Double, val ambiguous: Boolean, val row: Int)

    private fun findDate(rows: List<Row>, monthFirst: Boolean): DateHit? {
        val hits = rows.flatMap { row -> datesIn(row, monthFirst) }
        if (hits.isEmpty()) return null
        fun Row.isDue() = DUE_DATE.containsMatchIn(folded)
        return hits.firstOrNull { DATE_LABEL.containsMatchIn(rows[it.row].folded) && !rows[it.row].isDue() }
            ?: hits.firstOrNull { !rows[it.row].isDue() }
            ?: hits.first()
    }

    private fun datesIn(row: Row, monthFirst: Boolean): List<DateHit> {
        val out = mutableListOf<DateHit>()
        for (m in DATE_ISO.findAll(row.norm)) {
            val (y, mo, d) = m.destructured
            date(y.toInt(), mo.toInt(), d.toInt())?.let { out += DateHit(it, adjust(0.9, row.confidence), false, row.index) }
        }
        for (m in DATE_DMY.findAll(row.norm)) {
            val a = m.groupValues[1].toInt()
            val sep = m.groupValues[2]
            val b = m.groupValues[3].toInt()
            val y = m.groupValues[4].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            if (sep == ".") {
                date(y, b, a)?.let { out += DateHit(it, adjust(0.9, row.confidence), false, row.index) }
                continue
            }
            val dmy = date(y, b, a)
            val mdy = date(y, a, b)
            val hit = when {
                dmy != null && mdy == null -> DateHit(dmy, adjust(0.85, row.confidence), false, row.index)
                mdy != null && dmy == null -> DateHit(mdy, adjust(0.85, row.confidence), false, row.index)
                dmy != null && mdy != null && dmy == mdy -> DateHit(dmy, adjust(0.85, row.confidence), false, row.index)
                dmy != null && mdy != null ->
                    DateHit(if (monthFirst) mdy else dmy, adjust(0.6, row.confidence), true, row.index)
                else -> null
            }
            hit?.let { out += it }
        }
        for (m in DATE_NAMED_DMY.findAll(row.folded)) {
            val month = monthOf(m.groupValues[2]) ?: continue
            val y = m.groupValues[3].let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            date(y, month, m.groupValues[1].toInt())?.let { out += DateHit(it, adjust(0.85, row.confidence), false, row.index) }
        }
        for (m in DATE_NAMED_MDY.findAll(row.folded)) {
            val month = monthOf(m.groupValues[1]) ?: continue
            date(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())
                ?.let { out += DateHit(it, adjust(0.85, row.confidence), false, row.index) }
        }
        return out
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? {
        if (y !in 2000..2099) return null
        return try {
            LocalDate.of(y, m, d)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun monthOf(token: String): Int? = MONTHS.entries.firstOrNull { token.startsWith(it.key) }?.value

    private fun findTime(rows: List<Row>, dateRow: Int?): Pair<LocalTime, Double>? {
        fun timesIn(row: Row): List<LocalTime> {
            val out = mutableListOf<LocalTime>()
            for (m in TIME.findAll(row.norm)) {
                out += LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].ifEmpty { "0" }.toInt())
            }
            for (m in TIME_UHR.findAll(row.folded)) out += LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            return out
        }
        dateRow?.let { r -> timesIn(rows[r]).firstOrNull()?.let { return it to adjust(0.85, rows[r].confidence) } }
        rows.firstOrNull { TIME_LABEL.containsMatchIn(it.folded) && timesIn(it).isNotEmpty() }
            ?.let { return timesIn(it).first() to adjust(0.8, it.confidence) }
        rows.firstOrNull { timesIn(it).isNotEmpty() }?.let { return timesIn(it).first() to adjust(0.65, it.confidence) }
        return null
    }

    // ---------------------------------------------------------------- vendor / address / grade

    private fun findVendor(rows: List<Row>): Pair<String, Double>? {
        val top = rows.take(8)
        for (row in top) for ((re, name) in BRANDS) {
            if (re.containsMatchIn(row.folded)) return name to adjust(0.9, row.confidence)
        }
        rows.take(3).firstOrNull { TOTAL_BRAND.matches(it.folded.trim()) }?.let { return "TotalEnergies" to adjust(0.8, it.confidence) }
        for (row in rows.drop(8)) for ((re, name) in BRANDS) {
            if (re.containsMatchIn(row.folded)) return name to adjust(0.7, row.confidence)
        }
        for (row in rows.take(5)) {
            val text = row.norm.trim().replace(Regex("""\s+"""), " ").trim('*', '-', '=', '#', ' ', '.', ':')
            val letters = text.count { it.isLetter() }
            val digits = text.count { it.isDigit() }
            if (letters < 3 || digits * 2 > letters) continue
            val f = row.folded
            if (VENDOR_NOISE.containsMatchIn(f) || ID_ROW.containsMatchIn(f) || isAddress(f) ||
                datesIn(row, false).isNotEmpty() || totalLabelScore(f) != 0.0 || moneyIn(row).isNotEmpty()
            ) continue
            return text to adjust(0.5, row.confidence)
        }
        return null
    }

    private fun isAddress(f: String) = ADDRESS_STREET.containsMatchIn(f) || ADDRESS_CITY.containsMatchIn(f)

    private fun findAddress(rows: List<Row>): Pair<String, Double>? {
        val top = rows.take(12)
        val i = top.indexOfFirst { isAddress(it.folded) && !ID_ROW.containsMatchIn(it.folded) && totalLabelScore(it.folded) == 0.0 }
        if (i < 0) return null
        val parts = mutableListOf(rows[i])
        rows.getOrNull(i + 1)?.takeIf { next ->
            isAddress(next.folded) && !ID_ROW.containsMatchIn(next.folded) && totalLabelScore(next.folded) == 0.0
        }?.let { parts += it }
        val text = parts.joinToString(", ") { it.norm.trim().replace(Regex("""\s+"""), " ") }
        return text to adjust(if (parts.size == 2) 0.8 else 0.65, parts.minOf { it.confidence })
    }

    private fun findFuelGrade(rows: List<Row>): Pair<String, Double>? {
        for ((re, name) in FUEL_GRADES) for (row in rows) {
            val m = re.find(row.folded) ?: continue
            val label = if (name.contains("$")) name.replace("$", m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "") else name
            return label to adjust(0.8, row.confidence)
        }
        return null
    }

    // ---------------------------------------------------------------- numbers

    private data class Num(val value: Double, val decimals: Int)

    private fun numbersIn(text: String, threeDecimalsIsFraction: Boolean): List<Num> =
        NUM.findAll(text).mapNotNull { parseNumber(it.value, threeDecimalsIsFraction) }.toList()

    /**
     * Parses 1.234,56 / 1,234.56 / 1234,56 / 1234.56 / 1,799. A single separator followed by exactly three
     * digits is a decimal point for quantities and unit prices, a thousands separator for money.
     */
    private fun parseNumber(token: String, threeDecimalsIsFraction: Boolean): Num? {
        val t = token.trim()
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        val (intPart, fracPart) = when {
            lastDot >= 0 && lastComma >= 0 -> {
                val dec = maxOf(lastDot, lastComma)
                t.substring(0, dec).filter { it.isDigit() } to t.substring(dec + 1)
            }
            lastDot >= 0 || lastComma >= 0 -> {
                val sep = if (lastDot >= 0) '.' else ','
                val count = t.count { it == sep }
                val after = t.substring(t.lastIndexOf(sep) + 1)
                if (count > 1 || (after.length == 3 && !threeDecimalsIsFraction)) t.filter { it.isDigit() } to ""
                else t.substring(0, t.lastIndexOf(sep)).filter { it.isDigit() } to after
            }
            else -> t to ""
        }
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        val value = "${intPart.ifEmpty { "0" }}.${fracPart.ifEmpty { "0" }}".toDoubleOrNull() ?: return null
        return Num(value, fracPart.length)
    }

    private companion object {
        const val LITERS_PER_GALLON = 3.785411784

        fun round2(v: Double) = Math.round(v * 100.0) / 100.0
        fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0
        fun boost(v: Double?) = v?.let { minOf(0.99, it + 0.1) }

        /** Scales a rule's base confidence by how sure the OCR engine was about the row. */
        fun adjust(base: Double, ocr: Double) = base * (0.6 + 0.4 * ocr)

        fun lettersOnly(s: String) = s.filter { it.isLetter() }

        fun stripSpans(text: String, re: Regex) = re.replace(text) { " ".repeat(it.value.length) }

        val SUPERSCRIPTS = mapOf(
            '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4',
            '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9',
        )

        fun normalize(raw: String): String {
            var t = buildString {
                for (c in raw) append(
                    SUPERSCRIPTS[c] ?: when (c) {
                        ' ', ' ', ' ', '\t' -> ' '
                        '‚' -> ','
                        '−', '–' -> '-'
                        else -> c
                    },
                )
            }
            t = fixNumericNoise(t)
            t = STRAY_SPACE_AFTER_SEP.replace(t, "$1$2$3")
            t = STRAY_SPACE_BEFORE_SEP.replace(t, "$1$2$3")
            t = CURRENCY_BEFORE_DIGIT.replace(t, "$1 ")
            t = DIGIT_BEFORE_CURRENCY.replace(t, "$1 $2")
            t = SPLIT_LAST_PRICE_DIGIT.replace(t, "$1$2")
            return t
        }

        /** O/o→0, I/l/|→1, S→5, only where the neighbours make it clearly part of a number. */
        fun fixNumericNoise(s: String): String {
            val c = s.toCharArray()
            val confusable = "OoIl|S"
            fun digitAt(i: Int) = c.getOrNull(i)?.isDigit() == true
            fun sepAt(i: Int) = c.getOrNull(i)?.let { it in ".,:" } == true
            fun confAt(i: Int) = c.getOrNull(i)?.let { it in confusable } == true
            for (i in c.indices) {
                val ch = c[i]
                if (ch !in confusable) continue
                val prev = c.getOrNull(i - 1)
                val next = c.getOrNull(i + 1)
                val leftNum = digitAt(i - 1) || (sepAt(i - 1) && digitAt(i - 2))
                val rightNum = digitAt(i + 1) || (sepAt(i + 1) && digitAt(i + 2)) ||
                    (confAt(i + 1) && (digitAt(i + 2) || sepAt(i + 2)))
                val interior = leftNum && rightNum
                val leading = (prev == null || prev.isWhitespace()) && ch != 'S' && rightNum
                val trailingZero = (ch == 'O' || ch == 'o') && digitAt(i - 1) && (next == null || next.isWhitespace())
                if (interior || leading || trailingZero) {
                    c[i] = when (ch) {
                        'O', 'o' -> '0'
                        'S' -> '5'
                        else -> '1'
                    }
                }
            }
            return String(c)
        }

        fun fold(s: String) = s.replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")

        val IC = setOf(RegexOption.IGNORE_CASE)

        /** A number token: digits with optional . / , grouping and decimals, not glued to letters. */
        const val NUM_CORE = """(?<![\d.,A-Za-z])(?:\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,3})?|\d+(?:[.,]\d{1,3})?)(?![.,]?\d)"""
        val NUM = Regex(NUM_CORE)

        val STRAY_SPACE_AFTER_SEP = Regex("""(\d)\s*([.,])\s+(\d{2,3})(?!\d)""")
        val STRAY_SPACE_BEFORE_SEP = Regex("""(\d)\s+([.,])(\d{2,3})(?!\d)""")
        val CURRENCY_BEFORE_DIGIT = Regex("""(€|\$|£|\bEUR|\bUSD|\bGBP|\bCHF)(?=\d)""", IC)
        val DIGIT_BEFORE_CURRENCY = Regex("""(\d)(€|\$|£|EUR\b|USD\b|GBP\b|CHF\b)""", IC)
        val SPLIT_LAST_PRICE_DIGIT =
            Regex("""(\d[.,]\d{2})\s(\d)(?=\s*(?:€|eur|\$|£|chf)?\s*/\s*(?:l|ltr|liter|litre|gal)\b)""", IC)

        val CURRENCY_PATTERNS = listOf(
            "EUR" to Regex("""€|\bEUR\b""", IC),
            "GBP" to Regex("""£|\bGBP\b"""),
            "CHF" to Regex("""\bCHF\b|\bSFr\b""", IC),
            "USD" to Regex("""\$|\bUSD\b""", IC),
        )
        val CURRENCY_WORDS = Regex("""€|\$|£|\b(eur|usd|gbp|chf)\b""")
        val MONTH_FIRST_COUNTRIES = setOf("US", "PH", "BZ", "FM", "PW", "MH")

        val VOLUME_UNIT = Regex("""($NUM_CORE)\s*(liters?|litres?|ltrs?\.?|lt|l|gallons?|gals?)(?![\p{L}/])""", IC)
        val VOLUME_LABEL = Regex("""\b(menge|volumen|volume|liter|liters|litre|litres|gallons?|gals?|ltr)\b""")
        val PRICE_MARKER = Regex("""/\s*(l|ltr|liter|litre|gal|g)\b|pro\s+liter|per\s+(litre|liter|gallon)|preis|price|ppl|ppg""")
        val PRICE_UNIT = Regex(
            """($NUM_CORE)\s*(€|eur|\$|usd|£|gbp|chf|ct|cent|p)?\s*(?:/|pro|per)\s*(liters?|litres?|ltrs?|lt|l|gallons?|gal|g)(?![\p{L}])""",
            IC,
        )
        val PRICE_LABEL = Regex(
            """(preis|price|ppg|ppl|ppu|einzelpreis)\s*(/|per|pro)?\s*(liters?|litres?|ltr|l|gallons?|gal|g)?\b""",
        )
        val PERCENT = Regex("""($NUM_CORE)\s*%""")

        // Labels (matched against folded lowercase text).
        val GRAND_TOTAL = Regex("""grand\s*total""")
        val DUE_TOTAL = Regex(
            """gesamtsumme|endsumme|endbetrag|gesamtbetrag|rechnungsbetrag|zu\s*zahlen|zahlbetrag|total\s*due|""" +
                """amount\s*due|balance\s*due|to\s*pay|total\s*amount|total\s*sale|fuel\s*total|fuel\s*sale""",
        )
        val GENERIC_TOTAL = Regex("""summe|gesamt|\btotal\b|brutto""")
        val WEAK_TOTAL = Regex("""betrag|\bamount\b|\bsale\b""")
        val PAYMENT = Regex(
            """\b(bar|ec|ec-?cash|girocard|maestro|visa|mastercard|amex|karte|kartenzahlung|kreditkarte|card|cash|""" +
                """debit|credit|kontaktlos|contactless)\b""",
        )
        val EXCLUDED_AMOUNT = Regex(
            """sub\s*-?\s*total|zwischen\s*-?\s*summe|zw\.?\s*summe|\bnetto|\bnet\b|mwst|\bust\b|u\.st|\bvat\b|\btax|steuer|""" +
                """ruck\s*geld|rueckgeld|wechselgeld|\bchange\b|gegeben|tendered|\bgiven\b|discount|rabatt|savings|""" +
                """ersparnis|punkte|points|payback|bonus|\btip\b|trinkgeld|pfand|deposit""",
        )
        val INCLUSIVE = Regex("""incl|inkl|including|brutto""")
        val ID_ROW = Regex(
            """\*{3,}|x{4,}|\d{8,}|\b(tel|telefon|fon|phone|fax|iban|bic|ust-?id|ust-?idnr|st-?nr|steuer-?nr|terminal|""" +
                """tid|trace|auth|www|http)\b|@""",
        )

        val DATE_ISO = Regex("""(?<!\d)(20\d{2})[-./](\d{1,2})[-./](\d{1,2})(?!\d)""")
        val DATE_DMY = Regex("""(?<![\d.,/-])(\d{1,2})([./-])(\d{1,2})\2(\d{4}|\d{2})(?![\d.,/-]?\d)""")
        private const val MONTH_RE = """(jan|feb|mar|mrz|apr|may|mai|jun|jul|aug|sep|oct|okt|nov|dec|dez)[a-z]*\.?"""
        val DATE_NAMED_DMY = Regex("""(?<!\d)(\d{1,2})\.?\s*$MONTH_RE,?\s*(\d{4}|\d{2})(?!\d)""")
        val DATE_NAMED_MDY = Regex("""\b$MONTH_RE\s+(\d{1,2}),?\s+(\d{4})(?!\d)""")
        val MONTHS = linkedMapOf(
            "jan" to 1, "feb" to 2, "mar" to 3, "mrz" to 3, "apr" to 4, "may" to 5, "mai" to 5, "jun" to 6,
            "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "okt" to 10, "nov" to 11, "dec" to 12, "dez" to 12,
        )
        val DATE_LABEL = Regex("""datum|\bdate\b""")
        val DUE_DATE = Regex("""fallig|\bdue\b|zahlbar|gultig|valid|liefer""")
        val TIME = Regex("""(?<![\d.,:])([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?(?![\d:])""")
        val TIME_UHR = Regex("""(?<![\d.,:])([01]?\d|2[0-3])[.:]([0-5]\d)\s*uhr""")
        val TIME_LABEL = Regex("""zeit|\btime\b""")

        val ADDRESS_STREET = Regex(
            """(str\.|strasse|weg\b|allee\b|platz\b|ring\b|damm\b|gasse\b|chaussee\b)\s*\d+|""" +
                """\b\d+[a-z]?\s+([a-z]+\s+){0,3}(st|street|ave|avenue|rd|road|blvd|boulevard|hwy|highway|dr|drive|""" +
                """ln|lane|way|pkwy|parkway|ct|court|pl|place)\b""",
        )
        val ADDRESS_CITY = Regex(
            """(?<![\d.,])\d{5}\s+[a-z]{3,}|,?\s\b[a-z]{2}\s+\d{5}(-\d{4})?\b|\b[a-z]{1,2}\d[a-z\d]?\s+\d[a-z]{2}\b""",
        )

        val VENDOR_NOISE = Regex(
            """rechnung|quittung|beleg|kassenbon|\bbon\b|receipt|invoice|welcome|willkommen|thank|danke|kunde|customer|""" +
                """copy|kopie|duplikat|original|kasse\b|\bpos\b|store\s*#|filiale|station\s*#|pump|saule|zapf""",
        )

        val TOTAL_BRAND = Regex("""total(energies)?(\s+(tankstelle|station|service))?""")

        val BRANDS: List<Pair<Regex, String>> = listOf(
            """totalenergies""" to "TotalEnergies",
            """\baral\b""" to "Aral",
            """\bshell\b""" to "Shell",
            """\besso\b""" to "Esso",
            """exxon\s*mobil""" to "ExxonMobil",
            """\bexxon\b""" to "Exxon",
            """\bmobil\b""" to "Mobil",
            """\bjet\b""" to "JET",
            """\bbp\b""" to "BP",
            """\bavia\b""" to "AVIA",
            """\bomv\b""" to "OMV",
            """\bagip\b""" to "Agip",
            """\beni\b""" to "Eni",
            """\bstar\b""" to "Star",
            """\bhem\b""" to "HEM",
            """\bchevron\b""" to "Chevron",
            """\bcostco\b""" to "Costco",
            """\bsunoco\b""" to "Sunoco",
            """circle\s*k\b""" to "Circle K",
            """\btexaco\b""" to "Texaco",
            """\bvalero\b""" to "Valero",
            """\bspeedway\b""" to "Speedway",
            """\bmarathon\b""" to "Marathon",
            """phillips\s*66""" to "Phillips 66",
            """\bconoco\b""" to "Conoco",
            """\bsinclair\b""" to "Sinclair",
            """\barco\b""" to "ARCO",
            """\bwawa\b""" to "Wawa",
            """\bsheetz\b""" to "Sheetz",
            """\bquiktrip\b""" to "QuikTrip",
            """7-?eleven""" to "7-Eleven",
            """\btesco\b""" to "Tesco",
            """\bsainsbury""" to "Sainsbury's",
            """\basda\b""" to "Asda",
            """\bmorrisons\b""" to "Morrisons",
            """\bgulf\b""" to "Gulf",
            """\bq8\b""" to "Q8",
            """\bwestfalen\b""" to "Westfalen",
            """\borlen\b""" to "Orlen",
            """\btamoil\b""" to "Tamoil",
            """\brepsol\b""" to "Repsol",
            """\bmigrol\b""" to "Migrol",
            """\bagrola\b""" to "Agrola",
            """\bmol\b""" to "MOL",
            """\blukoil\b""" to "Lukoil",
        ).map { (re, name) -> Regex(re) to name }

        /** Ordered most specific first; `$` is replaced by the first captured group. */
        val FUEL_GRADES: List<Pair<Regex, String>> = listOf(
            """super\s*plus|superplus|super\s*\+|super\s*98""" to "Super Plus",
            """v-?\s*power\s*diesel""" to "V-Power Diesel",
            """v-?\s*power""" to "V-Power",
            """ultimate\s*diesel""" to "Ultimate Diesel",
            """ultimate""" to "Ultimate",
            """premium\s*diesel|diesel\s*premium""" to "Premium Diesel",
            """\bdiesel\b""" to "Diesel",
            """super\s*e\s*-?\s*10|\be\s*-?10\b""" to "Super E10",
            """super\s*e\s*-?\s*5\b|\be5\b""" to "Super E5",
            """super\s*95""" to "Super 95",
            """\be85\b""" to "E85",
            """premium\s*unleaded|unleaded\s*premium""" to "Premium Unleaded",
            """\bunleaded\s*(95|98)\b""" to "Unleaded $",
            """mid-?\s*grade|plus\s*unleaded|unleaded\s*plus""" to "Mid-Grade",
            """\bunleaded\b|\bbleifrei\b""" to "Unleaded",
            """\bregular\b""" to "Regular",
            """\bpremium\b""" to "Premium",
            """\bsuper\b""" to "Super",
            """\b(lpg|autogas)\b""" to "LPG",
            """\b(?:ron|oktan)\s*(95|98|100)\b""" to "$ RON",
            """\b(95|98|100)\s*(?:ron|oktan)\b""" to "$ RON",
        ).map { (re, name) -> Regex(re) to name }
    }
}

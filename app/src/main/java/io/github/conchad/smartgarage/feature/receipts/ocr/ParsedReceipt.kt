package io.github.conchad.smartgarage.feature.receipts.ocr

import java.time.LocalDate
import java.time.LocalTime

/**
 * One recognised line of receipt text, as handed to [ReceiptTextParser].
 *
 * Deliberately independent of the :ocr module's types; the caller maps its OCR output onto this.
 * Positions are optional and in any consistent unit (pixels or normalised). When every line has a
 * [centerY], lines on the same visual row are merged (left-to-right by [centerX]) so a label such as
 * "SUMME" can be paired with the value printed at the right-hand edge. [height] (the text box height)
 * makes that row grouping much more reliable; without it the tolerance is estimated from line spacing.
 */
data class OcrLine(
    val text: String,
    val confidence: Float = 1f,
    val centerX: Float? = null,
    val centerY: Float? = null,
    val height: Float? = null,
)

/** Fields the parser reports a confidence (0..1) for. */
enum class ReceiptField { TOTAL, VOLUME, PRICE_PER_LITER, DATE, TIME, VENDOR, CURRENCY, FUEL_GRADE, ADDRESS }

/** Non-fatal observations the UI may want to surface ("please check"). */
enum class ParseWarning {
    /** The input contained no text at all. */
    NO_TEXT,

    /** No labelled total (TOTAL/SUMME/...) was found; the largest plausible amount was used. */
    TOTAL_UNLABELLED,

    /** total differs from volume x price by more than 2%. */
    TOTAL_INCONSISTENT,

    /** pricePerLiterCents was computed as total / volume, not read from the receipt. */
    PRICE_DERIVED,

    /** The receipt was in US gallons and the volume was converted to litres. */
    VOLUME_CONVERTED_FROM_GALLONS,

    /** The receipt showed a price per US gallon; it was converted to a price per litre. */
    PRICE_CONVERTED_FROM_GALLONS,

    /** A dd/MM vs MM/dd date could be read either way; the locale/currency hint decided. */
    DATE_AMBIGUOUS,

    /** The OCR engine itself was unsure about much of the text. */
    LOW_OCR_CONFIDENCE,
}

/**
 * What [ReceiptTextParser] could extract. Every value is nullable: missing means "not found", never a guess
 * of zero. Money is in minor units (cents/pence) of [currency]; [pricePerLiterCents] is fractional
 * (e.g. 179.9 for 1,799 EUR/l).
 */
data class ParsedReceipt(
    val totalCents: Long? = null,
    val volumeLiters: Double? = null,
    val volumeFromGallons: Boolean = false,
    val pricePerLiterCents: Double? = null,
    val pricePerLiterDerived: Boolean = false,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val vendor: String? = null,
    /** ISO 4217 code (EUR, USD, GBP, CHF) when a symbol/code was printed on the receipt. */
    val currency: String? = null,
    val fuelGrade: String? = null,
    val address: String? = null,
    val confidence: Map<ReceiptField, Double> = emptyMap(),
    val warnings: List<ParseWarning> = emptyList(),
) {
    /** True when the text carries fuel-specific data (a volume or a printed per-litre price). */
    val isFuelReceipt: Boolean
        get() = volumeLiters != null || (pricePerLiterCents != null && !pricePerLiterDerived)

    /** True when nothing useful was found. */
    val isEmpty: Boolean
        get() = totalCents == null && volumeLiters == null && pricePerLiterCents == null && date == null &&
            time == null && vendor == null && currency == null && fuelGrade == null && address == null
}

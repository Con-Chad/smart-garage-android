package io.github.conchad.smartgarage.feature.receipts.ocr

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class ReceiptTextParserTest {

    private val parser = ReceiptTextParser()

    private fun parse(vararg lines: String, locale: Locale = Locale.GERMANY): ParsedReceipt =
        parser.parse(lines.map { OcrLine(it) }, locale)

    // ------------------------------------------------------------ realistic receipts

    @Test
    fun `german Aral fuel receipt fills every field`() {
        val r = parse(
            "ARAL",
            "Tankstelle Müller",
            "Hauptstr. 12",
            "10115 Berlin",
            "Tel. 030 1234567",
            "Säule 3",
            "Super E10",
            "45,67 l x 1,799 EUR/l",
            "82,16 EUR",
            "Summe EUR 82,16",
            "MwSt 19% 13,12",
            "Netto 69,04",
            "EC-Karte 82,16",
            "Datum: 15.10.2026 14:32",
            "Vielen Dank für Ihren Besuch",
        )
        assertThat(r.totalCents).isEqualTo(8216)
        assertThat(r.volumeLiters).isEqualTo(45.67)
        assertThat(r.pricePerLiterCents).isEqualTo(179.9)
        assertThat(r.pricePerLiterDerived).isFalse()
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(r.time).isEqualTo(LocalTime.of(14, 32))
        assertThat(r.vendor).isEqualTo("Aral")
        assertThat(r.currency).isEqualTo("EUR")
        assertThat(r.fuelGrade).isEqualTo("Super E10")
        assertThat(r.address).isEqualTo("Hauptstr. 12, 10115 Berlin")
        assertThat(r.warnings).isEmpty()
        assertThat(r.isFuelReceipt).isTrue()
        assertThat(r.confidence[ReceiptField.TOTAL]).isAtLeast(0.9)
    }

    @Test
    fun `US gas receipt in gallons converts volume and price to litres`() {
        val r = parse(
            "SHELL",
            "1234 MAIN ST",
            "SPRINGFIELD, IL 62704",
            "(217) 555-0100",
            "10/14/2026  08:15 AM",
            "PUMP# 05",
            "UNLEADED",
            "GALLONS: 12.345",
            "PRICE/GAL: $3.499",
            "FUEL SALE $43.20",
            "TOTAL $43.20",
            "VISA ************1234",
            locale = Locale.US,
        )
        assertThat(r.totalCents).isEqualTo(4320)
        assertThat(r.volumeLiters).isEqualTo(46.731)
        assertThat(r.volumeFromGallons).isTrue()
        assertThat(r.pricePerLiterCents).isEqualTo(92.43)
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 10, 14))
        assertThat(r.time).isEqualTo(LocalTime.of(8, 15))
        assertThat(r.vendor).isEqualTo("Shell")
        assertThat(r.currency).isEqualTo("USD")
        assertThat(r.fuelGrade).isEqualTo("Unleaded")
        assertThat(r.address).isEqualTo("1234 MAIN ST, SPRINGFIELD, IL 62704")
        assertThat(r.warnings).containsExactly(
            ParseWarning.VOLUME_CONVERTED_FROM_GALLONS,
            ParseWarning.PRICE_CONVERTED_FROM_GALLONS,
        )
    }

    @Test
    fun `US receipt with unit suffixes GAL and per G`() {
        val r = parse("CHEVRON", "Regular", "10.000 GAL @ $3.599/G", "TOTAL $35.99", locale = Locale.US)
        assertThat(r.volumeLiters).isEqualTo(37.854)
        assertThat(r.pricePerLiterCents).isEqualTo(95.08)
        assertThat(r.totalCents).isEqualTo(3599)
        assertThat(r.fuelGrade).isEqualTo("Regular")
        assertThat(r.vendor).isEqualTo("Chevron")
        assertThat(r.warnings).doesNotContain(ParseWarning.TOTAL_INCONSISTENT)
    }

    @Test
    fun `UK receipt with pence per litre and day-first date`() {
        val r = parse(
            "Tesco Extra",
            "Petrol Filling Station",
            "Unleaded",
            "40.12 L @ 145.9p/L",
            "£58.54",
            "TOTAL £58.54",
            "Card Payment £58.54",
            "VAT @ 20% £9.76",
            "12/10/2026 17:45",
            "London SW1A 1AA",
            locale = Locale.UK,
        )
        assertThat(r.totalCents).isEqualTo(5854)
        assertThat(r.volumeLiters).isEqualTo(40.12)
        assertThat(r.pricePerLiterCents).isEqualTo(145.9)
        assertThat(r.currency).isEqualTo("GBP")
        assertThat(r.vendor).isEqualTo("Tesco")
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 10, 12))
        assertThat(r.time).isEqualTo(LocalTime.of(17, 45))
        assertThat(r.warnings).contains(ParseWarning.DATE_AMBIGUOUS)
        assertThat(r.address).isEqualTo("London SW1A 1AA")
    }

    @Test
    fun `workshop invoice gives total, date and vendor only`() {
        val r = parse(
            "Autohaus Becker GmbH",
            "Industriestraße 7",
            "80331 München",
            "Rechnung Nr. 2026-1047",
            "Rechnungsdatum: 03.09.2026",
            "Ölwechsel inkl. Filter 89,90",
            "Bremsflüssigkeit 45,00",
            "Arbeitslohn 120,00",
            "Nettobetrag 254,90",
            "MwSt 19% 48,43",
            "Gesamtbetrag 303,33 €",
        )
        assertThat(r.totalCents).isEqualTo(30333)
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(r.vendor).isEqualTo("Autohaus Becker GmbH")
        assertThat(r.currency).isEqualTo("EUR")
        assertThat(r.address).isEqualTo("Industriestraße 7, 80331 München")
        assertThat(r.volumeLiters).isNull()
        assertThat(r.pricePerLiterCents).isNull()
        assertThat(r.fuelGrade).isNull()
        assertThat(r.isFuelReceipt).isFalse()
    }

    @Test
    fun `parking expense picks the inclusive amount, not the VAT share`() {
        val r = parse(
            "APCOA PARKING",
            "Parkhaus Zentrum",
            "Einfahrt 22.10.2026 09:12",
            "Ausfahrt 22.10.2026 11:47",
            "Parkdauer 2:35 h",
            "Betrag 6,50 EUR",
            "inkl. 19% MwSt 1,04",
        )
        assertThat(r.totalCents).isEqualTo(650)
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 10, 22))
        assertThat(r.time).isEqualTo(LocalTime.of(9, 12))
        assertThat(r.vendor).isEqualTo("APCOA PARKING")
        assertThat(r.isFuelReceipt).isFalse()
    }

    @Test
    fun `car wash avoids subtotal, tax, cash and change and reports no fuel grade`() {
        val r = parse(
            "Sparkle Car Wash",
            "Premium Wash  12.00",
            "Tire Shine 3.00",
            "Subtotal 15.00",
            "Tax 1.24",
            "Total 16.24",
            "Cash 20.00",
            "Change 3.76",
            "05/06/2026",
            locale = Locale.US,
        )
        assertThat(r.totalCents).isEqualTo(1624)
        assertThat(r.vendor).isEqualTo("Sparkle Car Wash")
        assertThat(r.fuelGrade).isNull()
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 5, 6))
        assertThat(r.warnings).contains(ParseWarning.DATE_AMBIGUOUS)
    }

    // ------------------------------------------------------------ OCR noise

    @Test
    fun `noisy OCR digits are repaired in numeric context only`() {
        val r = parse(
            "ARAL",
            "Super E1O",
            "4S,67 l",
            "1,79⁹ EUR/l",
            "SUMME EUR 82, 16",
            "l5.lO.2O26 l4:32",
        )
        assertThat(r.vendor).isEqualTo("Aral")
        assertThat(r.fuelGrade).isEqualTo("Super E10")
        assertThat(r.volumeLiters).isEqualTo(45.67)
        assertThat(r.pricePerLiterCents).isEqualTo(179.9)
        assertThat(r.totalCents).isEqualTo(8216)
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(r.time).isEqualTo(LocalTime.of(14, 32))
    }

    @Test
    fun `superscript last price digit recognised as a separate character`() {
        val r = parse("Super", "45,67 l", "1,79 9 €/l", "Summe 82,16 €")
        assertThat(r.pricePerLiterCents).isEqualTo(179.9)
        assertThat(r.warnings).doesNotContain(ParseWarning.TOTAL_INCONSISTENT)
    }

    @Test
    fun `stray space before the decimal separator is joined`() {
        assertThat(parse("Summe 45 ,67 €").totalCents).isEqualTo(4567)
    }

    @Test
    fun `letters in words are not turned into digits`() {
        val r = parse("Shell Station", "Oil Service", "Total 10,00 €")
        assertThat(r.vendor).isEqualTo("Shell")
        assertThat(r.totalCents).isEqualTo(1000)
    }

    @Test
    fun `low OCR confidence is flagged and lowers field confidence`() {
        val sure = parser.parse(listOf(OcrLine("Summe 20,00 €", 0.99f)), Locale.GERMANY)
        val unsure = parser.parse(listOf(OcrLine("Summe 20,00 €", 0.3f)), Locale.GERMANY)
        assertThat(unsure.totalCents).isEqualTo(2000)
        assertThat(unsure.warnings).contains(ParseWarning.LOW_OCR_CONFIDENCE)
        assertThat(sure.warnings).doesNotContain(ParseWarning.LOW_OCR_CONFIDENCE)
        assertThat(unsure.confidence[ReceiptField.TOTAL]).isLessThan(sure.confidence[ReceiptField.TOTAL])
    }

    // ------------------------------------------------------------ total selection traps

    @Test
    fun `subtotal and discount are skipped in favour of Summe`() {
        val r = parse("Zwischensumme 70,00", "Rabatt 5,00", "Summe 65,00 €")
        assertThat(r.totalCents).isEqualTo(6500)
    }

    @Test
    fun `amount due beats a larger-looking subtotal and tax`() {
        val r = parse("Subtotal 100.00", "Sales Tax 8.00", "Amount Due 108.00", locale = Locale.US)
        assertThat(r.totalCents).isEqualTo(10800)
    }

    @Test
    fun `total incl VAT is a total but the VAT line is not`() {
        val r = parse("Total incl. VAT 45.00", "VAT 20% 7.50", locale = Locale.UK)
        assertThat(r.totalCents).isEqualTo(4500)
    }

    @Test
    fun `cash tendered and change are not the total`() {
        val r = parse("Summe 82,16 EUR", "BAR 100,00", "Rückgeld 17,84")
        assertThat(r.totalCents).isEqualTo(8216)
    }

    @Test
    fun `payment line is used when no total label exists`() {
        val r = parse("Waschanlage Programm 3", "EC 14,90")
        assertThat(r.totalCents).isEqualTo(1490)
        assertThat(r.warnings).doesNotContain(ParseWarning.TOTAL_UNLABELLED)
    }

    @Test
    fun `label on its own line takes the value from the next line`() {
        val r = parse("Super E10", "SUMME", "82,16 €")
        assertThat(r.totalCents).isEqualTo(8216)
    }

    @Test
    fun `card numbers, phone numbers and postcodes are never the total`() {
        val r = parse(
            "Kiosk am Markt",
            "10115 Berlin",
            "Tel 0301234567",
            "Karte ****1234",
            "Kunde 4711",
            "Kaffee 12,50",
        )
        assertThat(r.totalCents).isEqualTo(1250)
        assertThat(r.warnings).contains(ParseWarning.TOTAL_UNLABELLED)
        assertThat(r.confidence[ReceiptField.TOTAL]).isLessThan(0.5)
    }

    @Test
    fun `dates are not mistaken for amounts`() {
        val r = parse("15.10.2026", "Parken 3,00")
        assertThat(r.totalCents).isEqualTo(300)
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 10, 15))
    }

    @Test
    fun `brand TOTAL heading is the vendor, not the total label`() {
        val r = parse("TOTAL", "Hauptstr. 5", "Super E10", "40,00 l 1,700 EUR/l", "Summe 68,00 EUR")
        assertThat(r.vendor).isEqualTo("TotalEnergies")
        assertThat(r.totalCents).isEqualTo(6800)
    }

    // ------------------------------------------------------------ number formats

    @Test
    fun `thousands separators in both conventions`() {
        assertThat(parse("Gesamtbetrag 1.234,56 €").totalCents).isEqualTo(123456)
        assertThat(parse("TOTAL $1,234.56", locale = Locale.US).totalCents).isEqualTo(123456)
        assertThat(parse("Summe 1234,56 €").totalCents).isEqualTo(123456)
        assertThat(parse("Total 1234.56 EUR").totalCents).isEqualTo(123456)
    }

    @Test
    fun `currency glued to the number is still recognised`() {
        val r = parse("Total EUR45,00")
        assertThat(r.totalCents).isEqualTo(4500)
        assertThat(r.currency).isEqualTo("EUR")
    }

    // ------------------------------------------------------------ volume / price

    @Test
    fun `volume units Ltr, Liter and litres`() {
        assertThat(parse("Menge 45,670 Ltr").volumeLiters).isEqualTo(45.67)
        assertThat(parse("Diesel 45,67 Liter").volumeLiters).isEqualTo(45.67)
        assertThat(parse("12.5 litres", locale = Locale.UK).volumeLiters).isEqualTo(12.5)
    }

    @Test
    fun `price per litre written as pro Liter and as ct per l`() {
        assertThat(parse("Preis 1,799 € pro Liter").pricePerLiterCents).isEqualTo(179.9)
        assertThat(parse("179,9 ct/l").pricePerLiterCents).isEqualTo(179.9)
        assertThat(parse("Preis/L 1,859").pricePerLiterCents).isEqualTo(185.9)
    }

    @Test
    fun `unit price alone is not read as a volume`() {
        val r = parse("1,799 EUR/l")
        assertThat(r.volumeLiters).isNull()
        assertThat(r.pricePerLiterCents).isEqualTo(179.9)
    }

    @Test
    fun `bare Preis on a non-fuel receipt is not a per-litre price`() {
        val r = parse("Waschstraße", "Preis 3,50", "Summe 3,50 €")
        assertThat(r.pricePerLiterCents).isNull()
        assertThat(r.isFuelReceipt).isFalse()
    }

    @Test
    fun `missing unit price is derived from total and volume`() {
        val r = parse("Diesel", "52,30 l", "Summe 89,43 €")
        assertThat(r.pricePerLiterCents).isEqualTo(170.99)
        assertThat(r.pricePerLiterDerived).isTrue()
        assertThat(r.warnings).contains(ParseWarning.PRICE_DERIVED)
        assertThat(r.isFuelReceipt).isTrue()
        assertThat(r.fuelGrade).isEqualTo("Diesel")
    }

    @Test
    fun `inconsistent total versus volume times price is flagged`() {
        val good = parse("45,67 l", "1,799 EUR/l", "Summe 82,16 €")
        val bad = parse("45,67 l", "1,799 EUR/l", "Summe 99,00 €")
        assertThat(bad.warnings).contains(ParseWarning.TOTAL_INCONSISTENT)
        assertThat(good.warnings).doesNotContain(ParseWarning.TOTAL_INCONSISTENT)
        assertThat(bad.confidence[ReceiptField.TOTAL]).isLessThan(good.confidence[ReceiptField.TOTAL])
    }

    @Test
    fun `fuel grade variants`() {
        assertThat(parse("Super Plus 98", "30,00 l").fuelGrade).isEqualTo("Super Plus")
        assertThat(parse("SUPER 95", "30,00 l").fuelGrade).isEqualTo("Super 95")
        assertThat(parse("Super E5", "30,00 l").fuelGrade).isEqualTo("Super E5")
        assertThat(parse("Shell V-Power", "30,00 l").fuelGrade).isEqualTo("V-Power")
        assertThat(parse("PREMIUM", "10.000 GAL", locale = Locale.US).fuelGrade).isEqualTo("Premium")
        assertThat(parse("Unleaded 95", "30.00 L", locale = Locale.UK).fuelGrade).isEqualTo("Unleaded 95")
    }

    // ------------------------------------------------------------ dates and times

    @Test
    fun `date formats ISO, two-digit year and month names`() {
        assertThat(parse("2026-10-15").date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(parse("15.10.26 10:00").date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(parse("15. Oktober 2026").date).isEqualTo(LocalDate.of(2026, 10, 15))
        assertThat(parse("Oct 15, 2026", locale = Locale.US).date).isEqualTo(LocalDate.of(2026, 10, 15))
    }

    @Test
    fun `slash dates resolved by validity before the locale`() {
        assertThat(parse("13/10/2026", locale = Locale.US).date).isEqualTo(LocalDate.of(2026, 10, 13))
        assertThat(parse("10/13/2026", locale = Locale.GERMANY).date).isEqualTo(LocalDate.of(2026, 10, 13))
        assertThat(parse("13/10/2026", locale = Locale.US).warnings).doesNotContain(ParseWarning.DATE_AMBIGUOUS)
    }

    @Test
    fun `ambiguous slash date follows the locale when no currency is printed`() {
        assertThat(parse("05/06/2026", locale = Locale.US).date).isEqualTo(LocalDate.of(2026, 5, 6))
        val de = parse("05/06/2026", locale = Locale.GERMANY)
        assertThat(de.date).isEqualTo(LocalDate.of(2026, 6, 5))
        assertThat(de.warnings).contains(ParseWarning.DATE_AMBIGUOUS)
        assertThat(de.confidence[ReceiptField.DATE]).isLessThan(0.7)
    }

    @Test
    fun `printed currency outranks the locale for date order`() {
        assertThat(parse("05/06/2026", "Total 10,00 €", locale = Locale.US).date).isEqualTo(LocalDate.of(2026, 6, 5))
        assertThat(parse("05/06/2026", "Total $10.00", locale = Locale.GERMANY).date).isEqualTo(LocalDate.of(2026, 5, 6))
    }

    @Test
    fun `invalid dates are rejected`() {
        assertThat(parse("32.13.2026").date).isNull()
        assertThat(parse("31.02.2026").date).isNull()
    }

    @Test
    fun `due date is skipped in favour of the receipt date`() {
        val r = parse("Fällig am 30.10.2026", "Datum 15.10.2026")
        assertThat(r.date).isEqualTo(LocalDate.of(2026, 10, 15))
    }

    @Test
    fun `times with seconds and with Uhr`() {
        assertThat(parse("15.10.2026 14:32:05").time).isEqualTo(LocalTime.of(14, 32, 5))
        assertThat(parse("Zeit 14.32 Uhr").time).isEqualTo(LocalTime.of(14, 32))
        assertThat(parse("Summe 14.32 €").time).isNull()
    }

    // ------------------------------------------------------------ vendor / currency

    @Test
    fun `vendor falls back to first plausible heading, skipping receipt titles`() {
        val r = parse("Kassenbon", "Reifen Schmidt", "Musterweg 3", "Summe 189,50 €")
        assertThat(r.vendor).isEqualTo("Reifen Schmidt")
        assertThat(r.confidence[ReceiptField.VENDOR]).isLessThan(0.9)
    }

    @Test
    fun `known brands found below the header and canonicalised`() {
        assertThat(parse("Willkommen", "bei Ihrer", "CIRCLE K", "Summe 5,00").vendor).isEqualTo("Circle K")
        assertThat(parse("Esso Station Nord").vendor).isEqualTo("Esso")
        assertThat(parse("TotalEnergies Lyon").vendor).isEqualTo("TotalEnergies")
    }

    @Test
    fun `currency codes and symbols`() {
        assertThat(parse("Total CHF 72.50").currency).isEqualTo("CHF")
        assertThat(parse("Total £5.00", locale = Locale.UK).currency).isEqualTo("GBP")
        assertThat(parse("Total 5.00 USD", locale = Locale.US).currency).isEqualTo("USD")
        assertThat(parse("Total 5,00").currency).isNull()
    }

    // ------------------------------------------------------------ positions

    @Test
    fun `labels and values on the same visual row are paired using positions`() {
        val lines = listOf(
            OcrLine("SUMME", 0.95f, centerX = 60f, centerY = 500f, height = 20f),
            OcrLine("MwSt 19%", 0.95f, centerX = 70f, centerY = 540f, height = 20f),
            OcrLine("13,12", 0.95f, centerX = 400f, centerY = 541f, height = 20f),
            OcrLine("82,16", 0.95f, centerX = 400f, centerY = 503f, height = 20f),
        )
        assertThat(parser.parse(lines, Locale.GERMANY).totalCents).isEqualTo(8216)
    }

    @Test
    fun `row pairing works without box heights`() {
        val lines = listOf(
            OcrLine("ARAL", centerX = 200f, centerY = 10f),
            OcrLine("Datum 15.10.2026", centerX = 80f, centerY = 50f),
            OcrLine("MwSt 13,12", centerX = 80f, centerY = 130f),
            OcrLine("Summe", centerX = 60f, centerY = 90f),
            OcrLine("82,16", centerX = 400f, centerY = 92f),
            OcrLine("Super E10", centerX = 60f, centerY = 170f),
        )
        val r = parser.parse(lines, Locale.GERMANY)
        assertThat(r.totalCents).isEqualTo(8216)
        assertThat(r.vendor).isEqualTo("Aral")
    }

    // ------------------------------------------------------------ degenerate input

    @Test
    fun `empty input yields an empty result without crashing`() {
        val r = parser.parse(emptyList(), Locale.GERMANY)
        assertThat(r.isEmpty).isTrue()
        assertThat(r.totalCents).isNull()
        assertThat(r.date).isNull()
        assertThat(r.vendor).isNull()
        assertThat(r.confidence).isEmpty()
        assertThat(r.warnings).containsExactly(ParseWarning.NO_TEXT)
    }

    @Test
    fun `blank lines only behave like empty input`() {
        assertThat(parse("", "   ", "\t").warnings).containsExactly(ParseWarning.NO_TEXT)
    }

    @Test
    fun `garbage text produces no fields`() {
        val r = parse("~~~ ---", "#*#*", ":::")
        assertThat(r.totalCents).isNull()
        assertThat(r.volumeLiters).isNull()
        assertThat(r.date).isNull()
        assertThat(r.vendor).isNull()
    }

    @Test
    fun `receipt with only a total leaves the rest null`() {
        val r = parse("Summe 25,00 €")
        assertThat(r.totalCents).isEqualTo(2500)
        assertThat(r.date).isNull()
        assertThat(r.time).isNull()
        assertThat(r.volumeLiters).isNull()
        assertThat(r.pricePerLiterCents).isNull()
        assertThat(r.fuelGrade).isNull()
    }
}

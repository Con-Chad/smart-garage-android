package io.github.conchad.smartgarage.feature.documents.scan

import java.io.OutputStream
import java.util.Locale

/**
 * A minimal PDF writer for scanned pages (D-A59): each page is one JPEG embedded as-is (`/DCTDecode`) and drawn to
 * fill its page box. `android.graphics.pdf.PdfDocument` re-encodes bitmaps losslessly (Flate), which makes a photo
 * page 5–10× larger than its JPEG; embedding the JPEG keeps a multi-page scan well inside the server's limit.
 * Pure Kotlin, unit-tested.
 */
object JpegPdf {
    /** What the PDF needs from a JPEG's frame header. */
    data class JpegInfo(val width: Int, val height: Int, val components: Int)

    class Page(val jpeg: ByteArray, val widthPt: Int, val heightPt: Int)

    /** Reads width, height and component count from the first SOF marker; null when [jpeg] isn't a usable JPEG. */
    fun info(jpeg: ByteArray): JpegInfo? {
        fun u8(i: Int) = jpeg[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (jpeg.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return null
        var i = 2
        while (i + 3 < jpeg.size) {
            if (u8(i) != 0xFF) return null
            val marker = u8(i + 1)
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
            if (marker == 0xD9 || marker == 0xDA) return null
            val length = u16(i + 2)
            // SOF0..SOF15 except DHT (C4), JPG (C8) and DAC (CC).
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (i + 9 >= jpeg.size) return null
                val info = JpegInfo(width = u16(i + 7), height = u16(i + 5), components = u8(i + 9))
                return info.takeIf { it.width > 0 && it.height > 0 && it.components in setOf(1, 3) }
            }
            i += 2 + length
        }
        return null
    }

    /** Writes [pages] as one PDF with [title] in its info dictionary. Every page's JPEG must pass [info]. */
    fun write(pages: List<Page>, title: String, out: OutputStream) {
        require(pages.isNotEmpty())
        val sink = CountingStream(out)
        val offsets = mutableListOf<Long>()
        fun ascii(text: String) = sink.write(text.toByteArray(Charsets.ISO_8859_1))
        fun begin(id: Int) {
            check(id == offsets.size + 1)
            offsets += sink.count
            ascii("$id 0 obj\n")
        }

        ascii("%PDF-1.4\n%âãÏÓ\n")
        // 1 catalog, 2 page tree, 3 info, then per page: page, image, content stream.
        val firstPage = 4
        begin(1); ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val kids = pages.indices.joinToString(" ") { "${firstPage + 3 * it} 0 R" }
        begin(2); ascii("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>\nendobj\n")
        begin(3); ascii("<< /Title ${pdfText(title)} /Producer (Smart Garage) >>\nendobj\n")
        pages.forEachIndexed { index, page ->
            val info = requireNotNull(info(page.jpeg)) { "page ${index + 1} is not a baseline JPEG" }
            val id = firstPage + 3 * index
            begin(id)
            ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${page.widthPt} ${page.heightPt}] " +
                    "/Resources << /XObject << /Im0 ${id + 1} 0 R >> >> /Contents ${id + 2} 0 R >>\nendobj\n",
            )
            begin(id + 1)
            val space = if (info.components == 1) "/DeviceGray" else "/DeviceRGB"
            ascii(
                "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} /ColorSpace $space " +
                    "/BitsPerComponent 8 /Filter /DCTDecode /Length ${page.jpeg.size} >>\nstream\n",
            )
            sink.write(page.jpeg)
            ascii("\nendstream\nendobj\n")
            val content = "q ${page.widthPt} 0 0 ${page.heightPt} 0 0 cm /Im0 Do Q"
            begin(id + 2)
            ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")
        }
        val xref = sink.count
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { ascii(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        sink.flush()
    }

    /** A PDF text string as UTF-16BE hex with a byte-order mark, so any name (umlauts included) survives. */
    internal fun pdfText(text: String): String {
        val bytes = text.toByteArray(Charsets.UTF_16BE)
        return buildString {
            append("<FEFF")
            bytes.forEach { append(String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF)) }
            append('>')
        }
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
    }
}

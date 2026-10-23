package io.github.conchad.smartgarage.feature.documents.scan

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.ui.files.FileRules
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class JpegPdfTest {
    /** SOI, an APP0 segment, a baseline SOF0 of [w] × [h] with [components], then EOI: enough for the header parser. */
    private fun fakeJpeg(w: Int, h: Int, components: Int = 3, marker: Int = 0xC0): ByteArray {
        val bytes = mutableListOf(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x06, 0x4A, 0x46, 0x49, 0x46)
        bytes += listOf(0xFF, marker, 0x00, 8 + 3 * components, 0x08, h shr 8, h and 0xFF, w shr 8, w and 0xFF, components)
        repeat(components) { bytes += listOf(it + 1, 0x11, 0x00) }
        bytes += listOf(0xFF, 0xD9)
        return ByteArray(bytes.size) { bytes[it].toByte() }
    }

    @Test fun readsTheFrameHeader() {
        assertThat(JpegPdf.info(fakeJpeg(1700, 2200))).isEqualTo(JpegPdf.JpegInfo(1700, 2200, 3))
        assertThat(JpegPdf.info(fakeJpeg(10, 20, components = 1, marker = 0xC2))).isEqualTo(JpegPdf.JpegInfo(10, 20, 1))
        assertThat(JpegPdf.info(byteArrayOf(0x25, 0x50, 0x44, 0x46))).isNull()
        assertThat(JpegPdf.info(fakeJpeg(10, 20, components = 4))).isNull()
    }

    @Test fun writesAPdfTheServerSniffsAndWhoseXrefPointsAtEachObject() {
        val pages = listOf(JpegPdf.Page(fakeJpeg(100, 140), 595, 833), JpegPdf.Page(fakeJpeg(140, 100, components = 1), 833, 595))
        val out = ByteArrayOutputStream()
        JpegPdf.write(pages, "Prüfbericht", out)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)

        assertThat(FileRules.sniffMimeType(bytes.copyOf(1024))).isEqualTo("application/pdf")
        assertThat(text).contains("/Count 2")
        assertThat(text).contains("/Kids [4 0 R 7 0 R]")
        assertThat(text).contains("/MediaBox [0 0 833 595]")
        assertThat(text).contains("/ColorSpace /DeviceGray")
        assertThat(text).contains("/Filter /DCTDecode /Length ${pages[0].jpeg.size}")
        assertThat(text).endsWith("%%EOF\n")

        val xref = text.substringAfterLast("startxref\n").substringBefore('\n').toInt()
        assertThat(text.substring(xref)).startsWith("xref\n0 10\n")
        val entries = text.substring(xref).lines().drop(3).take(9)
        entries.forEachIndexed { i, line ->
            assertThat(line).hasLength(19) // 20 bytes with the newline
            val offset = line.substring(0, 10).toInt()
            assertThat(text.substring(offset)).startsWith("${i + 1} 0 obj\n")
        }
    }

    /** A real encoder's JPEG (ImageIO) passes the header parser; the PDF is left in build/ for a viewer check. */
    @Test fun realJpegsMakeAPdf() {
        fun jpeg(w: Int, h: Int, type: Int): ByteArray {
            val image = BufferedImage(w, h, type)
            val g = image.createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, w, h)
            g.color = java.awt.Color.BLACK
            g.drawString("Smart Garage scan test", 20, 40)
            g.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
        }
        val colour = jpeg(420, 594, BufferedImage.TYPE_INT_RGB)
        val grey = jpeg(594, 420, BufferedImage.TYPE_BYTE_GRAY)
        assertThat(JpegPdf.info(colour)).isEqualTo(JpegPdf.JpegInfo(420, 594, 3))
        assertThat(JpegPdf.info(grey)).isEqualTo(JpegPdf.JpegInfo(594, 420, 1))
        val file = File("build/tmp/jpeg-pdf-test.pdf").apply { parentFile?.mkdirs() }
        file.outputStream().use { out ->
            JpegPdf.write(
                listOf(JpegPdf.Page(colour, 595, 841), JpegPdf.Page(grey, 841, 595)),
                "Prüfbericht (Test)",
                out,
            )
        }
        assertThat(file.length()).isGreaterThan((colour.size + grey.size).toLong())
    }

    @Test fun titleIsUtf16Hex() {
        assertThat(JpegPdf.pdfText("Ü")).isEqualTo("<FEFF00DC>")
    }
}

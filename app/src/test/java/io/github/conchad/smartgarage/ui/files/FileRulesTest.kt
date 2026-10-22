package io.github.conchad.smartgarage.ui.files

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The server's upload rules (lib/storage/paths.ts, sniff.ts), checked on the phone before a file is queued (D-A21). */
class FileRulesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun `sniffs the same signatures as the server`() {
        assertThat(FileRules.sniffMimeType(bytes(0xFF, 0xD8, 0xFF, 0xE0) + ascii("JFIF"))).isEqualTo("image/jpeg")
        assertThat(FileRules.sniffMimeType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).isEqualTo("image/png")
        assertThat(FileRules.sniffMimeType(ascii("GIF89a..."))).isEqualTo("image/gif")
        assertThat(FileRules.sniffMimeType(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WEBPVP8 "))).isEqualTo("image/webp")
        assertThat(FileRules.sniffMimeType(ascii("%PDF-1.7\n"))).isEqualTo("application/pdf")
        // A PDF header within the first KiB still counts, as on the server.
        assertThat(FileRules.sniffMimeType(ascii("﻿   \n%PDF-1.4"))).isEqualTo("application/pdf")
        assertThat(FileRules.sniffMimeType(ascii("hello"))).isNull()
        assertThat(FileRules.sniffMimeType(ByteArray(0))).isNull()
    }

    @Test
    fun `the bytes win over the declared type, and an unknown declared type is dropped`() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertThat(FileRules.effectiveType("image/jpeg", png)).isEqualTo("image/png")
        assertThat(FileRules.effectiveType("IMAGE/JPEG", ascii("junk"))).isEqualTo("image/jpeg")
        assertThat(FileRules.effectiveType("text/plain", ascii("junk"))).isNull()
        assertThat(FileRules.effectiveType(null, ascii("junk"))).isNull()
    }

    @Test
    fun `documents take images and pdf up to 25 MiB, photos images up to 10 MiB`() {
        assertThat(FileRules.check(UploadKind.Document, "application/pdf", 25 * FileRules.MIB)).isNull()
        assertThat(FileRules.check(UploadKind.Document, "application/pdf", 25 * FileRules.MIB + 1)).isEqualTo(FileProblem.TooLarge)
        assertThat(FileRules.check(UploadKind.Document, "text/plain", 10)).isEqualTo(FileProblem.UnsupportedType)
        assertThat(FileRules.check(UploadKind.Document, null, 10)).isEqualTo(FileProblem.UnsupportedType)
        assertThat(FileRules.check(UploadKind.VehiclePhoto, "image/webp", 10 * FileRules.MIB)).isNull()
        assertThat(FileRules.check(UploadKind.VehiclePhoto, "image/jpeg", 10 * FileRules.MIB + 1)).isEqualTo(FileProblem.TooLarge)
        assertThat(FileRules.check(UploadKind.VehiclePhoto, "application/pdf", 10)).isEqualTo(FileProblem.UnsupportedType)
        // The type check comes first: a huge file of the wrong type is reported as the wrong type.
        assertThat(FileRules.check(UploadKind.VehiclePhoto, "application/pdf", 100 * FileRules.MIB)).isEqualTo(FileProblem.UnsupportedType)
    }

    @Test
    fun `the picker is offered exactly the server's types`() {
        assertThat(FileRules.pickerTypes(UploadKind.Document).toSet()).containsExactly("image/jpeg", "image/png", "image/webp", "image/gif", "application/pdf")
        assertThat(FileRules.pickerTypes(UploadKind.VehiclePhoto).toSet()).containsExactly("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}

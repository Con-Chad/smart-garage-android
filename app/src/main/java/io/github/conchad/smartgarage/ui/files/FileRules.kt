package io.github.conchad.smartgarage.ui.files

/** What kind of upload a file is for; each has its own type list and size limit on the server (D-A21). */
enum class UploadKind { Document, VehiclePhoto }

/** Why a picked file can't be queued; the same two reasons the server would answer with 413 and 415. */
enum class FileProblem { TooLarge, UnsupportedType }

/**
 * The server's upload rules (`lib/storage/paths.ts`, `lib/storage/sniff.ts`), checked on the phone before a file is
 * queued, so a file that can only be rejected never sits in the outbox (D-A21). Pure Kotlin, unit-tested.
 */
object FileRules {
    const val MIB: Long = 1024L * 1024L

    /** `MAX_UPLOAD_SIZE_MB` default. */
    const val MAX_DOCUMENT_BYTES: Long = 25 * MIB

    /** `MAX_IMAGE_SIZE_MB` default. */
    const val MAX_IMAGE_BYTES: Long = 10 * MIB

    val IMAGE_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    val DOCUMENT_TYPES: Set<String> = IMAGE_TYPES + "application/pdf"

    /** The MIME types a picker should offer for [kind]. */
    fun pickerTypes(kind: UploadKind): Array<String> = allowedTypes(kind).toTypedArray()

    fun allowedTypes(kind: UploadKind): Set<String> = when (kind) {
        UploadKind.Document -> DOCUMENT_TYPES
        UploadKind.VehiclePhoto -> IMAGE_TYPES
    }

    fun maxBytes(kind: UploadKind): Long = when (kind) {
        UploadKind.Document -> MAX_DOCUMENT_BYTES
        UploadKind.VehiclePhoto -> MAX_IMAGE_BYTES
    }

    /**
     * Same signatures the server checks: the declared type is only trusted when the leading bytes agree. Null for
     * anything else. [head] is the first KiB (or less) of the file.
     */
    fun sniffMimeType(head: ByteArray): String? {
        fun startsWith(signature: ByteArray, offset: Int = 0): Boolean =
            head.size >= offset + signature.size && signature.indices.all { head[offset + it] == signature[it] }
        fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

        if (startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) return "image/jpeg"
        if (startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) return "image/png"
        if (startsWith(ascii("GIF87a")) || startsWith(ascii("GIF89a"))) return "image/gif"
        if (startsWith(ascii("RIFF")) && startsWith(ascii("WEBP"), 8)) return "image/webp"
        val text = String(head, 0, minOf(head.size, 1024), Charsets.ISO_8859_1)
        if ("%PDF-" in text) return "application/pdf"
        return null
    }

    /**
     * The type to send: what the bytes say when they match a known signature, else the declared type when it is
     * one the server accepts (a provider that only reports a wildcard image type still gets a concrete type this way).
     */
    fun effectiveType(declared: String?, head: ByteArray): String? =
        sniffMimeType(head) ?: declared?.lowercase()?.takeIf { it in DOCUMENT_TYPES }

    /** Null when the file can be queued for [kind]. The type check comes first: a huge file of the wrong type is still the wrong type. */
    fun check(kind: UploadKind, mimeType: String?, sizeBytes: Long): FileProblem? = when {
        mimeType == null || mimeType !in allowedTypes(kind) -> FileProblem.UnsupportedType
        sizeBytes > maxBytes(kind) -> FileProblem.TooLarge
        else -> null
    }
}

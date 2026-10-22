package io.github.conchad.smartgarage.ui.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** A file the user picked, with what the content provider says about it and the type its bytes confirm (D-A21). */
data class PickedFile(
    val uri: Uri,
    val name: String,
    /** Null when neither the provider nor the bytes identify it. */
    val mimeType: String?,
    val sizeBytes: Long,
) {
    fun problem(kind: UploadKind): FileProblem? = FileRules.check(kind, mimeType, sizeBytes)
}

/**
 * Reads name, size and type from the content provider (`OpenableColumns`), falls back to the name's extension for
 * the type, and sniffs the first KiB so the type sent is the one the server will see. Null when the URI can't be
 * opened at all.
 */
suspend fun ContentResolver.inspect(uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    var name: String? = null
    var size: Long = -1
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
    }
    val fileName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
    val declared = runCatching { getType(uri) }.getOrNull()
        ?: fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.lowercase()) }
    val head = runCatching { openInputStream(uri)?.use { it.readHead(1024) } }.getOrNull() ?: return@withContext null
    // A provider that doesn't report the size (some cloud providers) gets it counted; the limit check needs a number.
    // Counting stops just past the largest limit: anything bigger is rejected either way, so the exact size of a
    // multi-gigabyte file is never needed and never read.
    if (size < 0) size = runCatching { openInputStream(uri)?.use { it.countBytes(limit = FileRules.MAX_DOCUMENT_BYTES + 1) } }.getOrNull() ?: 0
    PickedFile(uri, fileName, FileRules.effectiveType(declared, head), size)
}

/** Opens the picked file for the writer to stage; the outbox closes it. */
fun ContentResolver.open(file: PickedFile): InputStream? = runCatching { openInputStream(file.uri) }.getOrNull()

private fun InputStream.readHead(max: Int): ByteArray {
    val buffer = ByteArray(max)
    var total = 0
    while (total < max) {
        val n = read(buffer, total, max - total)
        if (n < 0) break
        total += n
    }
    return buffer.copyOf(total)
}

/** Bytes in the stream, or [limit] once at least that many have been read. */
private fun InputStream.countBytes(limit: Long): Long {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (total < limit) {
        val n = read(buffer)
        if (n < 0) return total
        total += n
    }
    return limit
}

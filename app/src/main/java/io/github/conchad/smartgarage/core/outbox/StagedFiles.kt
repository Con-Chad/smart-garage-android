package io.github.conchad.smartgarage.core.outbox

import android.content.Context
import io.github.conchad.smartgarage.core.data.PhotoStore
import io.github.conchad.smartgarage.core.network.InvalidServerIdException
import io.github.conchad.smartgarage.core.network.ServerIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Files waiting to be uploaded (documents, vehicle photos). They live under the app's private `files/` directory,
 * not the cache, so they survive until the upload succeeds or the entry is dismissed (D-A16).
 */
interface StagedFiles {
    /** Copies [content] into the profile's outbox directory and returns the path to store on the entry. */
    suspend fun stage(key: String, content: InputStream): String

    /** The staged bytes, or null when the file is gone. */
    suspend fun read(path: String): ByteArray?

    suspend fun discard(path: String?)

    /**
     * Also keeps a copy where [PhotoStore] looks for [documentId], so the upload opens offline right away.
     * @throws InvalidServerIdException when [documentId] can't be a file name (D-A28).
     */
    suspend fun publish(path: String, documentId: String)

    /**
     * Moves a published copy from a temp document id to the server's id.
     * @throws InvalidServerIdException when either id can't be a file name (D-A28).
     */
    suspend fun rename(documentId: String, newId: String)
}

class OutboxFiles(private val context: Context, private val profileId: String) : StagedFiles {
    private val dir: File get() = dir(context, profileId).apply { mkdirs() }
    private val documents: File get() = PhotoStore.dir(context, profileId).apply { mkdirs() }

    override suspend fun stage(key: String, content: InputStream): String = withContext(Dispatchers.IO) {
        val target = File(dir, key)
        content.use { input -> target.outputStream().use { input.copyTo(it) } }
        target.absolutePath
    }

    override suspend fun read(path: String): ByteArray? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.isFile }?.readBytes()
    }

    override suspend fun discard(path: String?) {
        if (path == null) return
        withContext(Dispatchers.IO) { File(path).delete() }
    }

    override suspend fun publish(path: String, documentId: String) {
        ServerIds.require(documentId)
        withContext(Dispatchers.IO) {
            runCatching { File(path).copyTo(File(documents, documentId), overwrite = true) }
        }
    }

    override suspend fun rename(documentId: String, newId: String) {
        ServerIds.require(documentId)
        ServerIds.require(newId)
        if (documentId == newId) return
        withContext(Dispatchers.IO) {
            val from = File(documents, documentId)
            if (from.exists()) from.renameTo(File(documents, newId))
        }
    }

    companion object {
        fun dir(context: Context, profileId: String) = File(context.filesDir, "outbox/$profileId")
    }
}

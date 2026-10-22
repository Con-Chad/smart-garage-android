package io.github.conchad.smartgarage.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.network.ApiClient
import io.github.conchad.smartgarage.core.network.InvalidServerIdException
import io.github.conchad.smartgarage.core.network.ServerIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vehicle photos and other document files, downloaded through the authenticated API and kept in the app's cache
 * directory. Documents never change once uploaded (a new photo gets a new id), so a cached file is valid forever
 * and works offline. Android may clear the cache under storage pressure; files are fetched again then.
 */
@Singleton
class PhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiClient,
    private val profiles: ProfileStore,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * The local file for [documentId], downloading it if needed; null when it can't be fetched right now.
     * @throws InvalidServerIdException when [documentId] isn't a plain id and so can't be a file name (D-A28).
     */
    suspend fun file(profileId: String, documentId: String): File? = withContext(Dispatchers.IO) {
        ServerIds.require(documentId)
        val target = File(dir(profileId), documentId)
        if (target.exists()) return@withContext target
        locks.getOrPut("$profileId/$documentId") { Mutex() }.withLock {
            if (target.exists()) return@withLock target
            val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return@withLock null
            val bytes = api.documentContent(connection, documentId) ?: return@withLock null
            val partial = File(target.parentFile, "$documentId.part")
            partial.writeBytes(bytes)
            partial.renameTo(target)
            target
        }
    }

    private fun dir(profileId: String) = dir(context, profileId).apply { mkdirs() }

    companion object {
        /** Where a profile's downloaded files live; staged uploads are published here too (D-A16). */
        fun dir(context: Context, profileId: String) = File(context.cacheDir, "documents/$profileId")
    }
}

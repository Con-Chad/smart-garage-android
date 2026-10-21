package io.github.conchad.smartgarage.core.cache

import android.content.Context
import androidx.room.Room
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.conchad.smartgarage.core.data.ProfileDataCleaner
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One cache database per server profile (D-A02), opened lazily and kept open. */
@Singleton
class CacheProvider @Inject constructor(@ApplicationContext private val context: Context) : ProfileDataCleaner {
    private val open = mutableMapOf<String, CacheDatabase>()

    @Synchronized
    fun dao(profileId: String): CacheDao = open.getOrPut(profileId) {
        Room.databaseBuilder(context, CacheDatabase::class.java, fileName(profileId))
            // It's a cache: a schema change just means syncing again.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }.dao()

    /**
     * Wipes a profile's cached data, e.g. on sign-out or a revoked token (D-A12). The tables are emptied rather than
     * the file deleted: screens may still be observing the database while the UI switches away.
     */
    override suspend fun clear(profileId: String) {
        dao(profileId).run {
            clearRows()
            clearMeta()
        }
        // Downloaded photos and documents (PhotoStore) go with the rest of the profile's data.
        File(context.cacheDir, "documents/$profileId").deleteRecursively()
    }

    /** Deletes cache files of profiles that no longer exist. Call before any of them is opened. */
    @Synchronized
    fun deleteOrphans(profileIds: Set<String>) {
        context.databaseList()
            .filter { it.startsWith(PREFIX) && it.endsWith(".db") }
            .map { it.removePrefix(PREFIX).removeSuffix(".db") }
            .filter { it !in profileIds && it !in open }
            .forEach { context.deleteDatabase(fileName(it)) }
    }

    private fun fileName(profileId: String) = "$PREFIX$profileId.db"

    private companion object {
        const val PREFIX = "cache-"
    }
}

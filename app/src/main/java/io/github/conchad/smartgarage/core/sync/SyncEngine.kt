package io.github.conchad.smartgarage.core.sync

import io.github.conchad.smartgarage.core.cache.CacheProvider
import io.github.conchad.smartgarage.core.cache.CachedRow
import io.github.conchad.smartgarage.core.cache.MetaEntry
import io.github.conchad.smartgarage.core.data.AuthRepository
import io.github.conchad.smartgarage.core.data.DisplayPreferences
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.network.ApiClient
import io.github.conchad.smartgarage.core.network.ApiResult
import io.github.conchad.smartgarage.core.network.ServerConnection
import io.github.conchad.smartgarage.core.network.ServerProblem
import io.github.conchad.smartgarage.core.network.SyncPageDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncOutcome {
    data class Success(val upserted: Int, val deleted: Int, val fullResync: Boolean) : SyncOutcome
    data class Failed(val problem: ServerProblem) : SyncOutcome

    /** The token was rejected; the session and cache were cleared. */
    data object SignedOut : SyncOutcome

    data object NotSignedIn : SyncOutcome
}

/** What the UI shows about syncing for one profile. */
data class SyncStatus(val running: Boolean = false, val lastProblem: ServerProblem? = null)

/** Where a sync session lands. The real one is the profile's Room cache; tests use an in-memory fake. */
interface SyncStore {
    suspend fun nextSince(): String?
    suspend fun apply(fullResync: Boolean, upserts: List<CachedRow>, deletions: Map<String, List<String>>, meta: List<MetaEntry>)
}

/**
 * Pulls `/api/v1/sync` into a profile's cache (D-028, D-A12).
 *
 * All pages of a session are collected first and applied in one transaction, so a dropped connection halfway leaves
 * the previous cache intact. A personal garage is hundreds of rows, so holding a session in memory is cheap.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: ApiClient,
    private val profiles: ProfileStore,
    private val auth: AuthRepository,
    private val cache: CacheProvider,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val _status = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    val status: StateFlow<Map<String, SyncStatus>> = _status.asStateFlow()

    suspend fun sync(profileId: String): SyncOutcome {
        // One sync per profile at a time; a second caller waits and then runs a cheap incremental sync.
        return locks.getOrPut(profileId) { Mutex() }.withLock {
            setStatus(profileId) { it.copy(running = true) }
            var problem: ServerProblem? = null
            try {
                syncLocked(profileId).also { problem = (it as? SyncOutcome.Failed)?.problem }
            } finally {
                setStatus(profileId) { SyncStatus(running = false, lastProblem = problem) }
            }
        }
    }

    private suspend fun syncLocked(profileId: String): SyncOutcome {
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return SyncOutcome.NotSignedIn
        val store = RoomSyncStore(cache, profileId)
        val outcome = pull(api, connection, store) { prefs -> applyPreferences(profileId, prefs) }
        if (outcome == SyncOutcome.SignedOut) auth.sessionRejected(profileId)
        return outcome
    }

    private suspend fun applyPreferences(profileId: String, prefs: DisplayPreferences) {
        profiles.update(profileId) { it.copy(preferences = prefs) }
    }

    private fun setStatus(profileId: String, transform: (SyncStatus) -> SyncStatus) {
        _status.update { it + (profileId to transform(it[profileId] ?: SyncStatus())) }
    }

    companion object {
        const val META_NEXT_SINCE = "nextSince"
        const val META_LAST_SYNC = "lastSyncAt"
        const val META_NOTE_TAGS = "noteTags"

        /** The paging loop, separate from Android types so it can be unit tested. */
        internal suspend fun pull(
            api: ApiClient,
            connection: ServerConnection,
            store: SyncStore,
            now: () -> Instant = Instant::now,
            onPreferences: suspend (DisplayPreferences) -> Unit = {},
        ): SyncOutcome {
            val since = store.nextSince()
            val upserts = LinkedHashMap<Pair<String, String>, CachedRow>()
            val deletions = mutableMapOf<String, MutableSet<String>>()
            var first: SyncPageDto? = null
            var cursor: String? = null

            while (true) {
                val page = when (val result = api.sync(connection, since = since, cursor = cursor)) {
                    is ApiResult.Ok -> result.value
                    is ApiResult.HttpError -> return when (result.status) {
                        401 -> SyncOutcome.SignedOut
                        else -> SyncOutcome.Failed(ServerProblem.Unexpected(result.status, result.message))
                    }
                    is ApiResult.Failed -> return SyncOutcome.Failed(result.problem)
                }
                if (first == null) first = page
                page.changes.forEach { (resource, rows) ->
                    rows.forEach { json -> json.toCachedRow(resource)?.let { upserts[resource to it.id] = it } }
                }
                page.deletions.forEach { (resource, ids) -> deletions.getOrPut(resource) { mutableSetOf() } += ids }
                if (!page.hasMore) {
                    val head = first
                    val meta = buildList {
                        add(MetaEntry(META_NEXT_SINCE, page.nextSince ?: page.serverTime))
                        add(MetaEntry(META_LAST_SYNC, now().toString()))
                        head.noteTags?.let { add(MetaEntry(META_NOTE_TAGS, it.toString())) }
                    }
                    // A row deleted after it changed in the same session must not come back.
                    deletions.forEach { (resource, ids) -> ids.forEach { upserts.remove(resource to it) } }
                    store.apply(
                        fullResync = head.fullResync,
                        upserts = upserts.values.toList(),
                        deletions = deletions.mapValues { it.value.toList() },
                        meta = meta,
                    )
                    head.preferences?.let {
                        onPreferences(DisplayPreferences(it.locale, it.timezone, it.currency, it.distanceUnit, it.volumeUnit))
                    }
                    return SyncOutcome.Success(upserts.size, deletions.values.sumOf { it.size }, head.fullResync)
                }
                cursor = page.cursor ?: return SyncOutcome.Failed(ServerProblem.Unexpected(200, "missing cursor"))
            }
        }

        private fun JsonObject.toCachedRow(resource: String): CachedRow? {
            val id = this["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val vehicleId = if (resource == "vehicles") id else this["vehicleId"]?.jsonPrimitive?.contentOrNull
            return CachedRow(
                resource = resource,
                id = id,
                vehicleId = vehicleId,
                updatedAt = this["updatedAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                json = toString(),
            )
        }
    }
}

private class RoomSyncStore(private val cache: CacheProvider, private val profileId: String) : SyncStore {
    override suspend fun nextSince(): String? = cache.dao(profileId).meta(SyncEngine.META_NEXT_SINCE)

    override suspend fun apply(
        fullResync: Boolean,
        upserts: List<CachedRow>,
        deletions: Map<String, List<String>>,
        meta: List<MetaEntry>,
    ) = cache.dao(profileId).applySync(fullResync, upserts, deletions, meta)
}

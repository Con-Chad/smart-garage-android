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
import io.github.conchad.smartgarage.core.outbox.GarageStore
import io.github.conchad.smartgarage.core.outbox.OutboxReplay
import io.github.conchad.smartgarage.core.outbox.OutboxStores
import io.github.conchad.smartgarage.core.outbox.ReplayOutcome
import io.github.conchad.smartgarage.core.outbox.ServerRows
import io.github.conchad.smartgarage.core.outbox.StagedFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncOutcome {
    /** [rejected] counts rows the server sent that were dropped for an unusable id (D-A28); the sync still succeeds. */
    data class Success(val upserted: Int, val deleted: Int, val fullResync: Boolean, val rejected: Int = 0) : SyncOutcome
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
 * Sends the outbox and pulls `/api/v1/sync` into a profile's cache (D-028, D-A12, D-A16).
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
    private val stores: OutboxStores,
    private val scope: CoroutineScope,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    /** Profiles with a sync requested through [syncSoon]; true when one more run should follow the current one. */
    private val kicks = ConcurrentHashMap<String, Boolean>()
    private val _status = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    val status: StateFlow<Map<String, SyncStatus>> = _status.asStateFlow()

    suspend fun sync(profileId: String): SyncOutcome = withContext(Dispatchers.Default) {
        // Off the caller's dispatcher: ViewModels call this from Main, and a session decrypts the token (Keystore),
        // parses every page and serialises each row back to JSON for the cache.
        // One sync per profile at a time; a second caller waits and then runs a cheap incremental sync.
        locks.getOrPut(profileId) { Mutex() }.withLock {
            setStatus(profileId) { it.copy(running = true) }
            var problem: ServerProblem? = null
            try {
                syncLocked(profileId) { problem = it }.also { (it as? SyncOutcome.Failed)?.let { failed -> problem = failed.problem } }
            } finally {
                setStatus(profileId) { SyncStatus(running = false, lastProblem = problem) }
            }
        }
    }

    /**
     * [sync], unless the profile synced successfully within [maxAge] and has nothing queued, in which case nothing
     * runs and null is returned. For the hourly worker (D-A13): the foreground sync on every return to the app
     * usually has the cache fresh already, and an incremental pull that returns nothing still costs a request, a
     * Keystore decrypt and a database pass. A queued write always syncs, so the worker remains the path that sends
     * writes made while offline.
     */
    suspend fun syncIfStale(profileId: String, maxAge: Duration, now: Instant = Instant.now()): SyncOutcome? = withContext(Dispatchers.Default) {
        val dao = cache.dao(profileId)
        val lastSync = dao.meta(META_LAST_SYNC)
        val queued = dao.outbox().isNotEmpty()
        if (needsSync(lastSync, queued, now, maxAge)) sync(profileId) else null
    }

    /**
     * Runs a sync on the application scope without blocking the caller, e.g. right after a write was queued.
     * Requests that arrive while one runs are folded into a single follow-up run, so queuing several writes offline
     * doesn't line up a connection timeout per write.
     */
    fun syncSoon(profileId: String) {
        if (kicks.putIfAbsent(profileId, false) != null) {
            kicks[profileId] = true
            return
        }
        scope.launch {
            while (true) {
                kicks[profileId] = false
                sync(profileId)
                if (kicks.remove(profileId, false)) break
            }
        }
    }

    private suspend fun syncLocked(profileId: String, onPushProblem: (ServerProblem) -> Unit): SyncOutcome {
        val connection = profiles.connection(profileId)?.takeIf { it.token != null } ?: return SyncOutcome.NotSignedIn
        val outcome = session(
            api = api,
            connection = connection,
            store = RoomSyncStore(cache, profileId),
            outbox = stores.store(profileId),
            files = stores.files(profileId),
            onPushProblem = onPushProblem,
        ) { prefs -> applyPreferences(profileId, prefs) }
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

        /** The [syncIfStale] decision: stale, never synced, unparseable, a clock that went backwards, or work queued. */
        internal fun needsSync(lastSyncAt: String?, queued: Boolean, now: Instant, maxAge: Duration): Boolean {
            if (queued) return true
            val last = lastSyncAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
            val age = Duration.between(last, now)
            return age.isNegative || age >= maxAge
        }

        /**
         * One sync session: queued writes go out first, then the pull brings back what they changed server-side
         * (D-A16). When the replay finds the server unreachable the pull is skipped: it would only time out again.
         * Any other reason the replay stopped is reported through [onPushProblem] and the pull still runs.
         */
        internal suspend fun session(
            api: ApiClient,
            connection: ServerConnection,
            store: SyncStore,
            outbox: GarageStore,
            files: StagedFiles,
            now: () -> Instant = Instant::now,
            onPushProblem: (ServerProblem) -> Unit = {},
            onPreferences: suspend (DisplayPreferences) -> Unit = {},
        ): SyncOutcome {
            when (val replay = OutboxReplay(api, connection, outbox, files).run()) {
                ReplayOutcome.SignedOut -> return SyncOutcome.SignedOut
                is ReplayOutcome.Stopped -> {
                    onPushProblem(replay.problem)
                    if (replay.problem is ServerProblem.Unreachable || replay.problem is ServerProblem.GatewayError) {
                        return SyncOutcome.Failed(replay.problem)
                    }
                }
                ReplayOutcome.Done -> Unit
            }
            return pull(api, connection, store, now, onPreferences)
        }

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
            var rejected = 0

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
                    rows.forEach { json ->
                        val row = ServerRows.toCachedRow(resource, json)
                        if (row == null) rejected++ else upserts[resource to row.id] = row
                    }
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
                    return SyncOutcome.Success(upserts.size, deletions.values.sumOf { it.size }, head.fullResync, rejected)
                }
                cursor = page.cursor ?: return SyncOutcome.Failed(ServerProblem.Unexpected(200, "missing cursor"))
            }
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

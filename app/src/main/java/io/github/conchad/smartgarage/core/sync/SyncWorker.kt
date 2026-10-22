package io.github.conchad.smartgarage.core.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.data.ProfileStore
import io.github.conchad.smartgarage.core.network.ServerProblem
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Hourly background sync of every signed-in profile, so the app opens with fresh data even after days unused.
 * Uses a Hilt entry point instead of hilt-work's worker factory: one fewer dependency for one worker.
 *
 * Battery (D-A13): a profile that synced within [FRESH_FOR] and has nothing queued is skipped, so a run that lands
 * minutes after the user put the phone down (the foreground sync covered it) costs no network, Keystore or
 * database work. Retries back off from ten minutes, not WorkManager's thirty seconds: an origin that is down
 * stays down for a while, and the next hourly run comes anyway.
 *
 * Cleartext (D-A28): a plain-http profile is skipped unless the user turned on `backgroundSyncOnCleartext` for it,
 * so the device token isn't sent to whatever answers at a home-network address while the phone is elsewhere.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun syncEngine(): SyncEngine
        fun profileStore(): ProfileStore
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val state = deps.profileStore().current()
        var retry = false
        for (id in state.signedInIds) {
            val profile = state.profiles.firstOrNull { it.id == id } ?: continue
            if (!profile.syncsInBackground) continue
            val outcome = deps.syncEngine().syncIfStale(id, FRESH_FOR) ?: continue
            // Unreachable is normal away from home for a LAN profile; only retry what may be transient.
            if (outcome is SyncOutcome.Failed && outcome.problem is ServerProblem.GatewayError) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "periodic-sync"

        /** A sync this recent, with an empty outbox, makes the hourly run redundant. */
        private val FRESH_FOR: Duration = Duration.ofMinutes(15)

        /**
         * Called once per process start. `UPDATE` keeps the existing request's period and next run time (so an app
         * start never resets the hourly cadence) while applying any changed constraints or backoff.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

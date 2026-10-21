package io.github.conchad.smartgarage.core.sync

import android.content.Context
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
import java.util.concurrent.TimeUnit

/**
 * Hourly background sync of every signed-in profile, so the app opens with fresh data even after days unused.
 * Uses a Hilt entry point instead of hilt-work's worker factory: one fewer dependency for one worker.
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
            val outcome = deps.syncEngine().sync(id)
            // Unreachable is normal away from home for a LAN profile; only retry what may be transient.
            if (outcome is SyncOutcome.Failed && outcome.problem is ServerProblem.GatewayError) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "periodic-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

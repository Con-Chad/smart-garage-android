package io.github.conchad.smartgarage.core.obd.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.conchad.smartgarage.core.obd.ObdController
import java.util.concurrent.TimeUnit

/**
 * Sends queued OBD readings ([ObdUploader]) once a suitable network is there (D-A70). One unique job per profile;
 * re-scheduling replaces it, so a changed "cellular uploads" setting takes effect at once. Its network constraint is
 * its own: the hourly [io.github.conchad.smartgarage.core.sync.SyncWorker] keeps CONNECTED and never sees a reading
 * that hasn't been handed to the outbox yet.
 */
class ObdUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun obdController(): ObdController
    }

    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE) ?: return Result.success()
        val controller = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).obdController()
        return when (controller.upload(profileId)) {
            ObdUploader.Result.DONE -> Result.success()
            ObdUploader.Result.RETRY -> Result.retry()
        }
    }

    companion object {
        private const val KEY_PROFILE = "profileId"

        fun name(profileId: String) = "obd-upload-$profileId"

        /** Unmetered only when [cellular] is off; else any connection. */
        fun constraints(cellular: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()

        fun schedule(context: Context, profileId: String, cellular: Boolean) {
            val request = OneTimeWorkRequestBuilder<ObdUploadWorker>()
                .setConstraints(constraints(cellular))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_PROFILE to profileId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(profileId), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, profileId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(profileId))
        }
    }
}

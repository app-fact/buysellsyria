package com.buysellsyria.rep.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.buysellsyria.rep.RepApp
import com.buysellsyria.rep.data.SyncState
import java.util.concurrent.TimeUnit

/** Runs a sync whenever the phone is online: periodically and right after each new document. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = (applicationContext as RepApp).repo
        if (repo.session.token == null) return Result.success()
        return when (repo.sync()) {
            is SyncState.Failed -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Sync as soon as there is network (immediately if online). */
        fun syncSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelAll(context: Context) = WorkManager.getInstance(context).cancelAllWork()
    }
}

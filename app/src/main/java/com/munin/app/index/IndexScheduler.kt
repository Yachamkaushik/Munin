package com.munin.app.index

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object IndexScheduler {
    const val WORK_NAME = "munin-index"

    /**
     * Starts indexing unless a run is already queued or running (KEEP). WorkManager persists the request,
     * so it survives process death and restarts; progress lives in the database, so a resumed run continues
     * with whatever is still PENDING.
     */
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().build())
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun isRunning(context: Context): Flow<Boolean> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
}

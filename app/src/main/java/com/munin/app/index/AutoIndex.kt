package com.munin.app.index

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Index new screenshots and photos soon after they are taken, without any service running in the background.
 *
 * The system's job scheduler watches the photo library for us ([Constraints.Builder.addContentUriTrigger]) and only then starts a short job, which queues the
 * normal indexing run ([IndexScheduler]) and arms the next watch. WorkManager keeps the request across the app being killed and the phone restarting,
 * and indexing itself resumes from the database. It is off until the user switches it on, and needs photo access.
 */
object AutoIndex {
    const val WATCH_NAME = "munin-watch"
    private const val PREFS = "munin_prefs"
    private const val KEY = "auto_index"

    fun isOn(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (on) arm(context, ExistingWorkPolicy.KEEP) else WorkManager.getInstance(context).cancelUniqueWork(WATCH_NAME)
    }

    /** Called when the app starts: a force-stop clears scheduled jobs, so this puts the watch back if the user left it on. */
    fun rearmIfOn(context: Context) {
        if (isOn(context) && MediaAccess.state(context) != MediaAccessState.NONE) arm(context, ExistingWorkPolicy.KEEP)
    }

    /**
     * When the system starts Munin because the library changed, WorkManager's start-up re-schedules the watch job and can swallow that very trigger.
     * So on every start, if the newest image in the library is newer than anything indexed, queue a run now. One cheap query; no run when nothing is new.
     */
    suspend fun catchUp(context: Context, db: com.munin.app.data.MuninDatabase) {
        if (!isOn(context) || MediaAccess.state(context) == MediaAccessState.NONE) return
        val newestInStore = runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf("max(${MediaStore.Images.Media.DATE_ADDED})"), null, null, null,
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull() ?: return
        val newestIndexed = db.items().newestAddedAt() ?: 0L
        if (newestInStore > newestIndexed) IndexScheduler.enqueue(context)
    }

    internal fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<WatchWorker>().setConstraints(
        Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            // a burst of screenshots counts as one change: wait for quiet, but never longer than a minute
            .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .build(),
    ).build()

    internal fun arm(context: Context, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniqueWork(WATCH_NAME, policy, request())
    }
}

/** Runs when the photo library changed: queue indexing (if still allowed) and arm the next watch. */
class WatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!AutoIndex.isOn(ctx)) return Result.success() // switched off while waiting: do not re-arm
        if (MediaAccess.state(ctx) != MediaAccessState.NONE) IndexScheduler.enqueue(ctx)
        // Appended, so the next watch starts once this job has finished (a one-time trigger job cannot re-arm itself while it runs).
        AutoIndex.arm(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }
}

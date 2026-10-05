package com.munin.app.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.munin.app.MuninApp
import com.munin.app.R
import com.munin.app.data.ItemStatus

/** Foreground WorkManager job: scan MediaStore, then index pending items one by one until none are left. */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as MuninApp

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Starting…", 0, 0)

    override suspend fun doWork(): Result {
        val access = MediaAccess.state(applicationContext)
        if (access == MediaAccessState.NONE) return Result.failure()

        try { setForeground(foregroundInfo("Looking for photos…", 0, 0)) } catch (_: Exception) { /* notification not allowed: still index */ }

        val db = app.database
        db.items().retryFailed()
        MediaScanner(applicationContext, db).scan(access)

        app.ocrEngine.warmUp()
        val indexer = Indexer(db, { app.contentResolver.openInputStream(android.net.Uri.parse(it)) ?: error("Cannot open $it") }, app.ocrEngine, app.embedder)
        while (!isStopped) {
            val next = db.items().nextPending() ?: break
            val (done, total) = counts()
            try { setForeground(foregroundInfo(next.displayName, done, total)) } catch (_: Exception) { }
            indexer.process(next)
        }
        return Result.success()
    }

    private suspend fun counts(): Pair<Int, Int> {
        val db = app.database
        val dao = db.items()
        val pending = dao.pendingCount()
        val total = dao.totalCount()
        return (total - pending) to total
    }

    private fun foregroundInfo(current: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Indexing", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_munin)
            .setContentTitle("Munin is indexing your photos")
            .setContentText(if (total > 0) "$done of $total · $current" else current)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private companion object {
        const val CHANNEL_ID = "indexing"
        const val NOTIFICATION_ID = 1
    }
}

package com.munin.app.notifications

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.munin.app.MuninApp
import com.munin.app.data.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The user's choices about keeping notifications. Off until switched on. */
object NotificationSettings {
    private const val PREFS = "munin_prefs"
    private const val KEY = "notification_history"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setEnabled(context: Context, on: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply() }

    /** Whether the user has given Munin "Notification access" in the phone's settings. */
    fun accessGranted(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}

/**
 * Receives notifications only after the user (1) allowed Notification access in the phone's settings and (2) switched on Munin's own "Save notifications" option.
 * Keeps title, text and app name on the phone for 30 days (and at most 5000 notifications), skipping ongoing ones, one-time codes and Munin's own.
 */
class MuninNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!NotificationSettings.enabled(this)) return
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = NotificationFilter.clean(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val text = NotificationFilter.clean((extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString())
        val keep = NotificationFilter.shouldStore(
            sbn.packageName, packageName, sbn.isOngoing, (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0, n.category, title, text,
        )
        if (!keep) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrElse { sbn.packageName } // apps Munin may not see keep their package name
        val db = (application as MuninApp).database
        scope.launch {
            runCatching {
                db.notifications().insert(NotificationEntity(packageName = sbn.packageName, appLabel = label, title = title, text = text, postedAt = sbn.postTime))
                db.notifications().deleteOlderThan(NotificationFilter.cutoff(System.currentTimeMillis()))
                db.notifications().trimTo(NotificationFilter.MAX_ROWS)
            }
        }
    }
}

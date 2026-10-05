package com.munin.app.edge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.munin.app.R
import com.munin.app.launch.SearchLaunch

/**
 * An optional thin handle on the screen edge that opens Munin's search when tapped (and can be dragged up and down). The last fallback for opening
 * Munin from other apps. It needs the user to allow "Display over other apps", runs as a visible foreground service with a notification that has a
 * Turn off button, does no work while idle, and is off until the user switches it on.
 */
object EdgeHandle {
    private const val PREFS = "munin_prefs"
    private const val KEY = "edge_handle"

    fun wanted(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setWanted(context: Context, on: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply() }
    fun canDraw(context: Context) = Settings.canDrawOverlays(context)

    /** Makes the service match what the user asked for. Call only while Munin is in the foreground (Android blocks starting it from the background). */
    fun sync(context: Context) {
        val run = wanted(context) && canDraw(context)
        if (run) runCatching { ContextCompat.startForegroundService(context, Intent(context, EdgeHandleService::class.java)) }
        else context.stopService(Intent(context, EdgeHandleService::class.java))
    }
}

class EdgeHandleService : Service() {
    private var handle: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { EdgeHandle.setWanted(this, false); stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (handle == null) addHandle()
        return START_NOT_STICKY // restarted from inside Munin, not by the system, so a killed handle does not come back unseen
    }

    override fun onDestroy() {
        handle?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        handle = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Edge handle", NotificationManager.IMPORTANCE_LOW))
        val off = PendingIntent.getService(this, 1, Intent(this, EdgeHandleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_munin).setContentTitle("Munin edge handle is on")
            .setContentText("Tap the thin bar at the screen edge to search. Turn it off here.")
            .setContentIntent(SearchLaunch.pending(this)).addAction(0, "Turn off", off).setOngoing(true).setOnlyAlertOnce(true).build()
    }

    private fun addHandle() {
        val wm = getSystemService(WindowManager::class.java)
        val d = resources.displayMetrics
        val widthPx = (28 * d.density).toInt()      // a finger-sized touch target...
        val heightPx = (96 * d.density).toInt()
        val barPx = (8 * d.density).toInt()         // ...with a slim visible bar inside it
        val bar = View(this).apply {
            background = GradientDrawable().apply { setColor(0xB36650A4.toInt()); cornerRadius = barPx.toFloat() }
        }
        val view = FrameLayout(this).apply {
            addView(bar, FrameLayout.LayoutParams(barPx, heightPx, Gravity.END or Gravity.CENTER_VERTICAL))
            contentDescription = "Search Munin"
        }
        val lp = WindowManager.LayoutParams(
            widthPx, heightPx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = HandleGesture.initialTop(d.heightPixels, heightPx) }
        val gesture = HandleGesture(ViewConfiguration.get(this).scaledTouchSlop, d.heightPixels, heightPx)
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { gesture.onDown(e.rawY, lp.y); true }
                MotionEvent.ACTION_MOVE -> { lp.y = gesture.onMove(e.rawY); runCatching { wm.updateViewLayout(view, lp) }; true }
                MotionEvent.ACTION_UP -> { if (gesture.onUp()) startActivity(SearchLaunch.intent(this)); true }
                else -> false
            }
        }
        wm.addView(view, lp)
        handle = view
    }

    private companion object {
        const val CHANNEL_ID = "edge_handle"
        const val NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.munin.app.action.STOP_EDGE_HANDLE"
    }
}

package com.munin.app.launch

import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import com.munin.app.MainActivity
import com.munin.app.R
import com.munin.app.incoming.IncomingParser

/** The intent both entry points use: open Munin on the search screen with the keyboard ready. */
object SearchLaunch {
    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(IncomingParser.ACTION_OPEN_SEARCH).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun pending(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Quick Settings tile: pull down the shade, tap "Search Munin". Does nothing in the background; it only reacts to the tap. */
class SearchTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Search Munin"
            icon = Icon.createWithResource(this@SearchTileService, R.drawable.ic_search_tile)
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        // Android 14 requires the PendingIntent form; older versions only have the Intent form.
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(SearchLaunch.pending(this)) else startActivityAndCollapse(SearchLaunch.intent(this))
    }
}

/** Home-screen widget: one tap target that looks like a search bar. Static, with no update schedule, so it costs no battery. */
class SearchWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        fun views(context: Context): RemoteViews = RemoteViews(context.packageName, R.layout.widget_search).apply {
            setOnClickPendingIntent(R.id.widget_root, SearchLaunch.pending(context))
        }
    }
}

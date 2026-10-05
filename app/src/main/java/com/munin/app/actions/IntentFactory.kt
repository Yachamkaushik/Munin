package com.munin.app.actions

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract

/**
 * Turns a confirmed [ActionPayload] into a standard Android intent. All four open another app's own screen, so none
 * of them needs a permission, and none finishes by itself: the calendar waits for Save, the dialer for the call
 * button, maps just searches, and the share sheet waits for a target.
 */
object IntentFactory {
    fun build(payload: ActionPayload): Intent = when (payload) {
        is ActionPayload.Calendar -> Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, payload.startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, payload.endMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, payload.allDay)
            .putExtra(CalendarContract.Events.TITLE, payload.title)
            .putExtra(CalendarContract.Events.DESCRIPTION, payload.description)
        // ACTION_DIAL only fills in the number; ACTION_CALL (which would dial at once) is never used.
        is ActionPayload.Call -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + payload.number))
        is ActionPayload.Maps -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(payload.query)))
        is ActionPayload.Web -> Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, payload.query)
        is ActionPayload.Share -> Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, payload.text).putExtra(Intent.EXTRA_SUBJECT, payload.subject),
            null,
        )
    }
}

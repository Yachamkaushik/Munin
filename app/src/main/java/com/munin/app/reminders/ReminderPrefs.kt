package com.munin.app.reminders

import android.content.Context

/** Which suggestions the user has dealt with (dismissed, or sent to the calendar). Stored on the phone only; no database change. */
object ReminderPrefs {
    private const val PREFS = "munin_reminders"
    private const val KEY = "handled"

    fun handled(context: Context): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    fun markHandled(context: Context, key: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putStringSet(KEY, HashSet(handled(context)) + key).apply()
    }
}

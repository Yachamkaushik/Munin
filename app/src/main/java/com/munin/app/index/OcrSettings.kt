package com.munin.app.index

import android.content.Context

/** The user's choice about reading Telugu text in images. Stored on the phone only. */
object OcrSettings {
    private const val PREFS = "munin_prefs"
    private const val KEY = "telugu_policy"

    /** What the Telugu switch turns on. Set from the evaluation (docs/EVALUATION.md). */
    val TELUGU_ON = TeluguPolicy.WHEN_DOUBTFUL

    /** The switch's default state: off until the evaluation says otherwise. */
    val DEFAULT = TeluguPolicy.OFF

    fun policy(context: Context): TeluguPolicy =
        runCatching { TeluguPolicy.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT.name)!!) }.getOrDefault(DEFAULT)

    fun setTeluguEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, (if (enabled) TELUGU_ON else TeluguPolicy.OFF).name).apply()
    }
}

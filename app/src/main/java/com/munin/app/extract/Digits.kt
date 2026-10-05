package com.munin.app.extract

/**
 * Maps Devanagari, Telugu and Arabic-Indic digits to ASCII, one character for one character, so regexes can
 * work on ASCII digits while offsets still line up with the original text.
 */
object Digits {
    fun toAscii(s: String): String {
        var changed = false
        val out = CharArray(s.length) {
            val c = s[it]
            val d = when (c) {
                in '०'..'९' -> c - '०'
                in '౦'..'౯' -> c - '౦'
                in '٠'..'٩' -> c - '٠'
                else -> -1
            }
            if (d >= 0) { changed = true; '0' + d } else c
        }
        return if (changed) String(out) else s
    }
}

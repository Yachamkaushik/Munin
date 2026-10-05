package com.munin.app.extract

/**
 * Maps Devanagari, Telugu and Arabic-Indic digits to ASCII, one character for one character, so regexes can
 * work on ASCII digits while offsets still line up with the original text.
 *
 * A run of digits that mixes ASCII with another script ("३180") is left alone: OCR produces those when it reads
 * a rupee sign as a digit-like glyph, and converting the stray glyph would turn ₹180 into 3,180.
 */
object Digits {
    private fun nonAsciiValue(c: Char): Int = when (c) {
        in '\u0966'..'\u096F' -> c - '\u0966'
        in '\u0C66'..'\u0C6F' -> c - '\u0C66'
        in '\u0660'..'\u0669' -> c - '\u0660'
        else -> -1
    }

    /** True for the non-ASCII digits that [toAscii] would convert, and for the stray ones it leaves. */
    fun isForeignDigit(c: Char) = nonAsciiValue(c) >= 0

    fun toAscii(s: String): String {
        if (s.none(::isForeignDigit)) return s
        val out = s.toCharArray()
        var i = 0
        while (i < s.length) {
            if (!(s[i].isAsciiDigit() || isForeignDigit(s[i]))) { i++; continue }
            // a run of digits, allowing grouping separators between them
            var j = i
            while (j < s.length && (s[j].isAsciiDigit() || isForeignDigit(s[j]) || ((s[j] == ',' || s[j] == '.') && j + 1 < s.length && (s[j + 1].isAsciiDigit() || isForeignDigit(s[j + 1]))))) j++
            val run = s.substring(i, j)
            val mixed = run.any { it.isAsciiDigit() } && run.any(::isForeignDigit)
            if (!mixed) for (k in i until j) { val v = nonAsciiValue(s[k]); if (v >= 0) out[k] = '0' + v }
            i = j
        }
        return String(out)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

package com.munin.app.extract

/**
 * Guards against OCR damage inside amounts. The reader (ML Kit's Devanagari model, which also reads Latin) sometimes draws a digit of another script where
 * a Latin 8 or 0 was printed: "Rs 8,000" comes back as "Rs ৪,000" (a Bengali digit that looks like 8), "Rs 2,698" as "Rs 2,69৪". Left alone these give no amount,
 * or a truncated one believed at full confidence ("Rs 2,69৪" became 269). The modes below are the candidates measured in tools/eval/amount.md.
 */
object AmountSanity {
    enum class Mode {
        /** Today's behaviour before this check existed. */
        OFF,
        /** Repair the two look-alike digits seen, and cap the confidence of a repaired amount (it is a guess from damaged text). */
        LOOKALIKE,
        /** LOOKALIKE, plus: a number that still touches an unrepairable foreign digit is read as damaged and not believed. */
        GUARD,
        /** GUARD, plus: a bare amount line starting with 2 or 3 also offers the number without that digit, since a rupee sign is often read as a 2. */
        ALTERNATIVE,
    }

    /** What [FactExtractor.extract] uses. Chosen from the measurement in docs/EVALUATION.md. */
    val DEFAULT = Mode.LOOKALIKE

    /** Confidence of an amount that had to be repaired, of one whose digits look damaged, and of the second candidate in ALTERNATIVE. All below 0.6, so the answer says "check it". */
    const val REPAIRED = 0.55f
    const val DAMAGED = 0.3f
    const val ALTERNATE = 0.4f

    /** Shape look-alikes seen in real reader output: Bengali digit four is drawn like an 8, Bengali zero like a 0. Only what was observed is mapped. */
    private val LOOKALIKE = mapOf('৪' to '8', '০' to '0')

    fun hasLookalike(s: String) = s.any { it in LOOKALIKE }

    /** Bengali digits are not converted by [Digits] (they mean other numbers), but they are digit-like for deciding what a run is. */
    fun isBengaliDigit(c: Char) = c in '০'..'৯'

    /** Result of [repair]: the text (same length, so offsets agree) and whether anything was changed. */
    class Repaired(val text: String, val changed: Boolean)

    /**
     * Replaces look-alike digits that sit in a run with ASCII digits ("৪,000" -> "8,000"), but only in a run that also holds at least one ASCII digit or a
     * look-alike partner ("৪,6০০"); a lone one, or one touching a letter ("\u09E6ct" for "Oct", "Rs\u09EA000"), is left as it is.
     */
    fun repair(line: String): Repaired {
        if (line.none { it in LOOKALIKE }) return Repaired(line, false)
        val out = line.toCharArray()
        var changed = false
        var i = 0
        while (i < line.length) {
            if (!(line[i].isAsciiDigit() || line[i] in LOOKALIKE)) { i++; continue }
            var j = i
            while (j < line.length && (line[j].isAsciiDigit() || line[j] in LOOKALIKE || ((line[j] == ',' || line[j] == '.') && j + 1 < line.length && (line[j + 1].isAsciiDigit() || line[j + 1] in LOOKALIKE)))) j++
            val run = line.substring(i, j)
            val inWord = (i > 0 && line[i - 1].isLetter()) || (j < line.length && line[j].isLetter())
            if (!inWord && run.length > 1) {
                for (k in i until j) LOOKALIKE[line[k]]?.let { out[k] = it; changed = true }
            }
            i = j
        }
        return Repaired(String(out), changed)
    }

    /** True when the character right after a number is a digit of another script: the number was probably cut short by damage. */
    fun touchesForeignDigit(line: String, endExclusive: Int) =
        endExclusive < line.length && (Digits.isForeignDigit(line[endExclusive]) || isBengaliDigit(line[endExclusive]))

    /** "23,045" -> "3045": the amount with its first digit removed, or null if what is left is too short to be an amount. */
    fun withoutFirstDigit(value: String): String? {
        val digits = value.filter(Char::isDigit)
        if (digits.length < 4 || digits.first() !in "23") return null
        return FactExtractor.parseAmount(digits.drop(1))
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}

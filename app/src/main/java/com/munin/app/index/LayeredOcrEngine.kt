package com.munin.app.index

/** When to ask the Telugu-capable recognizer for a second opinion. */
enum class TeluguPolicy {
    /** Never: ML Kit only (the original behaviour). */
    OFF,
    /** Only when ML Kit looks unsure of its own output (low mean confidence, lines it had to drop, or nothing at all). */
    WHEN_DOUBTFUL,
    /** On every image. Most thorough, and the slowest. */
    ALWAYS,
}

object TeluguScript {
    private fun isTelugu(c: Char) = c in '\u0C00'..'\u0C7F'

    fun count(text: String) = text.count(::isTelugu)

    /**
     * Tesseract puts invisible zero-width joiners inside Telugu words ("హాస్టల్\u200C"). Nobody types them, so a keyword search for the plain word would miss,
     * and they make identical words compare unequal. They are removed from OCR output.
     */
    fun normalize(text: String) = text.replace("\u200C", "").replace("\u200D", "")

    /** Telugu letters as a share of all letters; Latin or Devanagari text scores about 0. */
    fun share(text: String): Float {
        val letters = text.count { it.isLetter() }
        return if (letters == 0) 0f else count(text).toFloat() / letters
    }
}

/**
 * Runs the primary recognizer (ML Kit) and, depending on [policy], a second one that can read Telugu. The second result **replaces** the first only
 * when it actually looks like Telugu ([MIN_TELUGU_CHARS] Telugu letters making up at least [MIN_TELUGU_SHARE] of its letters), so a hallucinating
 * Telugu model run over an English receipt cannot overwrite good Latin text. The thresholds were fixed before the evaluation of this feature.
 */
class LayeredOcrEngine(
    private val primary: OcrEngine,
    private val telugu: OcrEngine?,
    private val policy: () -> TeluguPolicy,
) : OcrEngine {
    override suspend fun warmUp() {
        primary.warmUp()
        if (policy() != TeluguPolicy.OFF) telugu?.warmUp()
    }

    override suspend fun recognize(uri: String, rotationDegrees: Int): OcrResult {
        val first = primary.recognize(uri, rotationDegrees)
        val p = policy()
        if (telugu == null || p == TeluguPolicy.OFF) return first
        if (p == TeluguPolicy.WHEN_DOUBTFUL && !doubtful(first)) return first
        val second = telugu.recognize(uri, rotationDegrees)
        return if (looksTelugu(second.text)) second else first
    }

    companion object {
        const val DOUBTFUL_BELOW = 0.75f
        const val MIN_TELUGU_CHARS = 5
        const val MIN_TELUGU_SHARE = 0.2f

        fun doubtful(r: OcrResult) = r.text.isBlank() || r.confidence < DOUBTFUL_BELOW || r.droppedLines > 0

        fun looksTelugu(text: String) = TeluguScript.count(text) >= MIN_TELUGU_CHARS && TeluguScript.share(text) >= MIN_TELUGU_SHARE
    }
}

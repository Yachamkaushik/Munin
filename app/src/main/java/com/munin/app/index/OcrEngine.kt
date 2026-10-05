package com.munin.app.index

/** Text read from one image. [engine] records which recognizer produced it. */
data class OcrResult(val text: String, val engine: String)

/** One recognized line with the recognizer's own confidence (0..1). */
data class OcrLine(val text: String, val confidence: Float)

/**
 * Reads text from an image. Kept behind an interface so another recognizer can be added: ML Kit has no
 * Telugu model, so Telugu text in images is not read today. A Tesseract (`tel`) implementation can slot in here
 * without touching the rest of the pipeline.
 */
interface OcrEngine {
    suspend fun recognize(uri: String, rotationDegrees: Int): OcrResult

    /** Loads models ahead of the first image so one-off start-up cost is not counted as per-item time. */
    suspend fun warmUp() {}
}

object OcrLines {
    /**
     * Lines the recognizer itself is unsure about are mostly noise: an unsupported script (Telugu) comes back as
     * confident-looking gibberish at 0.2-0.5, while real lines scored 0.66+ on the samples tried. The threshold was
     * tuned on a handful of synthetic screenshots, so the evaluation step should re-check it.
     */
    const val MIN_LINE_CONFIDENCE = 0.5f

    fun keep(lines: List<OcrLine>, min: Float = MIN_LINE_CONFIDENCE): List<OcrLine> = lines.filter { it.confidence >= min && it.text.isNotBlank() }

    fun meanConfidence(lines: List<OcrLine>): Float = if (lines.isEmpty()) 0f else lines.map { it.confidence }.average().toFloat()
}

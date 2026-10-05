package com.munin.app.index

/**
 * Splits OCR text into paragraph-sized chunks. A screenshot is normally one chunk; longer text is cut at
 * line boundaries so no chunk exceeds [maxTokens] (E5 reads at most 512, and shorter chunks embed better).
 * [countTokens] is injected so this stays testable without the model.
 */
class Chunker(
    private val maxTokens: Int = 256,
    private val countTokens: (String) -> Int,
) {
    fun split(raw: String): List<String> {
        val lines = raw.lines().map { it.trim().replace(WHITESPACE, " ") }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()

        val chunks = ArrayList<String>()
        val current = StringBuilder()
        var currentTokens = 0

        fun flush() {
            if (current.isNotEmpty()) chunks += current.toString()
            current.clear(); currentTokens = 0
        }

        for (line in lines.flatMap { hardSplit(it) }) {
            val t = countTokens(line)
            if (currentTokens > 0 && currentTokens + t > maxTokens) flush()
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
            currentTokens += t
        }
        flush()
        return chunks
    }

    /** A single line longer than the budget (rare OCR output) is cut on word boundaries. */
    private fun hardSplit(line: String): List<String> {
        if (countTokens(line) <= maxTokens) return listOf(line)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (word in line.split(' ')) {
            if (cur.isNotEmpty() && countTokens("$cur $word") > maxTokens) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(word)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

package com.munin.app.search

/** Text to show for a hit, with the character ranges to highlight. */
data class Snippet(val text: String, val highlights: List<IntRange>)

object Snippets {
    /**
     * Picks the line of [chunkText] that contains the most query [tokens] (plus the next line when short),
     * and marks each match, extended to the end of the word. With no keyword match it shows the first lines.
     */
    fun build(chunkText: String, tokens: List<String>, maxChars: Int = 160): Snippet {
        val lines = chunkText.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return Snippet("", emptyList())
        val scored = lines.mapIndexed { i, l -> i to tokens.count { l.contains(it, ignoreCase = true) } }
        val best = scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first ?: 0
        var text = lines[best]
        if (text.length < maxChars / 2 && best + 1 < lines.size) text += " · " + lines[best + 1]
        if (text.length > maxChars) text = text.take(maxChars - 1) + "…"
        return Snippet(text, highlights(text, tokens))
    }

    fun highlights(text: String, tokens: List<String>): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        for (t in tokens) {
            var from = 0
            while (true) {
                val at = text.indexOf(t, from, ignoreCase = true)
                if (at < 0) break
                var end = at + t.length
                while (end < text.length && (text[end].isLetterOrDigit() || Character.getType(text[end]).toByte() in MARKS)) end++
                ranges += at until end
                from = end
            }
        }
        // merge overlaps so the UI can apply spans without nesting
        val merged = ArrayList<IntRange>()
        for (r in ranges.sortedBy { it.first }) {
            if (merged.isNotEmpty() && r.first <= merged.last().last + 1) merged[merged.size - 1] = merged.last().first..maxOf(merged.last().last, r.last)
            else merged += r
        }
        return merged
    }

    private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK)
}

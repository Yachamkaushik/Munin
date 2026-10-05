package com.munin.app.search

/** Turns a free-text query into keyword-search terms and into FTS match expressions. */
object QueryTerms {
    /** Words too common to help keyword ranking. Deliberately small; the embedding leg handles meaning. */
    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "was", "are", "were", "of", "to", "in", "on", "for", "and", "or", "my", "me", "i",
        "how", "what", "when", "where", "which", "who", "show", "find", "get", "did", "do", "does", "much", "many",
    )

    /** Letters, digits, Indic combining marks and joiners make up a word; everything else separates words. */
    fun tokens(query: String, keepAll: Boolean = false): List<String> {
        val out = LinkedHashSet<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isEmpty()) return
            val t = cur.toString().lowercase()
            cur.setLength(0)
            val asciiOnly = t.all { it.code < 128 }
            if (!keepAll && asciiOnly && (t.length < 2 || t in STOPWORDS)) return
            out += t
        }
        var i = 0
        while (i < query.length) {
            val cp = query.codePointAt(i)
            if (isWordChar(cp)) cur.appendCodePoint(cp) else flush()
            i += Character.charCount(cp)
        }
        flush()
        return out.toList()
    }

    /**
     * FTS4 expression: each term is a quoted prefix phrase and terms are OR-ed, so a chunk matching more of them
     * scores higher under BM25. Prefix matching helps with inflected Telugu and Hindi words.
     */
    fun fts4Match(tokens: List<String>): String? =
        if (tokens.isEmpty()) null else tokens.joinToString(" OR ") { "\"$it*\"" }

    /** FTS5 expression (only used where the SQLite build has FTS5). */
    fun fts5Match(tokens: List<String>): String? =
        if (tokens.isEmpty()) null else tokens.joinToString(" OR ") { "\"$it\" *" }

    private fun isWordChar(cp: Int): Boolean {
        if (Character.isLetterOrDigit(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> cp == 0x200C || cp == 0x200D
        }
    }
}

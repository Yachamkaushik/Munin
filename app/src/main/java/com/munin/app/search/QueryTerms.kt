package com.munin.app.search

/** Turns a free-text query into keyword-search terms and into FTS match expressions. */
object QueryTerms {
    /** Words too common to help keyword ranking. Deliberately small; the embedding leg handles meaning. */
    private val STOPWORDS = setOf(
        "a", "an", "the", "is", "was", "are", "were", "of", "to", "in", "on", "for", "and", "or", "my", "me", "i",
        "how", "what", "when", "where", "which", "who", "show", "find", "get", "did", "do", "does", "much", "many",
    )

    /** True for a Hindi, Telugu or Roman-script function/question word (the same list [tokens] drops when `extended`). */
    fun isFunctionWord(token: String) = token in EXTENDED_STOPWORDS

    /**
     * Function words and question words of Hindi, Telugu and their Roman-script spellings, dropped only when asked for
     * ([extended]). They carry no topic: "का/की/కి/ki" and "कितना/ఎంత/entha" (how much) match half the corpus.
     */
    private val EXTENDED_STOPWORDS = setOf(
        // Hindi
        "का", "की", "के", "को", "से", "में", "पर", "है", "हैं", "था", "थी", "थे", "और", "कि", "क्या", "कब", "कहाँ", "कहां", "कितना", "कितनी", "कितने",
        "मेरा", "मेरी", "मेरे", "मुझे", "दिखाओ", "बताओ", "बताइए", "दिखाइए", "कौन", "यह", "वह", "इस", "उस", "एक", "भी", "तो", "ही", "हो", "लिए", "द्वारा",
        "वाला", "वाली", "आया", "गया", "गई", "हुआ", "करना", "करें", "चाहिए", "किया", "पिछले",
        // Telugu
        "ఎంత", "ఎప్పుడు", "ఏమిటి", "ఏంటి", "ఎక్కడ", "ఏ", "ఈ", "ఆ", "కి", "కు", "లో", "ని", "ను", "యొక్క", "తో", "నా", "నాకు", "నేను", "ఉంది", "ఉన్నాయి", "ఉన్న",
        "చూపించు", "చెప్పు", "గురించి", "లేదా", "మరియు", "అని", "ఇది", "అది", "ఏది", "ఎవరు", "ఎలా", "ఎన్ని", "కూడా", "చేశాను", "కట్టాను",
        // Roman-script Telugu
        "entha", "enta", "enti", "ekkada", "eppudu", "ela", "emiti", "undi", "unnayi", "ki", "ku", "lo", "ni", "nu", "naa", "naaku", "nenu", "chesanu", "cheyali",
        "kattanu", "kattali", "kosam", "gurinchi", "chupinchu", "cheppu", "ante",
        // Roman-script Hindi
        "kitna", "kitni", "kitne", "kab", "kahan", "kya", "ka", "ke", "ko", "mein", "me", "se", "hai", "hain", "tha", "thi", "the", "aur", "mera", "meri", "mujhe",
        "dikhao", "batao", "aaya", "kiya",
    )

    /** Letters, digits, Indic combining marks and joiners make up a word; everything else separates words. */
    fun tokens(query: String, keepAll: Boolean = false, extended: Boolean = false): List<String> {
        val out = LinkedHashSet<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isEmpty()) return
            val t = cur.toString().lowercase()
            cur.setLength(0)
            val asciiOnly = t.all { it.code < 128 }
            if (!keepAll && asciiOnly && (t.length < 2 || t in STOPWORDS)) return
            if (extended && t in EXTENDED_STOPWORDS) return
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

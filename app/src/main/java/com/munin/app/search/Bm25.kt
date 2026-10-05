package com.munin.app.search

import kotlin.math.ln

/**
 * Okapi BM25 from an FTS4 `matchinfo(table, 'pcnalx')` array, for a table with one text column.
 * FTS4 has no built-in `bm25()` (that is FTS5), so ranking is computed here.
 *
 * Layout for c = 1 column: `[p, c, n, avgLen, len, (hitsInRow, hitsInAllRows, rowsWithHit) * p]`.
 */
object Bm25 {
    private const val K1 = 1.2
    private const val B = 0.75

    fun score(info: IntArray): Double {
        val phrases = info[0]
        val rows = info[2].toDouble()
        val avgLen = info[3].toDouble().coerceAtLeast(1.0)
        val len = info[4].toDouble()
        var score = 0.0
        for (i in 0 until phrases) {
            val hits = info[5 + 3 * i].toDouble()
            if (hits == 0.0) continue
            val rowsWithHit = info[5 + 3 * i + 2].toDouble()
            val idf = ln((rows - rowsWithHit + 0.5) / (rowsWithHit + 0.5) + 1.0)
            score += idf * hits * (K1 + 1) / (hits + K1 * (1 - B + B * len / avgLen))
        }
        return score
    }

    /** How many of the query's phrases (words) occur in this row. */
    fun matchedPhrases(info: IntArray): Int = (0 until info[0]).count { info[5 + 3 * it] > 0 }
}

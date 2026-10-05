package com.munin.app.search

import com.munin.app.data.KeywordIndex
import com.munin.app.data.MuninDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BM25 keyword search over `chunk_fts`. */
class KeywordSearcher(private val db: MuninDatabase) {

    /** Top [k] chunks for the query [tokens], best first. Empty when there is nothing to match. */
    fun search(tokens: List<String>, k: Int, minCoverage: Float = 0f): List<ScoredChunk> {
        val sql = db.openHelper.readableDatabase
        return when (db.keywordEngine) {
            KeywordIndex.Engine.FTS4 -> {
                val match = QueryTerms.fts4Match(tokens) ?: return emptyList()
                val scored = ArrayList<ScoredChunk>()
                sql.query(
                    "SELECT rowid, matchinfo(${KeywordIndex.TABLE}, 'pcnalx') FROM ${KeywordIndex.TABLE} WHERE ${KeywordIndex.TABLE} MATCH ?",
                    arrayOf(match),
                ).use { c ->
                    while (c.moveToNext()) {
                        val info = ints(c.getBlob(1))
                        // Coverage gate: a row that matches only a sliver of the query (a lone shared word) is a coincidence, not a match.
                        if (minCoverage > 0f && Bm25.matchedPhrases(info) < required(info[0], minCoverage)) continue
                        scored += ScoredChunk(c.getLong(0), Bm25.score(info).toFloat())
                    }
                }
                scored.sortedByDescending { it.score }.take(k)
            }
            // Android's system SQLite has no FTS5 (checked on API 37), so this branch is untested on a real device.
            KeywordIndex.Engine.FTS5 -> {
                val match = QueryTerms.fts5Match(tokens) ?: return emptyList()
                val out = ArrayList<ScoredChunk>()
                sql.query(
                    "SELECT rowid, -bm25(${KeywordIndex.TABLE}) FROM ${KeywordIndex.TABLE} WHERE ${KeywordIndex.TABLE} MATCH ? ORDER BY bm25(${KeywordIndex.TABLE}) LIMIT $k",
                    arrayOf(match),
                ).use { c -> while (c.moveToNext()) out += ScoredChunk(c.getLong(0), c.getDouble(1).toFloat()) }
                out
            }
        }
    }

    /** matchinfo returns an array of native-endian uint32 values. */
    private fun ints(blob: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder()).asIntBuffer()
        return IntArray(buf.remaining()).also { buf.get(it) }
    }

    companion object {
        /** At least [fraction] of [phrases] words, rounded up, and never fewer than one. */
        internal fun required(phrases: Int, fraction: Float): Int = kotlin.math.ceil(phrases * fraction.toDouble() - 1e-9).toInt().coerceIn(1, maxOf(1, phrases))
    }
}

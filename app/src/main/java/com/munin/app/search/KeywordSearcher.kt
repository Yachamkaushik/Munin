package com.munin.app.search

import com.munin.app.data.KeywordIndex
import com.munin.app.data.MuninDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** BM25 keyword search over `chunk_fts`. */
class KeywordSearcher(private val db: MuninDatabase) {

    /** Top [k] chunks for the query [tokens], best first. Empty when there is nothing to match. */
    fun search(tokens: List<String>, k: Int): List<ScoredChunk> {
        val sql = db.openHelper.readableDatabase
        return when (db.keywordEngine) {
            KeywordIndex.Engine.FTS4 -> {
                val match = QueryTerms.fts4Match(tokens) ?: return emptyList()
                val scored = ArrayList<ScoredChunk>()
                sql.query(
                    "SELECT rowid, matchinfo(${KeywordIndex.TABLE}, 'pcnalx') FROM ${KeywordIndex.TABLE} WHERE ${KeywordIndex.TABLE} MATCH ?",
                    arrayOf(match),
                ).use { c ->
                    while (c.moveToNext()) scored += ScoredChunk(c.getLong(0), Bm25.score(ints(c.getBlob(1))).toFloat())
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
}

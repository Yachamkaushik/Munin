package com.munin.app.search

import com.munin.app.data.MuninDatabase
import com.munin.app.data.VectorCodec
import java.util.PriorityQueue

/**
 * All chunk vectors in one flat array, searched by brute force. Vectors are unit length, so the dot product is
 * the cosine similarity. A few thousand vectors scan in a few milliseconds; past tens of thousands this is where
 * an approximate index would go.
 */
class VectorIndex(private val modelVersion: String) {
    private var ids = LongArray(0)
    private var matrix = FloatArray(0)
    private var signature: Pair<Int, Long>? = null

    val size get() = ids.size

    /** Reloads from the database only when the set of vectors changed. */
    fun refresh(db: MuninDatabase) {
        val sql = db.openHelper.readableDatabase
        val sig = sql.query("SELECT COUNT(*), COALESCE(SUM(chunkId), 0) FROM embeddings WHERE modelVersion = ?", arrayOf(modelVersion))
            .use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }
        if (sig == signature) return
        val newIds = LongArray(sig.first)
        val newMatrix = FloatArray(sig.first * DIM)
        var n = 0
        sql.query("SELECT chunkId, vector FROM embeddings WHERE modelVersion = ? ORDER BY chunkId", arrayOf(modelVersion)).use { c ->
            while (c.moveToNext() && n < newIds.size) {
                val v = VectorCodec.decode(c.getBlob(1))
                if (v.size != DIM) continue
                newIds[n] = c.getLong(0)
                System.arraycopy(v, 0, newMatrix, n * DIM, DIM)
                n++
            }
        }
        ids = if (n == newIds.size) newIds else newIds.copyOf(n)
        matrix = if (n == newIds.size) newMatrix else newMatrix.copyOf(n * DIM)
        signature = sig
    }

    /** Top [k] chunks by cosine similarity, best first. */
    fun search(query: FloatArray, k: Int): List<ScoredChunk> {
        require(query.size == DIM)
        val heap = PriorityQueue<ScoredChunk>(k + 1, compareBy { it.score }) // min-heap of the best k so far
        for (row in ids.indices) {
            var dot = 0f
            val base = row * DIM
            for (d in 0 until DIM) dot += matrix[base + d] * query[d]
            if (heap.size < k) heap += ScoredChunk(ids[row], dot)
            else if (dot > heap.peek().score) { heap.poll(); heap += ScoredChunk(ids[row], dot) }
        }
        return heap.sortedByDescending { it.score }
    }

    private companion object {
        const val DIM = 384
    }
}

data class ScoredChunk(val chunkId: Long, val score: Float)

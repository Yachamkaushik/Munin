package com.munin.app.search

/** Reciprocal rank fusion: each list contributes `1 / (k + rank)` for every id it ranks (rank starts at 1). */
object Rrf {
    const val K = 60

    fun fuse(rankings: List<List<Long>>, k: Int = K): Map<Long, Double> {
        val scores = HashMap<Long, Double>()
        for (ranking in rankings) ranking.forEachIndexed { i, id -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + i + 1) }
        return scores
    }
}

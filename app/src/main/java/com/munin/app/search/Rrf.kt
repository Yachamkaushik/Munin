package com.munin.app.search

/** Reciprocal rank fusion: each list contributes `1 / (k + rank)` for every id it ranks (rank starts at 1). */
object Rrf {
    const val K = 60

    /** [weights] scales each list's contribution (default 1 each); a lower weight makes a list less able to override the others. */
    fun fuse(rankings: List<List<Long>>, k: Int = K, weights: List<Double> = rankings.map { 1.0 }): Map<Long, Double> {
        val scores = HashMap<Long, Double>()
        rankings.forEachIndexed { li, ranking -> ranking.forEachIndexed { i, id -> scores[id] = (scores[id] ?: 0.0) + weights[li] / (k + i + 1) } }
        return scores
    }
}

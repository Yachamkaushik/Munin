package com.munin.app.search

import com.munin.app.data.MuninDatabase

enum class SearchMode { MERGED, MEANING, KEYWORDS }

/** Turns a query into a vector; abstracted so the engine can be tested and timed without the UI. */
interface QueryEmbedder {
    val modelVersion: String
    fun embedQuery(text: String): FloatArray
}

data class SearchResult(
    val itemId: Long,
    val uri: String,
    val displayName: String,
    val chunkId: Long,
    val snippet: Snippet,
    /** 1-based rank of this item's best chunk in each leg, or null when that leg did not return it. */
    val meaningRank: Int?,
    val meaningScore: Float?,
    val keywordRank: Int?,
    val keywordScore: Float?,
    val score: Double,
)

data class SearchTimings(
    val embedMs: Double,
    val meaningMs: Double,
    val keywordMs: Double,
    val fuseMs: Double,
    val totalMs: Double,
    val chunksSearched: Int,
)

data class SearchResponse(val query: String, val mode: SearchMode, val results: List<SearchResult>, val timings: SearchTimings)

/**
 * Hybrid search: cosine similarity over all vectors and BM25 over the keyword table, merged with reciprocal rank
 * fusion. Each leg returns its top [LEG_DEPTH] chunks; results are then grouped so each item appears once, ranked
 * by its best chunk.
 */
class SearchEngine(
    private val db: MuninDatabase,
    private val embedder: QueryEmbedder,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val vectors = VectorIndex(embedder.modelVersion)
    private val keywords = KeywordSearcher(db)

    suspend fun search(query: String, mode: SearchMode = SearchMode.MERGED, limit: Int = 10): SearchResponse {
        val t0 = nanoClock()
        fun ms(from: Long) = (nanoClock() - from) / 1e6
        val trimmed = query.trim()
        val tokens = QueryTerms.tokens(trimmed)

        var embedMs = 0.0
        var meaningMs = 0.0
        var keywordMs = 0.0
        var meaning: List<ScoredChunk> = emptyList()
        var keyword: List<ScoredChunk> = emptyList()

        if (trimmed.isNotEmpty()) {
            if (mode != SearchMode.KEYWORDS) {
                val te = nanoClock()
                val q = embedder.embedQuery(trimmed)
                embedMs = ms(te)
                val tm = nanoClock()
                vectors.refresh(db)
                meaning = vectors.search(q, LEG_DEPTH)
                meaningMs = ms(tm)
            }
            if (mode != SearchMode.MEANING) {
                val tk = nanoClock()
                keyword = keywords.search(tokens, LEG_DEPTH)
                keywordMs = ms(tk)
            }
        }

        val tf = nanoClock()
        val meaningRank = meaning.mapIndexed { i, c -> c.chunkId to (i + 1) }.toMap()
        val keywordRank = keyword.mapIndexed { i, c -> c.chunkId to (i + 1) }.toMap()
        val ordered: List<Pair<Long, Double>> = when (mode) {
            SearchMode.MERGED -> Rrf.fuse(listOf(meaning.map { it.chunkId }, keyword.map { it.chunkId })).entries
                .sortedByDescending { it.value }.map { it.key to it.value }
            SearchMode.MEANING -> meaning.map { it.chunkId to it.score.toDouble() }
            SearchMode.KEYWORDS -> keyword.map { it.chunkId to it.score.toDouble() }
        }
        val fuseMs = ms(tf)

        // Hydrate the best chunks, keeping only the first (best) chunk per item.
        val candidateIds = ordered.take(LEG_DEPTH).map { it.first }
        val rows = if (candidateIds.isEmpty()) emptyMap() else db.chunks().withItems(candidateIds).associateBy { it.chunkId }
        val meaningScores = meaning.associate { it.chunkId to it.score }
        val keywordScores = keyword.associate { it.chunkId to it.score }
        val seen = HashSet<Long>()
        val results = ArrayList<SearchResult>()
        for ((chunkId, score) in ordered) {
            val row = rows[chunkId] ?: continue
            if (!seen.add(row.itemId)) continue
            results += SearchResult(
                row.itemId, row.uri, row.displayName, chunkId, Snippets.build(row.text, tokens),
                meaningRank[chunkId], meaningScores[chunkId], keywordRank[chunkId], keywordScores[chunkId], score,
            )
            if (results.size == limit) break
        }
        return SearchResponse(trimmed, mode, results, SearchTimings(embedMs, meaningMs, keywordMs, fuseMs, ms(t0), vectors.size))
    }

    companion object {
        /** Chunks each leg contributes before fusion. */
        const val LEG_DEPTH = 50
    }
}

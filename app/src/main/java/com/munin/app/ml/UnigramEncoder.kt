package com.munin.app.ml

/**
 * Viterbi segmentation over a SentencePiece Unigram vocabulary, mirroring
 * `Model::EncodeOptimized` in SentencePiece (float scores, first best path wins ties,
 * unknown characters scored at `minScore - 10`).
 */
class UnigramEncoder(model: SentencePieceModel) {
    private val pieceIds = HashMap<String, Int>(model.pieces.size * 2)
    private val pieceScores = FloatArray(model.pieces.size)
    private val maxPieceCodePoints: Int
    private val unkScore: Float
    val unkId: Int

    init {
        require(model.modelType == SentencePieceModel.MODEL_TYPE_UNIGRAM) { "Not a Unigram model" }
        var maxLen = 1
        var minScore = Float.MAX_VALUE
        var unk = 0
        model.pieces.forEachIndexed { id, p ->
            pieceScores[id] = p.score
            when (p.type) {
                SentencePieceModel.TYPE_UNKNOWN -> unk = id
                SentencePieceModel.TYPE_CONTROL -> Unit
                else -> {
                    pieceIds[p.text] = id
                    maxLen = maxOf(maxLen, p.text.codePointCount(0, p.text.length))
                    if (p.score < minScore) minScore = p.score
                }
            }
        }
        maxPieceCodePoints = maxLen
        unkScore = minScore - UNK_PENALTY
        unkId = unk
    }

    /** Segments already-normalized text into SentencePiece ids (unknowns come back as [unkId]). */
    fun encode(normalized: String): IntArray {
        if (normalized.isEmpty()) return IntArray(0)
        val cps = normalized.codePoints().toArray()
        val n = cps.size
        // charStart[i] = UTF-16 index of code point i, so substrings are cheap.
        val charStart = IntArray(n + 1)
        for (i in 0 until n) charStart[i + 1] = charStart[i] + Character.charCount(cps[i])

        val bestScore = FloatArray(n + 1)
        val bestStart = IntArray(n + 1) { -1 }
        val bestId = IntArray(n + 1)
        bestScore[0] = 0f

        for (start in 0 until n) {
            val base = bestScore[start]
            var hasSingle = false
            val maxLen = minOf(maxPieceCodePoints, n - start)
            for (len in 1..maxLen) {
                val id = pieceIds[normalized.substring(charStart[start], charStart[start + len])] ?: continue
                val cand = pieceScores[id] + base
                val end = start + len
                if (bestStart[end] == -1 || cand > bestScore[end]) {
                    bestScore[end] = cand; bestStart[end] = start; bestId[end] = id
                }
                if (len == 1) hasSingle = true
            }
            if (!hasSingle) {
                val cand = unkScore + base
                val end = start + 1
                if (bestStart[end] == -1 || cand > bestScore[end]) {
                    bestScore[end] = cand; bestStart[end] = start; bestId[end] = unkId
                }
            }
        }

        val path = ArrayList<Int>()
        var end = n
        while (end > 0) { path += bestId[end]; end = bestStart[end] }
        path.reverse()
        // SentencePiece collapses a run of unknown characters into one <unk>.
        return path.filterIndexed { i, id -> !(id == unkId && i > 0 && path[i - 1] == unkId) }.toIntArray()
    }

    private companion object {
        const val UNK_PENALTY = 10.0f
    }
}

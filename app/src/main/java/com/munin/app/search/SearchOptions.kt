package com.munin.app.search

/**
 * Switches for the keyword leg and its fusion with the meaning leg. The defaults reproduce the original behaviour so the
 * evaluation harness can compare variants; [RECOMMENDED] is what the app uses.
 *
 * The evaluation (docs/EVALUATION.md) found the keyword leg adds noise across languages: it matched Hindi/Telugu function words and
 * single shared words, and rank fusion trusted those hits as much as strong ones.
 */
data class SearchOptions(
    /** Drop Hindi, Telugu and Roman-script Hindi/Telugu function words and question words from the keyword query. */
    val stopwords: Boolean = false,
    /** A keyword hit must match at least this fraction of the query's content words (0 = no gate; always at least one word). */
    val minKeywordCoverage: Float = 0f,
    /** Weight of the keyword ranking in reciprocal rank fusion (meaning ranking has weight 1). */
    val keywordWeight: Double = 1.0,
) {
    companion object {
        val BASELINE = SearchOptions()
        /**
         * Selected on the dev set by a rule declared in advance (docs/EVALUATION.md): the keyword coverage gate alone. On the held-out set it lifted
         * merged recall@5 from 73% to 87% (perfect text) and 64% to 80% (real OCR), bringing it level with meaning-only search, not past it.
         */
        val RECOMMENDED = SearchOptions(minKeywordCoverage = 0.5f)
    }
}

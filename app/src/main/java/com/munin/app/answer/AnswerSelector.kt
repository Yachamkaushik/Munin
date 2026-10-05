package com.munin.app.answer

import com.munin.app.extract.ExtractedFact
import com.munin.app.extract.FactType
import com.munin.app.search.QueryTerms
import com.munin.app.search.SearchResult

/** Why we are (not) willing to answer from the top search hit. */
enum class ItemEvidence(val score: Float) {
    /** At least half of the question's topic words are whole words in the item, and its meaning ranked first too. */
    STRONG(1.0f),
    /** At least half of the question's topic words are whole words in the item. */
    TEXT_MATCH(0.8f),
    /** Too few shared words, but the meaning score is clearly above the next item's. */
    CLEAR_LEAD(0.6f),
    NONE(0f),
}

data class RankedFact(val fact: ExtractedFact, val score: Float)

/**
 * Picks which fact answers a question and how sure we are. Pure functions, so every rule is unit-testable.
 * The final confidence is half "is this the right item" and half "is this the right value within it".
 */
object AnswerSelector {
    const val MIN_CONFIDENCE = 0.55f
    /** Calibrated on a handful of queries (unrelated ones led by <= 0.029); re-check in the evaluation step. */
    private const val MIN_CLEAR_LEAD = 0.05f
    private const val MIN_COVERAGE = 0.5f
    private const val STEM_MIN = 5
    const val GUESSED_BELOW = 0.6f

    /**
     * Judges the top result against the question. Only the top result is ever answered from; a runner-up is not a
     * safe guess. Word overlap uses *whole words* of the item text, not the search leg's prefix matches, because
     * "car" matching "card" or a single shared word like "fee" must not make an unrelated item look right.
     */
    fun itemEvidence(results: List<SearchResult>, q: Question, itemText: String, ungrounded: Boolean = false): ItemEvidence {
        val top = results.firstOrNull() ?: return ItemEvidence.NONE
        if (ungrounded) return ItemEvidence.NONE
        if (topicCoverage(q.topic, itemText) >= MIN_COVERAGE) return if (top.meaningRank == 1) ItemEvidence.STRONG else ItemEvidence.TEXT_MATCH
        val next = results.getOrNull(1)
        val lead = when {
            next == null -> 1f
            top.meaningScore != null && next.meaningScore != null -> top.meaningScore - next.meaningScore
            else -> 0f
        }
        return if (lead >= MIN_CLEAR_LEAD) ItemEvidence.CLEAR_LEAD else ItemEvidence.NONE
    }

    /** Whether [word] occurs as a whole word (or, for words of 5+ letters, the same stem) in [itemText]. */
    fun hasWord(word: String, itemText: String): Boolean = word in itemWords(itemText) || stemMatch(word, itemWords(itemText))

    private fun itemWords(text: String) = QueryTerms.tokens(text, keepAll = true).toSet()
    private fun stemMatch(t: String, words: Set<String>) = t.length >= STEM_MIN && words.any { w -> w.length >= STEM_MIN && (w.startsWith(t) || t.startsWith(w)) }

    /** Fraction of [topic] words found as whole words (or the same stem, for words of 5+ letters) in [itemText]. */
    fun topicCoverage(topic: List<String>, itemText: String): Float {
        if (topic.isEmpty()) return 0f
        val words = itemWords(itemText)
        return topic.count { t -> t in words || stemMatch(t, words) }.toFloat() / topic.size
    }

    /** The facts of the question's kind, best first. */
    fun rank(facts: List<ExtractedFact>, q: Question): List<RankedFact> =
        facts.filter { it.type == q.kind }
            .map { RankedFact(it, score(it, q)) }
            .sortedWith(compareByDescending<RankedFact> { it.score }.thenBy { it.fact.line })

    /**
     * A value that was itself a guess (extraction confidence under [GUESSED_BELOW], e.g. an amount whose ₹ sign was
     * not read) is only answered when the item is also matched by words, never on a score lead alone.
     */
    fun confidence(best: RankedFact, evidence: ItemEvidence): Float {
        val c = 0.5f * best.score + 0.5f * evidence.score
        return if (best.fact.confidence < GUESSED_BELOW && evidence.score < ItemEvidence.TEXT_MATCH.score) minOf(c, MIN_CONFIDENCE - 0.01f) else c
    }

    private fun score(f: ExtractedFact, q: Question): Float {
        val context = "${f.label.orEmpty()} ${f.raw}".lowercase()
        val labelNorm = (labelScore(q, f.label.orEmpty().lowercase()) + 1f) / 2f
        val overlap = if (q.topic.isEmpty()) 0f else q.topic.count { context.contains(it) }.toFloat() / q.topic.size
        return 0.5f * f.confidence + 0.35f * labelNorm + 0.15f * overlap
    }

    /** -1 (a label that means something else) .. +1 (the label the question is asking for). */
    internal fun labelScore(q: Question, label: String): Float {
        fun has(vararg words: String) = words.any { label.contains(it) }
        fun asked(vararg words: String) = words.any { it in q.terms }
        var s = 0f
        when (q.kind) {
            FactType.AMOUNT -> {
                if (has("amount", "paid", "total", "payable", "fee", "price", "cost", "bill", "charge", "राशि", "रकम", "शुल्क", "कुल", "जमा", "మొత్తం", "ఫీజు", "చెల్లించిన", "ధర")) s += 0.6f
                if (has("due") && asked("due", "pending", "pay")) s += 0.4f
                if (has("paid", "जमा", "చెల్లించిన") && asked("paid", "pay", "payment", "जमा", "చెల్లించ")) s += 0.4f
                if (has("total", "कुल") && asked("total")) s += 0.4f
                if (has("balance", "discount", "fine", "late", "tax", "gst", "cashback", "refund", "limit", "available", "change") && !asked("balance", "discount", "fine", "late", "tax", "gst", "refund")) s -= 1f
            }
            FactType.DATE -> {
                if (has("date", " on", "due", "last", "deadline", "valid", "till", "until", "expiry", "paid", "issued", "collected", "dated", "दिनांक", "तारीख", "तिथि", "తేదీ")) s += 0.5f
                if (has("due", "last", "deadline", "expiry", "valid", "till", "until", "before", "upto") && asked("due", "last", "deadline", "expiry", "expires", "valid", "before")) s += 0.5f
                if (has("paid", "payment", "transaction", " on", "date") && asked("paid", "pay", "payment", "transaction")) s += 0.3f
                if (has("dob", "birth", "born", "जन्म", "పుట్టిన") && !asked("birth", "birthday", "born", "dob")) s -= 1f
                if (has("issued", "printed", "generated") && asked("due", "last", "deadline")) s -= 0.3f
            }
            FactType.PHONE -> if (has("phone", "mobile", "contact", "call", "helpline", "tel", "फोन", "फ़ोन", "मोबाइल", "संपर्क", "ఫోన్", "మొబైల్")) s += 0.7f
            FactType.ADDRESS -> if (label.isNotBlank()) s += 0.5f
        }
        return s.coerceIn(-1f, 1f)
    }
}

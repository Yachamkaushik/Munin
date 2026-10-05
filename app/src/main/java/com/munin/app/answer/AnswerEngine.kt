package com.munin.app.answer

import com.munin.app.data.MuninDatabase
import com.munin.app.extract.ExtractedFact
import com.munin.app.extract.FactExtractor
import com.munin.app.extract.FactType
import com.munin.app.search.SearchResponse
import com.munin.app.search.SearchResult

data class AlternativeValue(val display: String, val label: String?)

/** A value found in the text of [source], ready to show with where it came from. */
data class Answer(
    val kind: FactType,
    /** Shown to the user, e.g. "₹45,000". */
    val display: String,
    /** The exact stored value, e.g. "45000"; what an action (step 5) or the ledger (step 6) would use. */
    val value: String,
    val label: String?,
    /** The text as the OCR read it, e.g. "Rs 45,000". */
    val raw: String,
    val source: SearchResult,
    /** Overall confidence in the answer (right item and right value). */
    val confidence: Float,
    /** The extractor's own confidence in the value alone; low means a guessed amount, a missing year, etc. */
    val factConfidence: Float,
    val alternatives: List<AlternativeValue>,
    /** The item's first line of text, e.g. "Electricity Bill"; used to name calendar events and shares. */
    val itemTitle: String,
    /** Set when the value is less certain than usual, in words the user can act on. */
    val caveat: String?,
)

sealed interface AnswerOutcome {
    /** The query is not asking for a value, so no answer card. */
    data object NotAQuestion : AnswerOutcome
    /** It is a question but we will not guess; the search results are shown instead. */
    data class Declined(val kind: FactType, val reason: String) : AnswerOutcome
    data class Found(val answer: Answer) : AnswerOutcome
}

/**
 * Switches for the answer gate; the default reproduces the original behaviour so the evaluation can compare.
 *
 * [grounding]: every *rare* word of the question (one found in few documents, or none) must appear in the document the answer
 * comes from. The evaluation showed the original gate answering "how much was the car insurance" from a health-insurance premium,
 * because the common-ish word "insurance" matched and the unknown word "car" was simply ignored.
 */
data class AnswerOptions(
    val grounding: Boolean = false,
    val rareDocFrequency: Double = 0.05,
    /** Grounding ignores Hindi/Telugu/Roman function words when picking the question's topic words. */
    val topicFunctionWords: Boolean = false,
    /** Grounding also ignores everyday payment verbs (pay, paid, spend, भरना, కట్టాను ...). */
    val topicGenericWords: Boolean = false,
) {
    companion object {
        val BASELINE = AnswerOptions()
        /**
         * Round 2 (docs/EVALUATION.md): grounding with the question's topic taken without Hindi/Telugu/Roman function words ("था", "కి", "entha"),
         * chosen by a rule written before the fresh set was measured. Against the original gate, on the fresh set it avoided 3 wrong answers
         * (perfect text) / 2 (real OCR) at the cost of 2 correct answers each, and on the earlier sets it removed every made-up answer.
         * Adding a list of generic payment verbs (AnswerOptions.topicGenericWords) changed nothing on any set and is not used.
         */
        val RECOMMENDED = AnswerOptions(grounding = true, topicFunctionWords = true)
    }
}

/**
 * Answers value questions from already-extracted facts: take the top search hit, and if it clearly is the right
 * item and has the requested field, return that value with its source. Otherwise decline rather than guess.
 */
class AnswerEngine(private val db: MuninDatabase, private val options: AnswerOptions = AnswerOptions.RECOMMENDED) {

    suspend fun answer(query: String, response: SearchResponse): AnswerOutcome {
        val q = QuestionParser.parse(query) ?: return AnswerOutcome.NotAQuestion
        val top = response.results.firstOrNull() ?: return AnswerOutcome.Declined(q.kind, "nothing matched")

        val itemText = db.facts().chunkTexts(top.itemId).joinToString("\n")
        val evidence = AnswerSelector.itemEvidence(response.results, q, itemText, ungrounded = options.grounding && hasUngroundedRareWord(q, itemText))
        if (evidence == ItemEvidence.NONE) return AnswerOutcome.Declined(q.kind, "nothing in the text clearly matches what you asked about")

        val facts = db.facts().forItems(listOf(top.itemId), q.kind.name).map {
            ExtractedFact(FactType.valueOf(it.name), it.value, it.raw, it.label, it.lineIndex, it.confidence)
        }
        val ranked = AnswerSelector.rank(facts, q)
        val best = ranked.firstOrNull()
            ?: return AnswerOutcome.Declined(q.kind, "the best match (${top.displayName}) has no ${q.kind.name.lowercase()} in the text that was read")

        val confidence = AnswerSelector.confidence(best, evidence)
        if (confidence < AnswerSelector.MIN_CONFIDENCE) {
            return AnswerOutcome.Declined(q.kind, "not sure enough (%.2f) which value in ${top.displayName} you mean".format(confidence))
        }
        val f = best.fact
        val others = ranked.drop(1).filter { it.fact.value != f.value }.distinctBy { it.fact.value }.take(3)
            .map { AlternativeValue(AnswerFormat.display(q.kind, it.fact.value), it.fact.label) }
        return AnswerOutcome.Found(
            Answer(q.kind, AnswerFormat.display(q.kind, f.value), f.value, f.label, f.raw, top, confidence, f.confidence, others, FactExtractor.lines(itemText).firstOrNull().orEmpty(), caveat(q.kind, f, others.isNotEmpty())),
        )
    }

    /** True when the question names something rare in the collection that this document does not contain. */
    private suspend fun hasUngroundedRareWord(q: Question, itemText: String): Boolean {
        val total = db.chunks().count().coerceAtLeast(1)
        val limit = maxOf(3.0, options.rareDocFrequency * total)
        return QuestionParser.groundingTopic(q, options.topicFunctionWords, options.topicGenericWords).any { word ->
            val docFrequency = runCatching { db.matchCount("\"$word\"") }.getOrDefault(0) // 0 for a word the collection has never seen
            docFrequency <= limit && !AnswerSelector.hasWord(word, itemText)
        }
    }

    private fun caveat(kind: FactType, f: ExtractedFact, hasOthers: Boolean): String? = when {
        kind == FactType.AMOUNT && f.confidence < 0.6f -> "The ₹ sign was not clearly read, so this number is a guess. Check the image."
        kind == FactType.DATE && f.value.startsWith("--") -> "The year was not in the text."
        f.confidence < 0.6f -> "Read with low confidence. Check the image."
        hasOthers -> "Other ${kind.name.lowercase()} values appear in the same item."
        else -> null
    }
}

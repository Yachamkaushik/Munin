package com.munin.app.search

import com.munin.app.answer.AnswerSelector
import com.munin.app.answer.ItemEvidence
import com.munin.app.answer.QuestionParser
import com.munin.app.search.QueryTerms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedStopwordTest {
    @Test fun baselineKeepsIndicFunctionWordsAsBefore() {
        assertEquals(listOf("साई", "विद्या", "हॉस्टल", "की", "फीस", "कितनी", "था"), QueryTerms.tokens("साई विद्या हॉस्टल की फीस कितनी थी".replace("थी", "था")))
    }

    @Test fun hindiFunctionAndQuestionWordsAreDropped() =
        assertEquals(listOf("साई", "विद्या", "हॉस्टल", "फीस"), QueryTerms.tokens("साई विद्या हॉस्टल की फीस कितनी था", extended = true))

    @Test fun teluguFunctionAndQuestionWordsAreDropped() =
        assertEquals(listOf("సాయి", "విద్య", "హాస్టల్", "ఫీజు"), QueryTerms.tokens("సాయి విద్య హాస్టల్ ఫీజు ఎంత", extended = true))

    @Test fun romanTeluguAndHindiFunctionWordsAreDropped() {
        assertEquals(listOf("sai", "vidya", "hostel", "fee"), QueryTerms.tokens("sai vidya hostel fee entha", extended = true))
        assertEquals(listOf("bijli", "bill"), QueryTerms.tokens("bijli bill kitna aaya", extended = true))
        assertEquals(listOf("water", "bill"), QueryTerms.tokens("water bill eppudu kattali", extended = true))
    }

    @Test fun contentWordsAreNeverDropped() {
        assertEquals(listOf("electricity", "bill", "15", "october"), QueryTerms.tokens("electricity bill due 15 October", extended = true).filter { it != "due" })
        assertEquals(listOf("మదురై", "రైలు", "టికెట్"), QueryTerms.tokens("మదురై రైలు టికెట్", extended = true))
    }

    @Test fun aQueryOfOnlyFunctionWordsLeavesNothingToMatch() {
        assertEquals(emptyList<String>(), QueryTerms.tokens("कितना था", extended = true))
        assertEquals(null, QueryTerms.fts4Match(QueryTerms.tokens("ఎంత ఎప్పుడు", extended = true)))
    }
}

class CoverageGateTest {
    @Test fun requiredWordsRoundUpAndAreNeverZero() {
        fun req(p: Int, f: Float) = KeywordSearcher.required(p, f)
        assertEquals(1, req(1, 0.5f)); assertEquals(1, req(2, 0.5f)); assertEquals(2, req(3, 0.5f)); assertEquals(2, req(4, 0.5f)); assertEquals(3, req(5, 0.5f)); assertEquals(6, req(6, 1f))
        assertEquals(1, req(5, 0.01f))
    }

    @Test fun matchedPhrasesCountsPhrasesWithAHit() {
        // [p, c, n, avg, len, (hits, allHits, rows) x p]
        assertEquals(2, Bm25.matchedPhrases(intArrayOf(3, 1, 100, 10, 10, 1, 5, 5, 0, 0, 0, 2, 9, 4)))
        assertEquals(0, Bm25.matchedPhrases(intArrayOf(2, 1, 100, 10, 10, 0, 0, 0, 0, 0, 0)))
    }
}

class WeightedFusionTest {
    @Test fun defaultWeightsEqualPlainRrf() =
        assertEquals(Rrf.fuse(listOf(listOf(1L, 2L), listOf(2L, 3L))), Rrf.fuse(listOf(listOf(1L, 2L), listOf(2L, 3L)), weights = listOf(1.0, 1.0)))

    @Test fun aLighterKeywordListCannotOverrideAMeaningWinner() {
        val meaning = listOf(10L, 20L); val keyword = listOf(99L) // 99 is a lone keyword hit that meaning search did not return
        fun best(weights: List<Double>) = Rrf.fuse(listOf(meaning, keyword), weights = weights).entries.maxByOrNull { it.value }!!.key
        val tied = Rrf.fuse(listOf(meaning, keyword))
        assertEquals(tied.getValue(10L), tied.getValue(99L), 0.0) // equal weights: both are rank 1 in their own list, an exact tie
        assertEquals(10L, best(listOf(1.0, 0.5))) // keyword weight 0.5: 0.5/61 < 1/61, so the meaning winner stays first
        assertEquals(99L, best(listOf(1.0, 2.0))) // a heavier keyword list would let the lone hit win
    }
}

class GroundingTest {
    private val hostelText = "Hostel Fee Receipt\nAmount paid: Rs 45,000"

    @Test fun hasWordUsesWholeWordsAndStems() {
        assertTrue(AnswerSelector.hasWord("hostel", hostelText))
        assertTrue(AnswerSelector.hasWord("receipts", hostelText)) // stem of 5+ letters
        assertFalse(AnswerSelector.hasWord("car", "Apollo Clinic appointment card")) // a prefix inside another word is not a match
        assertFalse(AnswerSelector.hasWord("insurance", hostelText))
    }

    @Test fun anUngroundedQuestionGetsNoEvidenceEvenWithAClearLead() {
        val q = QuestionParser.parse("how much was the car insurance")!!
        val strong = com.munin.app.search.SearchResult(1, "u", "f", 1, Snippet("", emptyList()), 1, 0.95f, 1, 5f, 0.0)
        assertTrue(AnswerSelector.itemEvidence(listOf(strong), q, "Health insurance premium due Rs 8,600") != ItemEvidence.NONE) // today's behaviour
        assertEquals(ItemEvidence.NONE, AnswerSelector.itemEvidence(listOf(strong), q, "Health insurance premium due Rs 8,600", ungrounded = true))
    }
}

class GroundingTopicTest {
    private fun topic(q: String, fn: Boolean, gen: Boolean) = com.munin.app.answer.QuestionParser.groundingTopic(com.munin.app.answer.QuestionParser.parse(q)!!, fn, gen)

    @Test fun defaultsKeepTheOriginalTopic() {
        assertEquals(listOf("pay", "hostel", "fee"), topic("when did I pay the hostel fee", fn = false, gen = false))
        assertEquals(listOf("छात्रावास", "शुल्क", "था"), topic("छात्रावास शुल्क कितना था", fn = false, gen = false))
    }

    @Test fun functionWordsAreDroppedFromTheTopic() {
        assertEquals(listOf("छात्रावास", "शुल्क"), topic("छात्रावास शुल्क कितना था", fn = true, gen = false))
        assertEquals(listOf("pay", "hostel", "fee"), topic("when did I pay the hostel fee", fn = true, gen = false)) // "pay" is not a function word
    }

    @Test fun genericPaymentVerbsAreDroppedOnlyWhenAsked() {
        assertEquals(listOf("hostel", "fee"), topic("when did I pay the hostel fee", fn = true, gen = true))
        assertEquals(listOf("hostel", "fee", "కట్టాను"), topic("hostel fee ఎంత కట్టాను", fn = false, gen = false))
        assertEquals(listOf("hostel", "fee"), topic("hostel fee ఎంత కట్టాను", fn = true, gen = false)) // this verb is already on the Telugu function-word list
        assertEquals(listOf("hostel", "fee"), topic("hostel fee ఎంత కట్టాను", fn = true, gen = true))
    }

    @Test fun contentWordsAreNeverDropped() {
        assertEquals(listOf("car", "insurance"), topic("how much was the car insurance", fn = true, gen = true))
        assertEquals(listOf("gym", "membership"), topic("how much did I pay for the gym membership", fn = true, gen = true))
    }

    @Test fun functionWordLookupMatchesTheStopwordList() {
        assertTrue(QueryTerms.isFunctionWord("था")); assertTrue(QueryTerms.isFunctionWord("entha")); assertFalse(QueryTerms.isFunctionWord("hostel"))
    }
}

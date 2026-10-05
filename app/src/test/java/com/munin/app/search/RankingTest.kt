package com.munin.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Bm25Test {
    // [p, c, n, avgLen, len, hitsInRow, hitsInAllRows, rowsWithHit]
    private fun info(rows: Int, avg: Int, len: Int, hits: Int, rowsWithHit: Int) = intArrayOf(1, 1, rows, avg, len, hits, hits * rowsWithHit, rowsWithHit)

    @Test fun matchesTheTextbookFormula() {
        // idf = ln((100 - 5 + .5)/(5 + .5) + 1); tf = 2*2.2 / (2 + 1.2*(1 - .75 + .75*1))
        val expected = Math.log((100 - 5 + 0.5) / (5 + 0.5) + 1) * (2 * 2.2) / (2 + 1.2)
        assertEquals(expected, Bm25.score(info(100, 10, 10, 2, 5)), 1e-9)
    }

    @Test fun noHitsScoresZero() = assertEquals(0.0, Bm25.score(info(100, 10, 10, 0, 5)), 0.0)

    @Test fun rarerTermsAndShorterRowsScoreHigher() {
        assertTrue(Bm25.score(info(100, 10, 10, 1, 2)) > Bm25.score(info(100, 10, 10, 1, 50)))
        assertTrue(Bm25.score(info(100, 10, 5, 1, 5)) > Bm25.score(info(100, 10, 40, 1, 5)))
    }

    @Test fun phrasesAreSummed() {
        val two = intArrayOf(2, 1, 100, 10, 10, 1, 5, 5, 1, 5, 5)
        assertEquals(2 * Bm25.score(info(100, 10, 10, 1, 5)), Bm25.score(two), 1e-9)
    }
}

class QueryTermsTest {
    @Test fun dropsQuestionWordsButKeepsContentWords() =
        assertEquals(listOf("hostel", "fee"), QueryTerms.tokens("How much was the hostel fee?"))

    @Test fun keepsTeluguAndHindiWordsWhole() {
        assertEquals(listOf("హాస్టల్", "ఫీజు", "రసీదు"), QueryTerms.tokens("హాస్టల్ ఫీజు రసీదు"))
        assertEquals(listOf("छात्रावास", "शुल्क"), QueryTerms.tokens("छात्रावास शुल्क।"))
    }

    @Test fun splitsMixedScriptsAndPunctuation() {
        assertEquals(listOf("hostel", "fee", "రసీదు", "చూపించు"), QueryTerms.tokens("hostel fee రసీదు చూపించు"))
        assertEquals(listOf("rs", "45", "000"), QueryTerms.tokens("Rs.45,000/-"))
    }

    @Test fun dropsSingleAsciiLettersButNotSingleIndicLetters() {
        assertEquals(listOf("ఈ"), QueryTerms.tokens("x ఈ"))
    }

    @Test fun duplicateTermsAppearOnce() = assertEquals(listOf("fee"), QueryTerms.tokens("fee FEE fee"))

    @Test fun matchExpressions() {
        assertEquals("\"hostel*\" OR \"fee*\"", QueryTerms.fts4Match(listOf("hostel", "fee")))
        assertEquals("\"hostel\" * OR \"fee\" *", QueryTerms.fts5Match(listOf("hostel", "fee")))
        assertNull(QueryTerms.fts4Match(QueryTerms.tokens("how much was the")))
    }
}

class RrfTest {
    @Test fun fusesByReciprocalRank() {
        val fused = Rrf.fuse(listOf(listOf(1L, 2L, 3L), listOf(3L, 2L, 9L)))
        assertEquals(listOf(3L, 2L, 1L, 9L), fused.entries.sortedByDescending { it.value }.map { it.key })
        assertEquals(1.0 / 62 + 1.0 / 62, fused.getValue(2L), 1e-12)
    }

    @Test fun anItemInBothListsBeatsATopItemInOne() {
        val fused = Rrf.fuse(listOf(listOf(10L, 20L), listOf(30L, 20L)))
        assertEquals(20L, fused.maxByOrNull { it.value }!!.key)
    }

    @Test fun emptyInputGivesEmptyResult() = assertTrue(Rrf.fuse(listOf(emptyList(), emptyList())).isEmpty())
}

class SnippetsTest {
    private val text = "Hostel Fee Receipt\nStudent: Ravi Kumar\nAmount paid: Rs 45,000\nTransaction ID: 4821937560"

    @Test fun picksTheLineWithTheMostMatchesAndHighlightsWords() {
        val s = Snippets.build(text, listOf("amount", "paid"))
        assertTrue(s.text, s.text.startsWith("Amount paid: Rs 45,000"))
        assertEquals(listOf("Amount", "paid"), s.highlights.map { s.text.substring(it.first, it.last + 1) })
    }

    @Test fun prefixMatchesHighlightTheWholeWord() {
        val s = Snippets.build("Receipts are issued monthly", listOf("receipt"))
        assertEquals(listOf("Receipts"), s.highlights.map { s.text.substring(it.first, it.last + 1) })
    }

    @Test fun withoutAMatchItShowsTheFirstLine() {
        val s = Snippets.build(text, listOf("zzz"))
        assertTrue(s.text.startsWith("Hostel Fee Receipt"))
        assertTrue(s.highlights.isEmpty())
    }

    @Test fun highlightsTeluguWordsIncludingVowelSigns() {
        val s = Snippets.build("హాస్టల్ ఫీజు రసీదు", listOf("ఫీజు"))
        assertEquals(listOf("ఫీజు"), s.highlights.map { s.text.substring(it.first, it.last + 1) })
    }

    @Test fun emptyTextGivesEmptySnippet() = assertEquals(Snippet("", emptyList()), Snippets.build("", listOf("a")))

    @Test fun longSnippetsAreTruncated() = assertTrue(Snippets.build("word ".repeat(100), emptyList()).text.length <= 160)
}

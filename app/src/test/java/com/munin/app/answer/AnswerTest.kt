package com.munin.app.answer

import com.munin.app.extract.ExtractedFact
import com.munin.app.extract.FactType
import com.munin.app.extract.FactType.AMOUNT
import com.munin.app.extract.FactType.DATE
import com.munin.app.extract.FactType.PHONE
import com.munin.app.search.SearchResult
import com.munin.app.search.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionParserTest {
    private fun kind(q: String) = QuestionParser.parse(q)?.kind

    @Test fun englishAmountQuestions() {
        assertEquals(AMOUNT, kind("how much was the hostel fee"))
        assertEquals(AMOUNT, kind("How much is the electricity bill?"))
        assertEquals(AMOUNT, kind("what is the total amount"))
        assertEquals(AMOUNT, kind("what's the price?"))
    }

    @Test fun teluguHindiAndRomanAmountQuestions() {
        assertEquals(AMOUNT, kind("హాస్టల్ ఫీజు ఎంత"))
        assertEquals(AMOUNT, kind("छात्रावास शुल्क कितना था"))
        assertEquals(AMOUNT, kind("hostel fee entha"))
        assertEquals(AMOUNT, kind("bijli bill kitna aaya"))
        assertEquals(AMOUNT, kind("hostel fee ఎంత కట్టాను")) // mixed
    }

    @Test fun dateQuestionsInEveryStyle() {
        assertEquals(DATE, kind("when is the fee due"))
        assertEquals(DATE, kind("college fee last date"))
        assertEquals(DATE, kind("what date did I pay"))
        assertEquals(DATE, kind("ఫీజు చివరి తేదీ ఎప్పుడు"))
        assertEquals(DATE, kind("फीस कब भरनी है"))
        assertEquals(DATE, kind("college fee last date eppudu"))
        assertEquals(DATE, kind("bill kab tak"))
    }

    @Test fun phoneAndAddressQuestions() {
        assertEquals(PHONE, kind("what is the hostel contact number"))
        assertEquals(PHONE, kind("clinic phone number"))
        assertEquals(PHONE, kind("हॉस्टल का फोन नंबर"))
        assertEquals(PHONE, kind("హాస్టల్ ఫోన్ నంబర్"))
        assertEquals(FactType.ADDRESS, kind("clinic address"))
        assertEquals(FactType.ADDRESS, kind("చిరునామా ఏమిటి"))
        assertEquals(FactType.ADDRESS, kind("हॉस्टल का पता"))
    }

    @Test fun plainDescriptionsAreNotQuestions() {
        assertNull(kind("hostel fee receipt"))
        assertNull(kind("హాస్టల్ ఫీజు రసీదు"))
        assertNull(kind("hostel fee receipt ekkada undi")) // "where is it": a file search, not an address question
        assertNull(kind("UPI payment screenshot చూపించు, amount ₹500")) // bare noun without a question marker
        assertNull(kind("flight to hyderabad"))
        assertNull(kind(""))
    }

    @Test fun bareNounsCountOnlyWithAQuestionMarker() {
        assertNull(kind("amount"))
        assertEquals(AMOUNT, kind("amount?"))
        assertEquals(AMOUNT, kind("what is the amount"))
    }

    @Test fun spendingQuestionsBelongToTheLedgerNotHere() {
        assertNull(kind("how much did I spend in September"))
        assertNull(kind("कितना खर्च किया"))
        assertNull(kind("ఈ month ఎంత spend చేశాను"))
    }

    @Test fun theEarlierWordWinsWhenAQueryAsksForTwoThings() {
        assertEquals(AMOUNT, kind("how much and when"))
        assertEquals(DATE, kind("when and how much"))
    }

    @Test fun topicWordsExcludeTheQuestionWords() {
        val q = QuestionParser.parse("how much was the hostel fee")!!
        assertEquals(listOf("hostel", "fee"), q.topic)
        assertTrue("fee" in q.terms)
    }
}

class AnswerSelectorTest {
    private fun fact(type: FactType, value: String, label: String?, conf: Float, line: Int = 0, raw: String = value) = ExtractedFact(type, value, raw, label, line, conf)
    private fun q(text: String) = QuestionParser.parse(text)!!
    private fun result(id: Long, kw: Int?, meaning: Int?, meaningScore: Float?) =
        SearchResult(id, "u$id", "f$id", id, Snippet("", emptyList()), meaning, meaningScore, kw, kw?.let { 1f }, 0.0)

    @Test fun theLabelNamedInTheQuestionWins() {
        val facts = listOf(fact(AMOUNT, "45000", "Fee", 0.9f, 1), fact(AMOUNT, "500", "Late fine", 0.9f, 2), fact(AMOUNT, "1250", "Amount due", 0.7f, 3))
        assertEquals("45000", AnswerSelector.rank(facts, q("how much was the hostel fee")).first().fact.value)
        assertEquals("1250", AnswerSelector.rank(facts, q("how much is due")).first().fact.value)
        assertEquals("500", AnswerSelector.rank(facts, q("how much is the late fine")).first().fact.value)
    }

    @Test fun balanceAndDiscountLinesLoseUnlessAskedFor() {
        val facts = listOf(fact(AMOUNT, "300", "Balance", 0.9f, 1), fact(AMOUNT, "700", "Amount paid", 0.9f, 2))
        assertEquals("700", AnswerSelector.rank(facts, q("how much was paid")).first().fact.value)
        assertEquals("300", AnswerSelector.rank(facts, q("how much is the balance")).first().fact.value)
    }

    @Test fun dueDateBeatsIssueDateForADueQuestionAndBirthDatesNeverWin() {
        val facts = listOf(fact(DATE, "2026-09-01", "Issued on", 0.9f, 1), fact(DATE, "2026-09-30", "Last date", 0.9f, 2), fact(DATE, "2008-01-05", "DOB", 0.9f, 0))
        assertEquals("2026-09-30", AnswerSelector.rank(facts, q("when is the fee due")).first().fact.value)
        assertEquals("2008-01-05", AnswerSelector.rank(facts, q("when is the fee due")).last().fact.value)
    }

    @Test fun otherKindsOfFactAreIgnored() {
        val facts = listOf(fact(DATE, "2026-09-30", "Date", 0.9f), fact(PHONE, "+919876543210", "Phone", 0.9f))
        assertTrue(AnswerSelector.rank(facts, q("how much is the fee")).isEmpty())
        assertEquals(1, AnswerSelector.rank(facts, q("hostel phone number")).size)
    }

    @Test fun anExplicitCurrencyBeatsAGuessedOne() {
        val facts = listOf(fact(AMOUNT, "2499", "Payment successful", 0.45f, 1), fact(AMOUNT, "100", "Cashback", 0.9f, 2))
        assertEquals("2499", AnswerSelector.rank(facts, q("how much was the payment")).first().fact.value)
    }

    private val hostelText = "Hostel Fee Receipt\nAmount paid: Rs 45,000\nPaid on: 12 Sep 2026"

    @Test fun evidenceNeedsTopicWordsInTheItemOrAClearLead() {
        val q = q("how much was the hostel fee") // topic: hostel, fee
        assertEquals(ItemEvidence.STRONG, AnswerSelector.itemEvidence(listOf(result(1, 1, 1, 0.9f), result(2, null, 2, 0.8f)), q, hostelText))
        assertEquals(ItemEvidence.TEXT_MATCH, AnswerSelector.itemEvidence(listOf(result(1, 2, 2, 0.9f), result(2, 1, 1, 0.91f)), q, hostelText))
        assertEquals(ItemEvidence.CLEAR_LEAD, AnswerSelector.itemEvidence(listOf(result(1, null, 1, 0.90f), result(2, null, 2, 0.84f)), q, "Electricity bill"))
        assertEquals(ItemEvidence.NONE, AnswerSelector.itemEvidence(listOf(result(1, null, 1, 0.82f), result(2, null, 2, 0.81f)), q, "Electricity bill"))
        assertEquals(ItemEvidence.CLEAR_LEAD, AnswerSelector.itemEvidence(listOf(result(1, null, 1, 0.82f)), q, "Electricity bill"))
        assertEquals(ItemEvidence.NONE, AnswerSelector.itemEvidence(emptyList(), q, hostelText))
    }

    @Test fun oneSharedWordIsNotEnough() { // "school bus fee" must not be answered from the hostel fee receipt
        val q = q("how much was the school bus fee")
        assertEquals(1f / 3, AnswerSelector.topicCoverage(q.topic, hostelText), 1e-6f)
        assertEquals(ItemEvidence.NONE, AnswerSelector.itemEvidence(listOf(result(1, 1, 1, 0.830f), result(2, null, 2, 0.828f)), q, hostelText))
    }

    @Test fun aPrefixInsideAnotherWordIsNotAMatch() { // "car" must not match "card"
        val q = q("how much was the car insurance")
        assertEquals(0f, AnswerSelector.topicCoverage(q.topic, "Apollo Clinic appointment card"), 0f)
    }

    @Test fun stemsOfFiveOrMoreLettersMatchInflections() {
        assertEquals(1f, AnswerSelector.topicCoverage(listOf("receipt"), "Hostel Fee Receipts"), 0f)
        assertEquals(1f, AnswerSelector.topicCoverage(listOf("receipts"), "Hostel Fee Receipt"), 0f)
        assertEquals(0f, AnswerSelector.topicCoverage(listOf("fee"), "feeling fine"), 0f) // short words need an exact match
    }

    @Test fun coverageWorksForHindiAndTelugu() {
        assertEquals(1f, AnswerSelector.topicCoverage(listOf("छात्रावास", "शुल्क"), "छात्रावास शुल्क रसीद"), 0f)
        assertEquals(1f, AnswerSelector.topicCoverage(listOf("హాస్టల్", "ఫీజు"), "హాస్టల్ ఫీజు రసీదు"), 0f)
        assertEquals(0f, AnswerSelector.topicCoverage(emptyList(), "anything"), 0f)
    }

    @Test fun confidenceMixesRightItemAndRightValue() {
        val best = AnswerSelector.rank(listOf(fact(AMOUNT, "45000", "Amount paid", 0.9f)), q("how much was paid")).first()
        val strong = AnswerSelector.confidence(best, ItemEvidence.STRONG)
        val weak = AnswerSelector.confidence(best, ItemEvidence.CLEAR_LEAD)
        assertTrue(strong > weak)
        assertTrue(strong >= 0.85f)
        assertTrue(weak >= AnswerSelector.MIN_CONFIDENCE)
    }

    @Test fun aGuessedAmountOnAWeakItemIsDeclined() {
        val guessed = AnswerSelector.rank(listOf(fact(AMOUNT, "2499", "Payment successful", 0.45f)), q("how much was the payment")).first()
        assertTrue(AnswerSelector.confidence(guessed, ItemEvidence.CLEAR_LEAD) < AnswerSelector.MIN_CONFIDENCE)
        assertTrue(AnswerSelector.confidence(guessed, ItemEvidence.STRONG) >= AnswerSelector.MIN_CONFIDENCE)
    }
}

class AnswerFormatTest {
    @Test fun indianGroupingForAmounts() {
        assertEquals("₹45,000", AnswerFormat.display(AMOUNT, "45000"))
        assertEquals("₹1,25,000", AnswerFormat.display(AMOUNT, "125000"))
        assertEquals("₹12,50,000", AnswerFormat.display(AMOUNT, "1250000"))
        assertEquals("₹999", AnswerFormat.display(AMOUNT, "999"))
        assertEquals("₹2,499.50", AnswerFormat.display(AMOUNT, "2499.5"))
        assertEquals("₹1,250", AnswerFormat.display(AMOUNT, "1250"))
    }

    @Test fun dates() {
        assertEquals("12 Sep 2026", AnswerFormat.display(DATE, "2026-09-12"))
        assertEquals("20 Sep 2026, 6:45 PM", AnswerFormat.display(DATE, "2026-09-20T18:45"))
        assertEquals("3 Nov (year not read), 6:45 AM", AnswerFormat.display(DATE, "--11-03T06:45"))
    }

    @Test fun phones() {
        assertEquals("+91 98765 43210", AnswerFormat.display(PHONE, "+919876543210"))
        assertEquals("18001234567", AnswerFormat.display(PHONE, "18001234567"))
    }
}

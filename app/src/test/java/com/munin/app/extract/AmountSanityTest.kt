package com.munin.app.extract

import com.munin.app.extract.AmountSanity.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountSanityTest {
    private fun amounts(text: String, mode: Mode = Mode.LOOKALIKE) = FactExtractor.extract(text, mode).filter { it.type == FactType.AMOUNT }

    @Test fun lookalikeDigitsAreRepairedAndFlagged() {
        val a = amounts("Amount paid: Rs ৪,000").single()
        assertEquals("8000", a.value)
        assertEquals("Rs ৪,000", a.raw)               // what was read is kept, so the dialog can show it
        assertEquals(AmountSanity.REPAIRED, a.confidence) // a repair is a guess: below 0.6, so the answer says "check it"
        assertEquals("8600", amounts("Premium due: Rs ৪,6০০").single().value)
        assertEquals("2698", amounts("Amount due: Rs 2,69৪").single().value) // used to be cut short to 269 and believed
        assertEquals("1809", amounts("Amount due: Rs 1,৪০9").single().value) // used to be 1
    }

    @Test fun withoutTheCheckTheseWereMissedOrTruncated() {
        assertTrue(amounts("Amount paid: Rs ৪,000", Mode.OFF).none { it.value == "8000" })
        assertEquals("269", amounts("Amount due: Rs 2,69৪", Mode.OFF).single().value)
    }

    @Test fun cleanAmountsAreUntouched() {
        val a = amounts("Amount paid: Rs 8,000").single()
        assertEquals("8000", a.value); assertEquals(0.9f, a.confidence)
        val b = amounts("देय राशि: ₹1,150").single()
        assertEquals("1150", b.value); assertEquals(0.9f, b.confidence)
    }

    @Test fun aRupeeSignReadAsAHindiDigitIsStillHandled() {
        assertEquals("1150", amounts("देय राशि: २1,150").single().value)
        assertEquals("591", amounts("देय राशि: ३591").single().value)
    }

    @Test fun lookalikesInWordsAndOtherFactsAreLeftAlone() {
        assertEquals(Repaired("Date:28০ct 2026", false), AmountSanity.repair("Date:28০ct 2026").let { Repaired(it.text, it.changed) })
        assertFalse(AmountSanity.repair("০").changed)
        assertTrue(FactExtractor.extract("Date: 5 Aug 2026 Ref ৪12", Mode.LOOKALIKE).none { it.type == FactType.AMOUNT })
    }

    @Test fun repairKeepsTheTextTheSameLengthSoOffsetsAgree() {
        val r = AmountSanity.repair("Rs ৪,6০০ and ৪")
        assertEquals("Rs 8,600 and ৪", r.text)
        assertEquals("Rs ৪,6০০ and ৪".length, r.text.length)
        assertTrue(r.changed)
    }

    @Test fun guardMarksANumberCutShortByAnUnrepairableDigit() {
        val txt = "Amount due: Rs 2,69৫"   // Bengali five: not a look-alike we repair
        assertEquals(0.9f, amounts(txt, Mode.LOOKALIKE).single().confidence)
        assertEquals(AmountSanity.DAMAGED, amounts(txt, Mode.GUARD).single().confidence)
    }

    @Test fun alternativeOffersTheNumberWithoutALeadingTwoOrThree() {
        val got = amounts("Payment Successful\n23,045\nOct 2, 2026", Mode.ALTERNATIVE)
        assertEquals(listOf("23045", "3045"), got.map { it.value })
        assertTrue(got.last().confidence < got.first().confidence)
        assertNull(AmountSanity.withoutFirstDigit("8000"))
        assertNull(AmountSanity.withoutFirstDigit("230"))
        assertEquals(listOf("23045"), amounts("Payment Successful\n23,045\nOct 2, 2026", Mode.GUARD).map { it.value })
    }

    private data class Repaired(val text: String, val changed: Boolean)
}

class AnswerCaveatsTest {
    private fun fact(raw: String, conf: Float) = ExtractedFact(FactType.AMOUNT, "8000", raw, "Amount paid", 0, conf)

    @Test fun aRepairedAmountSaysWhatHappenedAndWhatWasRead() {
        val c = com.munin.app.answer.AnswerCaveats.of(FactType.AMOUNT, fact("Rs ৪,000", AmountSanity.REPAIRED), false)!!
        assertTrue(c.contains("look-alike")); assertTrue(c.contains("Rs ৪,000")); assertTrue(c.contains("Check the image"))
    }

    @Test fun otherCaveatsAreUnchanged() {
        assertTrue(com.munin.app.answer.AnswerCaveats.of(FactType.AMOUNT, fact("23,045", 0.45f), false)!!.startsWith("The ₹ sign was not clearly read"))
        assertEquals(null, com.munin.app.answer.AnswerCaveats.of(FactType.AMOUNT, fact("Rs 8,000", 0.9f), false))
        assertTrue(com.munin.app.answer.AnswerCaveats.of(FactType.AMOUNT, fact("Rs 8,000", 0.9f), true)!!.startsWith("Other amount"))
    }
}

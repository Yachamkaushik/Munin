package com.munin.app.index

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayeredOcrEngineTest {
    private class Fake(val result: OcrResult) : OcrEngine {
        var calls = 0
        override suspend fun recognize(uri: String, rotationDegrees: Int): OcrResult { calls++; return result }
    }

    private val sure = OcrResult("Hostel Fee Receipt\nAmount paid: Rs 45,000", "mlkit", confidence = 0.9f, droppedLines = 0)
    private val unsure = OcrResult("3OoH vdo: ór, 45,000", "mlkit", confidence = 0.5f, droppedLines = 2)
    private val telugu = OcrResult("హాస్టల్ ఫీజు రసీదు\nరూ. 28,000", "tesseract", confidence = 0.8f)
    private val gibberishLatin = OcrResult("ooo xx 45,000 ll", "tesseract", confidence = 0.3f)

    private fun run(first: OcrResult, second: OcrResult, policy: TeluguPolicy): Triple<OcrResult, Fake, Fake> {
        val a = Fake(first); val b = Fake(second)
        return Triple(runBlocking { LayeredOcrEngine(a, b) { policy }.recognize("u", 0) }, a, b)
    }

    @Test fun offNeverCallsTheSecondReader() {
        val (r, _, b) = run(unsure, telugu, TeluguPolicy.OFF)
        assertEquals(unsure, r); assertEquals(0, b.calls)
    }

    @Test fun whenDoubtfulSkipsTheSecondReaderIfTheFirstIsSure() {
        val (r, _, b) = run(sure, telugu, TeluguPolicy.WHEN_DOUBTFUL)
        assertEquals(sure, r); assertEquals(0, b.calls)
    }

    @Test fun whenDoubtfulAsksAndUsesTeluguTextIfItLooksTelugu() {
        val (r, _, b) = run(unsure, telugu, TeluguPolicy.WHEN_DOUBTFUL)
        assertEquals(telugu, r); assertEquals(1, b.calls)
    }

    @Test fun aSecondResultThatIsNotTeluguNeverReplacesTheFirst() { // the safeguard: a Telugu model over Latin text must not win
        assertEquals(unsure, run(unsure, gibberishLatin, TeluguPolicy.WHEN_DOUBTFUL).first)
        assertEquals(sure, run(sure, gibberishLatin, TeluguPolicy.ALWAYS).first)
    }

    @Test fun alwaysAsksEvenWhenTheFirstIsSure() {
        val (r, _, b) = run(sure, telugu, TeluguPolicy.ALWAYS)
        assertEquals(1, b.calls); assertEquals(telugu, r)
    }

    @Test fun anImageWithNoTextCountsAsDoubtful() {
        assertTrue(LayeredOcrEngine.doubtful(OcrResult("", "mlkit", confidence = 0f)))
        assertTrue(LayeredOcrEngine.doubtful(OcrResult("fine", "mlkit", confidence = 0.95f, droppedLines = 1)))
        assertTrue(LayeredOcrEngine.doubtful(OcrResult("fine", "mlkit", confidence = 0.74f)))
        assertFalse(LayeredOcrEngine.doubtful(OcrResult("fine", "mlkit", confidence = 0.75f)))
    }

    @Test fun withoutASecondEngineNothingChanges() = runBlocking {
        assertEquals(unsure, LayeredOcrEngine(Fake(unsure), null) { TeluguPolicy.ALWAYS }.recognize("u", 0))
    }

    @Test fun zeroWidthJoinersAreRemovedSoKeywordsMatch() {
        assertEquals("హాస్టల్ ఫీజు", TeluguScript.normalize("హాస్టల్\u200C ఫీజు\u200D"))
        assertEquals("plain text", TeluguScript.normalize("plain text"))
    }

    @Test fun teluguScriptDetection() {
        assertEquals(0, TeluguScript.count("Amount paid: Rs 45,000"))
        assertTrue(TeluguScript.share("హాస్టల్ ఫీజు 45,000") > 0.8f)
        assertEquals(0f, TeluguScript.share("12345 ,.-"), 0f)
        assertEquals(0f, TeluguScript.share("हॉस्टल शुल्क"), 0f)
        assertTrue(LayeredOcrEngine.looksTelugu("హాస్టల్ ఫీజు"))
        assertFalse(LayeredOcrEngine.looksTelugu("రూ 3 hostel fee receipt for the second semester paid")) // a stray Telugu glyph in English text
    }
}

package com.munin.app.answer

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemKind
import com.munin.app.data.MuninDatabase
import com.munin.app.extract.FactType
import com.munin.app.index.Indexer
import com.munin.app.index.OcrEngine
import com.munin.app.index.OcrResult
import com.munin.app.ml.E5Embedder
import com.munin.app.search.SearchEngine
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Question -> search -> extracted fact -> answer, with the real model and a small multilingual corpus. */
class AnswerEngineTest {
    private fun ask(q: String, engine: AnswerEngine = answers): AnswerOutcome = runBlocking {
        val outcome = engine.answer(q, search.search(q))
        Log.i("MuninAnswer", "\"$q\" -> ${describe(outcome)}")
        outcome
    }

    private fun describe(o: AnswerOutcome) = when (o) {
        is AnswerOutcome.Found -> "FOUND ${o.answer.display} [${o.answer.label}] from ${o.answer.source.displayName} conf=${"%.2f".format(o.answer.confidence)} caveat=${o.answer.caveat}"
        is AnswerOutcome.Declined -> "DECLINED ${o.kind}: ${o.reason}"
        AnswerOutcome.NotAQuestion -> "not a question"
    }

    private fun found(q: String, engine: AnswerEngine = answers) = (ask(q, engine) as? AnswerOutcome.Found)?.answer ?: error("expected an answer for \"$q\": ${describe(ask(q, engine))}")

    @Test fun hostelFeeInEnglish() {
        val a = found("how much was the hostel fee")
        assertEquals("₹45,000", a.display)
        assertEquals("45000", a.value)
        assertEquals("en_fee", a.source.displayName)
        assertEquals("Amount paid", a.label)
        assertTrue(a.confidence >= 0.8f)
    }

    @Test fun sameQuestionInTeluguStillAnswers() {
        val te = found("హాస్టల్ ఫీజు ఎంత")
        assertEquals("45000", te.value); assertEquals("te_fee", te.source.displayName)
    }

    /**
     * The grounding gate (the shipped default) declines when the question contains a word that is rare in the collection and absent from the
     * document, and it cannot yet tell a generic word from a topic word: "pay" (the receipt says "paid"), Hindi "था", the Telugu verb "కట్టాను".
     * So it sometimes declines a question the original gate answered correctly, but must never answer wrongly. Measured in docs/EVALUATION.md.
     */
    @Test fun theGroundingGateMayDeclineButNeverAnswersDifferentlyFromTheOriginalGate() {
        val cases = listOf("छात्रावास शुल्क कितना था" to "45000", "hostel fee ఎంత కట్టాను" to "45000", "when did I pay the hostel fee" to "2026-09-12")
        for ((q, expected) in cases) {
            assertEquals(q, expected, found(q, baselineAnswers).value) // the original gate answers all of them
            when (val o = ask(q)) {
                is AnswerOutcome.Found -> assertEquals(q, expected, o.answer.value) // if it answers, it is right
                is AnswerOutcome.Declined -> Log.i("MuninAnswer", "declined by the grounding gate (documented trade-off): $q")
                AnswerOutcome.NotAQuestion -> error("$q should be recognised as a question")
            }
        }
    }

    @Test fun datesComeBackNormalizedAndLabelled() {
        val due = found("when is the electricity bill due")
        assertEquals("2026-10-15", due.value); assertEquals("15 Oct 2026", due.display); assertEquals("Due date", due.label)
        // "pay" is not in the document ("Paid on"), so the grounding gate declines this; the original gate answers. See the gate test below.
        assertEquals("2026-09-12", found("when did I pay the hostel fee", baselineAnswers).value)
    }

    @Test fun phoneAndAddressFromTheClinicCard() {
        assertEquals("+919876543210", found("clinic phone number").value)
        assertEquals("+91 98765 43210", found("clinic phone number").display)
        assertTrue(found("clinic address").value.contains("Jubilee Hills"))
    }

    @Test fun declinesWhenTheBestItemHasNoSuchField() {
        val o = ask("what is the flight booking phone number")
        assertTrue(describe(o), o is AnswerOutcome.Declined && o.reason.contains("no phone"))
    }

    @Test fun descriptionsAndSpendingQuestionsGetNoAnswerCard() {
        assertEquals(AnswerOutcome.NotAQuestion, ask("hostel fee receipt"))
        assertEquals(AnswerOutcome.NotAQuestion, ask("how much did I spend in September"))
    }

    @Test fun neverAnswersFromAnUnrelatedItem() {
        // Each of these used to pass a weaker gate: "car" prefix-matched "card", "fee" alone matched the hostel receipt.
        for (q in listOf("how much was the car insurance", "how much was the school bus fee", "how much is the rent", "how much was the pizza", "how much was the laptop")) {
            val o = ask(q)
            assertTrue("$q -> ${describe(o)}", o is AnswerOutcome.Declined)
        }
    }

    /** Not assertions: top-1 evidence for unrelated vs. legitimate-but-wordless questions, to calibrate the gate. */
    @Test fun logEvidenceCalibration() = runBlocking {
        for (q in listOf(
            "how much was the car insurance", "how much is the rent", "how much was the pizza", "how much was the school bus fee",
            "how much was the gym membership", "how much was the laptop",
            "dormitory charges how much", "how much did the lodging cost", "how much was the power bill", "mess charges how much",
        )) {
            val r = search.search(q)
            val top = r.results.getOrNull(0); val next = r.results.getOrNull(1)
            Log.i("MuninCalib", "%-36s top=%-7s meaning=%.3f next=%.3f lead=%.3f kw=%s evidence=%s".format(
                q, top?.displayName, top?.meaningScore ?: -1f, next?.meaningScore ?: -1f,
                (top?.meaningScore ?: 0f) - (next?.meaningScore ?: 0f), top?.keywordRank,
                top?.let { AnswerSelector.itemEvidence(r.results, QuestionParser.parse(q)!!, db.facts().chunkTexts(it.itemId).joinToString("\n")) }))
        }
    }

    @Test fun factsAreStoredAndBackfilledFromSavedText() = runBlocking {
        val id = db.items().allExisting().first { it.uri == "en_fee" }.id
        assertTrue(db.facts().forItems(listOf(id), "AMOUNT").isNotEmpty())
        // simulate an item indexed before this feature: no facts, factsVersion 0
        db.facts().deleteForItem(id)
        db.openHelper.writableDatabase.execSQL("UPDATE items SET factsVersion = 0 WHERE id = $id")
        assertEquals(1, db.backfillFacts())
        assertEquals(listOf("45000"), db.facts().forItems(listOf(id), "AMOUNT").map { it.value })
        assertEquals(0, db.backfillFacts()) // nothing left to do
    }

    companion object {
        lateinit var db: MuninDatabase
        lateinit var embedder: E5Embedder
        lateinit var search: SearchEngine
        lateinit var answers: AnswerEngine
        lateinit var baselineAnswers: AnswerEngine

        private val docs = linkedMapOf(
            "en_fee" to "Hostel Fee Receipt\nStudent: Ravi Kumar\nAmount paid: Rs 45,000\nPaid on:12 Sep 2026\nTransaction ID: 4821937560",
            "te_fee" to "హాస్టల్ ఫీజు రసీదు\nవిద్యార్థి: రవి కుమార్\nచెల్లించిన మొత్తం: రూ. 45,000\nతేదీ: 12 సెప్టెంబర్ 2026",
            "hi_fee" to "छात्रावास शुल्क रसीद\nजमा राशि: ₹45,000\nदिनांक: 12 सितंबर 2026",
            "elec" to "Electricity Bill\nConsumer: Ravi Kumar\nAmount due: 1,250\nDue date: 15/10/2026",
            "upi" to "Payment successful\nI2,499\nPaid to Amazon Pay\n20 Sep 2026, 6:45 PM\nUPI Ref No: 612345678901",
            "flight" to "Your flight to Hyderabad is confirmed\n3 November 6:45 AM\nBooking ref XK92LP",
            "clinic" to "Apollo Clinic appointment card\nAddress: Road No 36, Jubilee Hills\nHyderabad 500033\nPhone: 98765 43210\nAppointment: 20 Sep 2026, 10:30 AM",
            "notes" to "Meeting notes: submit the project report by Monday",
        )

        @BeforeClass @JvmStatic fun build() = runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            db = MuninDatabase.create(ctx, name = null)
            embedder = E5Embedder.load(ctx)
            val ocr = object : OcrEngine {
                override suspend fun recognize(uri: String, rotationDegrees: Int) = OcrResult(docs.getValue(uri), "fake")
            }
            val indexer = Indexer(db, { ByteArrayInputStream(it.toByteArray()) }, ocr, embedder)
            docs.keys.forEach {
                db.items().insertIgnore(ItemEntity(uri = it, kind = ItemKind.IMAGE, displayName = it, sizeBytes = 1, modifiedAt = 1, addedAt = 0, width = 1080, height = 1920, rotation = 0))
            }
            while (indexer.processNext()) Unit
            search = SearchEngine(db, embedder)
            answers = AnswerEngine(db) // the shipped default: AnswerOptions.RECOMMENDED
            baselineAnswers = AnswerEngine(db, com.munin.app.answer.AnswerOptions.BASELINE)
        }

        @AfterClass @JvmStatic fun close() { db.close(); embedder.close() }
    }
}

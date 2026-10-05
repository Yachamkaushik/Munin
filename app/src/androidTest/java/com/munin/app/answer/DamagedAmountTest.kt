package com.munin.app.answer

import androidx.test.core.app.ApplicationProvider
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemKind
import com.munin.app.data.MuninDatabase
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

/** The amount sanity check through the real pipeline: damaged digits as the reader produced them are corrected, and the answer says so. */
class DamagedAmountTest {
    private fun found(q: String): Answer = runBlocking {
        (answers.answer(q, search.search(q)) as? AnswerOutcome.Found)?.answer ?: error("no answer for \"$q\"")
    }

    @Test fun aLookalikeDigitIsRepairedAndTheAnswerSaysToCheck() {
        val a = found("how much was the hostel fee")
        assertEquals("8000", a.value)
        assertTrue(a.factConfidence < 0.6f)
        assertTrue(a.caveat!!, a.caveat!!.contains("look-alike") && a.caveat!!.contains("Rs ৪,000"))
    }

    @Test fun aNumberThatUsedToBeCutShortIsNowWhole() = assertEquals("2698", found("how much is the insurance premium due").value)

    @Test fun storedFactsFromOlderRulesAreReReadFromSavedText() = runBlocking {
        val id = db.items().allExisting().first { it.uri == "fee" }.id
        db.facts().deleteForItem(id)
        db.openHelper.writableDatabase.execSQL("UPDATE items SET factsVersion = 4 WHERE id = $id") // the previous rules version
        assertEquals(1, db.backfillFacts())
        assertEquals(listOf("8000"), db.facts().forItems(listOf(id), "AMOUNT").map { it.value })
    }

    companion object {
        lateinit var db: MuninDatabase
        lateinit var embedder: E5Embedder
        lateinit var search: SearchEngine
        lateinit var answers: AnswerEngine

        private val docs = linkedMapOf(
            "fee" to "Hostel Fee Receipt\nStudent: Ravi Kumar\nAmount paid: Rs ৪,000\nPaid on:12 Sep 2026",
            "premium" to "Insurance Premium Notice\nPolicy no: 64619201\nPremium due: Rs 2,69৪\nDue date: 14 Dec 2026",
            "flight" to "Your flight to Hyderabad is confirmed\n3 November 6:45 AM\nBooking ref XK92LP",
            "notes" to "Meeting notes: submit the project report by Monday",
        )

        @BeforeClass @JvmStatic fun build() = runBlocking {
            val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
            db = MuninDatabase.create(ctx, name = null)
            embedder = E5Embedder.load(ctx)
            val ocr = object : OcrEngine { override suspend fun recognize(uri: String, rotationDegrees: Int) = OcrResult(docs.getValue(uri), "fake") }
            val indexer = Indexer(db, { ByteArrayInputStream(it.toByteArray()) }, ocr, embedder)
            docs.keys.forEach { db.items().insertIgnore(ItemEntity(uri = it, kind = ItemKind.IMAGE, displayName = it, sizeBytes = 1, modifiedAt = 1, addedAt = 0, width = 1080, height = 1920, rotation = 0)) }
            while (indexer.processNext()) Unit
            search = SearchEngine(db, embedder)
            answers = AnswerEngine(db)
        }

        @AfterClass @JvmStatic fun close() { db.close(); embedder.close() }
    }
}

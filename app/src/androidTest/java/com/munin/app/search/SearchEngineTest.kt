package com.munin.app.search

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemKind
import com.munin.app.data.MuninDatabase
import com.munin.app.index.Indexer
import com.munin.app.index.OcrEngine
import com.munin.app.index.OcrResult
import com.munin.app.ml.E5Embedder
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Search over a small multilingual corpus with the real model, real FTS4 and real Room. */
class SearchEngineTest {
    private fun top(query: String, mode: SearchMode = SearchMode.MERGED, n: Int = 3): List<String> = runBlocking {
        val r = engine.search(query, mode, limit = n)
        Log.i("MuninSearch", "[$mode] \"$query\" -> ${r.results.map { it.displayName }}  ${"%.0f".format(r.timings.totalMs)} ms")
        r.results.map { it.displayName }
    }

    @Test fun englishQueryFindsTheEnglishReceipt() = assertEquals("en_fee", top("hostel fee receipt", n = 1).single())

    @Test fun teluguQueryFindsTheTeluguReceiptFirst() = assertEquals("te_fee", top("హాస్టల్ ఫీజు రసీదు", n = 1).single())

    @Test fun exactIdentifiersAreFoundByKeywords() {
        assertEquals("en_fee", top("4821937560", SearchMode.KEYWORDS, 1).single())
        assertEquals("en_fee", top("4821937560", SearchMode.MERGED, 1).single())
    }

    @Test fun otherEnglishQueries() {
        assertEquals("elec", top("electricity bill due date", n = 1).single())
        assertEquals("flight", top("flight to hyderabad", n = 1).single())
    }

    @Test fun keywordLegDoesNotCrossLanguages() {
        assertTrue("te_fee" !in top("hostel fee", SearchMode.KEYWORDS, 10))
        assertTrue("en_fee" !in top("హాస్టల్ ఫీజు", SearchMode.KEYWORDS, 10))
        assertEquals(listOf("te_fee"), top("హాస్టల్ ఫీజు", SearchMode.KEYWORDS, 10))
    }

    @Test fun meaningLegAlwaysReturnsNeighboursAndSurvivesJunkQueries() {
        assertEquals(5, top("qwertyzzz", SearchMode.MEANING, 5).size)
        assertEquals(emptyList<String>(), top("qwertyzzz", SearchMode.KEYWORDS, 5))
    }

    @Test fun blankAndStopwordOnlyQueriesDoNotCrash() {
        assertEquals(emptyList<String>(), top("   "))
        assertEquals(emptyList<String>(), top("how much was the", SearchMode.KEYWORDS))
        top("how much was the") // meaning leg still has something to say
    }

    @Test fun resultsAreOnePerItemAndCarryHighlightedSnippets() = runBlocking {
        val r = engine.search("hostel fee receipt", SearchMode.MERGED, limit = 10)
        assertEquals(r.results.map { it.itemId }.distinct(), r.results.map { it.itemId })
        val first = r.results.first()
        assertTrue(first.snippet.highlights.isNotEmpty())
        assertEquals(8, r.timings.chunksSearched)
    }

    /** Not assertions: ranks for the harder query styles, logged so quality can be inspected honestly. */
    @Test fun logHarderQueryStyles() {
        for (q in listOf("hostel fee రసీదు చూపించు", "hostel fee receipt ekkada undi", "छात्रावास शुल्क", "bijli bill kitna", "current bill entha")) {
            top(q, SearchMode.MERGED, 3); top(q, SearchMode.MEANING, 3); top(q, SearchMode.KEYWORDS, 3)
        }
    }

    @Test fun searchIsFast() = runBlocking {
        engine.search("warm up")
        val times = (1..10).map { engine.search("hostel fee receipt").timings }
        val med = times.map { it.totalMs }.sorted()[5]
        Log.i("MuninSearch", "median total ${"%.1f".format(med)} ms; last: $${times.last()}")
        assertTrue("median $med ms", med < 500)
    }

    companion object {
        lateinit var db: MuninDatabase
        lateinit var embedder: E5Embedder
        lateinit var engine: SearchEngine

        private val docs = linkedMapOf(
            "en_fee" to "Hostel Fee Receipt\nStudent: Ravi Kumar\nAmount paid: Rs 45,000\nPaid on: 12 Sep 2026\nTransaction ID: 4821937560",
            "te_fee" to "హాస్టల్ ఫీజు రసీదు\nవిద్యార్థి: రవి కుమార్\nచెల్లించిన మొత్తం: రూ. 45,000",
            "hi_fee" to "छात्रावास शुल्क रसीद\nजमा राशि: ₹45,000\nदिनांक: 12 सितंबर 2026",
            "elec" to "Electricity Bill\nAmount due: ₹1,250\nDue date: 15/10/2026",
            "upi" to "Payment successful\n₹2,499\nPaid to Amazon Pay\nUPI Ref No: 612345678901",
            "flight" to "Your flight to Hyderabad is confirmed\n3 November 6:45 AM\nBooking ref XK92LP",
            "doctor" to "Doctor appointment Apollo Clinic Jubilee Hills\nFriday 10:30 AM",
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
            engine = SearchEngine(db, embedder)
        }

        @AfterClass @JvmStatic fun close() { db.close(); embedder.close() }
    }
}

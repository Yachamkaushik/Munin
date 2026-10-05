package com.munin.app.index

import androidx.test.core.app.ApplicationProvider
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemKind
import com.munin.app.data.MuninDatabase
import com.munin.app.data.VectorCodec
import com.munin.app.ml.E5Embedder
import java.io.ByteArrayInputStream
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** The indexing pipeline end to end on a device: real model, real Room/FTS, fake files and fake OCR. */
class IndexerTest {
    private lateinit var db: MuninDatabase
    private val files = HashMap<String, ByteArray>()
    private val texts = HashMap<String, String>()
    private val failing = HashSet<String>()

    private val ocr = object : OcrEngine {
        override suspend fun recognize(uri: String, rotationDegrees: Int): OcrResult {
            check(uri !in failing) { "decoder exploded" }
            return OcrResult(texts[uri] ?: "", "fake")
        }
    }

    private fun indexer() = Indexer(db, { ByteArrayInputStream(files.getValue(it)) }, ocr, embedder)

    @Before fun setUp() { db = MuninDatabase.create(ApplicationProvider.getApplicationContext(), name = null) }
    @After fun tearDown() = db.close()

    private fun add(uri: String, text: String?, bytes: ByteArray = uri.toByteArray(), added: Long = 0) {
        files[uri] = bytes
        if (text != null) texts[uri] = text
        runBlocking {
            db.items().insertIgnore(
                ItemEntity(uri = uri, kind = ItemKind.IMAGE, displayName = uri, sizeBytes = bytes.size.toLong(), modifiedAt = 1, addedAt = added, width = 1080, height = 2400, rotation = 0),
            )
        }
    }

    private fun drain() = runBlocking { while (indexer().processNext()) Unit }

    private fun itemStatuses(): Map<String, String> {
        val out = HashMap<String, String>()
        db.openHelper.readableDatabase.query("SELECT uri, status FROM items").use { while (it.moveToNext()) out[it.getString(0)] = it.getString(1) }
        return out
    }

    @Test fun indexesEnglishHindiAndTeluguTextIntoChunksVectorsAndFts() {
        add("en", "Hostel Fee Receipt\nRs 45,000 paid on 12 Sep 2026")
        add("hi", "छात्रावास शुल्क की रसीद\n₹45,000 जमा किए गए")
        add("te", "హాస్టల్ ఫీజు రసీదు\nరూ. 45,000 చెల్లించారు")
        drain()

        assertEquals(mapOf("en" to "INDEXED", "hi" to "INDEXED", "te" to "INDEXED"), itemStatuses())
        runBlocking {
            assertEquals(3, db.chunks().count())
            assertEquals(3, db.embeddings().count())
            assertEquals(0, db.embeddings().countOtherVersions(E5Embedder.MODEL_VERSION))
        }
        // Whole words match in all three scripts: this is what the Indic tokenchars setting is for.
        assertEquals(1, db.matchCount("\"hostel\""))
        assertEquals(1, db.matchCount("\"ఫీజు\""))
        assertEquals(1, db.matchCount("\"शुल्क\""))
        assertEquals(0, db.matchCount("\"nonexistentword\""))
        println("keyword engine: ${db.keywordEngine}")
    }

    /** Android's system SQLite may lack FTS5. If it has it, our tokenizer options must be valid and be used. */
    @Test fun keywordEngineIsFts5WheneverFts5Exists() {
        val sql = db.openHelper.writableDatabase
        val fts5Exists = runCatching { sql.execSQL("CREATE VIRTUAL TABLE temp.fts5_probe USING fts5(a)"); sql.execSQL("DROP TABLE temp.fts5_probe") }.isSuccess
        println("FTS5 available in system SQLite: $fts5Exists; engine in use: ${db.keywordEngine}")
        android.util.Log.i("MuninFts", "FTS5 available=$fts5Exists engine=${db.keywordEngine}")
        assertEquals(if (fts5Exists) com.munin.app.data.KeywordIndex.Engine.FTS5 else com.munin.app.data.KeywordIndex.Engine.FTS4, db.keywordEngine)
    }

    @Test fun storedVectorsAreUnitLength384() {
        add("en", "Electricity bill due 15 October")
        drain()
        val v = db.openHelper.readableDatabase.query("SELECT vector FROM embeddings").use { it.moveToFirst(); VectorCodec.decode(it.getBlob(0)) }
        assertEquals(384, v.size)
        assertEquals(1.0, sqrt(v.sumOf { it.toDouble() * it }), 1e-3)
    }

    @Test fun exactDuplicatesAreNotIndexedTwice() {
        add("a.png", "Same receipt", bytes = byteArrayOf(1, 2, 3), added = 2)
        add("b.png", "Same receipt", bytes = byteArrayOf(1, 2, 3), added = 1)
        drain()
        assertEquals(mapOf("a.png" to "INDEXED", "b.png" to "DUPLICATE"), itemStatuses())
        assertEquals(1, runBlocking { db.chunks().count() })
    }

    @Test fun imageWithoutTextKeepsAnEntryButNoChunks() {
        add("blank", "  \n ")
        drain()
        assertEquals(mapOf("blank" to "NO_TEXT"), itemStatuses())
        assertEquals(0, runBlocking { db.chunks().count() })
    }

    @Test fun oneFailureDoesNotStopTheRest() {
        add("bad", "x", added = 2); failing += "bad"
        add("good", "Water bill", added = 1)
        drain()
        assertEquals(mapOf("bad" to "FAILED", "good" to "INDEXED"), itemStatuses())
        val err = db.openHelper.readableDatabase.query("SELECT error FROM items WHERE uri='bad'").use { it.moveToFirst(); it.getString(0) }
        assertTrue(err, err.contains("decoder exploded"))
    }

    @Test fun newestFirstAndRerunsSkipFinishedWork() = runBlocking {
        add("old", "old text", added = 1)
        add("new", "new text", added = 9)
        assertEquals("new", db.items().nextPending()!!.uri)
        assertTrue(indexer().processNext())
        assertEquals("old", db.items().nextPending()!!.uri)
        drain()
        // Nothing pending: a second run (e.g. after a restart) has no work and leaves finished items alone.
        val chunksBefore = db.chunks().count()
        assertTrue(!indexer().processNext())
        assertEquals(chunksBefore, db.chunks().count())
    }

    @Test fun deletingAnItemRemovesItsChunksVectorsAndFtsRows() = runBlocking {
        add("keep", "keepword alpha"); add("drop", "dropword beta")
        drain()
        assertEquals(1, db.matchCount("\"dropword\""))
        db.deleteItems(listOf(db.items().allExisting().first { it.uri == "drop" }.id))
        assertEquals(0, db.matchCount("\"dropword\""))
        assertEquals(1, db.matchCount("\"keepword\""))
        assertEquals(1, db.chunks().count())
        assertEquals(1, db.embeddings().count())
    }

    @Test fun clearIndexEmptiesEveryTable() = runBlocking {
        add("x", "some words here"); drain()
        db.clearIndex()
        assertEquals(0, db.chunks().count()); assertEquals(0, db.embeddings().count()); assertEquals(0, db.matchCount("\"words\""))
        assertEquals(emptyList<Any>(), db.items().allExisting())
    }

    companion object {
        lateinit var embedder: E5Embedder

        @BeforeClass @JvmStatic fun loadModel() { embedder = E5Embedder.load(ApplicationProvider.getApplicationContext()) }
        @AfterClass @JvmStatic fun closeModel() = embedder.close()
    }
}

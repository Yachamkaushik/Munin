package com.munin.app.index

import com.munin.app.data.ChunkEntity
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemStatus
import com.munin.app.data.MuninDatabase
import com.munin.app.data.VectorCodec
import com.munin.app.extract.FactExtractor
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/** Opens a file's bytes; abstracted so tests do not need MediaStore. */
fun interface ContentSource {
    fun open(uri: String): InputStream
}

/** Turns text into a vector; abstracted so the pipeline can be tested without loading the model. */
interface PassageEmbedder {
    val modelVersion: String
    fun countTokens(text: String): Int
    fun embedPassage(text: String): FloatArray
}

/**
 * The per-item pipeline: hash, drop exact duplicates, OCR, chunk, embed, store.
 * Everything for one item is written in one transaction, so a killed process never leaves half an item.
 */
class Indexer(
    private val db: MuninDatabase,
    private val content: ContentSource,
    private val ocr: OcrEngine,
    private val embedder: PassageEmbedder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val chunker = Chunker(countTokens = embedder::countTokens)

    /** Processes the next pending item. Returns false when nothing is pending. */
    suspend fun processNext(): Boolean {
        val item = db.items().nextPending() ?: return false
        process(item)
        return true
    }

    suspend fun process(item: ItemEntity) {
        try {
            val hash = content.open(item.uri).use(::sha256)
            if (db.items().countIndexedWithHash(hash, item.id) > 0) {
                db.items().update(item.copy(contentHash = hash, status = ItemStatus.DUPLICATE, indexedAt = clock()))
                return
            }

            val t0 = nanoClock()
            val text = ocr.recognize(item.uri, item.rotation).text
            val ocrMs = (nanoClock() - t0) / 1_000_000

            val chunks = chunker.split(text)
            if (chunks.isEmpty()) {
                db.items().update(item.copy(contentHash = hash, status = ItemStatus.NO_TEXT, indexedAt = clock(), ocrMs = ocrMs, textLength = 0))
                return
            }

            val t1 = nanoClock()
            val vectors = chunks.map { VectorCodec.encode(embedder.embedPassage(it)) }
            val embedMs = (nanoClock() - t1) / 1_000_000

            db.insertChunks(chunks.mapIndexed { i, t -> ChunkEntity(itemId = item.id, ordinal = i, text = t) }, vectors, embedder.modelVersion)
            db.items().update(
                item.copy(
                    contentHash = hash, status = ItemStatus.INDEXED, indexedAt = clock(), error = null,
                    ocrMs = ocrMs, embedMs = embedMs, textLength = chunks.sumOf { it.length },
                ),
            )
            // After the item update, which would otherwise reset factsVersion. If we die before this line the
            // item has no facts and factsVersion 0, and the next backfill fills them in from the saved chunks.
            db.replaceFacts(item.id, FactExtractor.extract(chunks.joinToString("\n")))
        } catch (e: CancellationException) {
            throw e // stays PENDING and is picked up again
        } catch (e: Exception) {
            db.items().update(item.copy(status = ItemStatus.FAILED, error = "${e.javaClass.simpleName}: ${e.message}".take(300), indexedAt = clock()))
        }
    }

    private fun sha256(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

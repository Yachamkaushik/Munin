package com.munin.app.search

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.munin.app.data.ChunkEntity
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemKind
import com.munin.app.data.MuninDatabase
import com.munin.app.data.VectorCodec
import com.munin.app.ml.E5Embedder
import java.util.Random
import kotlinx.coroutines.runBlocking
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the brute-force vector scan behaves as the index grows. Random unit vectors, so this measures speed only. */
class VectorIndexScaleTest {
    private fun unit(rnd: Random) = FloatArray(384) { rnd.nextGaussian().toFloat() }.let { v ->
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        FloatArray(384) { v[it] / n }
    }

    private fun measure(count: Int): Triple<Double, Double, Int> = runBlocking {
        val db = MuninDatabase.create(ApplicationProvider.getApplicationContext(), name = null)
        try {
            val rnd = Random(7)
            for (i in 0 until count) {
                val id = db.items().insertIgnore(ItemEntity(uri = "u$i", kind = ItemKind.IMAGE, displayName = "n$i", sizeBytes = 1, modifiedAt = 1, addedAt = 0, width = 1000, height = 1000, rotation = 0))
                db.insertChunks(listOf(ChunkEntity(itemId = id, ordinal = 0, text = "t$i")), listOf(VectorCodec.encode(unit(rnd))), E5Embedder.MODEL_VERSION)
            }
            val index = VectorIndex(E5Embedder.MODEL_VERSION)
            val t0 = System.nanoTime(); index.refresh(db); val loadMs = (System.nanoTime() - t0) / 1e6
            val q = unit(rnd)
            index.search(q, 50) // warm up the JIT
            val times = (1..20).map { val t = System.nanoTime(); index.search(q, 50); (System.nanoTime() - t) / 1e6 }.sorted()
            Log.i("MuninScale", "$count vectors: load ${"%.0f".format(loadMs)} ms, scan median ${"%.1f".format(times[10])} ms, worst ${"%.1f".format(times.last())} ms")
            assertEquals(count, index.size)
            Triple(loadMs, times[10], index.size)
        } finally { db.close() }
    }

    @Test fun fiveThousandVectors() { assertTrue(measure(5_000).second < 100) }
    @Test fun twentyThousandVectors() { assertTrue(measure(20_000).second < 400) }
}

package com.munin.app.ml

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/** On-device tokenizer ids (exact) and ONNX embeddings (cosine) vs. the Python reference. */
class EmbeddingParityTest {
    @Test
    fun tokenIdsMatchPythonExactly() {
        val bad = records.filter { embedder.tokenizer.encodeForModel(it.inputText).toList() != it.ids.toList() }
        assertTrue("token id mismatches: ${bad.map { it.id + "/" + it.prefix }}", bad.isEmpty())
    }

    @Test
    fun embeddingsMatchPython() {
        var minCos = 1.0
        var sumCos = 0.0
        var maxAbs = 0.0
        var worst = ""
        for (r in records) {
            val v = embedder.embedIds(r.ids)
            assertEquals("dimension", E5Embedder.DIM, v.size)
            var dot = 0.0
            for (i in v.indices) { dot += v[i] * r.vector[i]; maxAbs = maxOf(maxAbs, abs((v[i] - r.vector[i]).toDouble())) }
            val norm = sqrt(v.sumOf { it.toDouble() * it })
            assertEquals("unit norm for ${r.id}", 1.0, norm, 1e-3)
            if (dot < minCos) { minCos = dot; worst = r.id + "/" + r.prefix }
            sumCos += dot
        }
        Log.i("MuninParity", "cosine vs python: min=$minCos ($worst) mean=${sumCos / records.size} maxAbsDiff=$maxAbs over ${records.size} vectors")
        assertTrue("min cosine $minCos at $worst", minCos >= MIN_COSINE)
    }

    companion object {
        /** Same ORT version and optimization level on both sides, so this should be near-exact. */
        const val MIN_COSINE = 0.9999

        lateinit var embedder: E5Embedder
        lateinit var records: List<ReferenceRecord>

        @BeforeClass @JvmStatic
        fun load() {
            val instr = InstrumentationRegistry.getInstrumentation()
            embedder = E5Embedder.load(instr.targetContext)
            records = ReferenceData.records(instr.context.assets.open("reference.json").bufferedReader().readText())
        }

        @AfterClass @JvmStatic
        fun close() = embedder.close()
    }
}

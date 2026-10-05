package com.munin.app.ml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Kotlin tokenizer vs. Python `sentencepiece` output (JVM only, no device needed). */
class TokenizerParityTest {
    @Test
    fun referenceSentencesMatchPythonTokenIds() {
        val failures = records.filter { tokenizer.encodeForModel(it.inputText).toList() != it.ids.toList() }
        assertTrue(describe(failures.map { it.inputText to it.ids }), failures.isEmpty())
    }

    @Test
    fun stressCorpusMatchesPythonTokenIds() {
        val failures = stress.filter { tokenizer.encode(it.text).toList() != it.ids.toList() }
        println("stress: ${stress.size - failures.size}/${stress.size} identical")
        assertTrue(describe(failures.take(10).map { it.text to it.ids }, forModel = false), failures.isEmpty())
    }

    @Test
    fun specialTokensAndTruncation() {
        val ids = tokenizer.encodeForModel("query: hello")
        assertEquals(E5Tokenizer.BOS, ids.first())
        assertEquals(E5Tokenizer.EOS, ids.last())
        val long = tokenizer.encodeForModel("word ".repeat(2000))
        assertEquals(E5Tokenizer.MAX_LENGTH, long.size)
        assertEquals(E5Tokenizer.EOS, long.last())
    }

    private fun describe(items: List<Pair<String, IntArray>>, forModel: Boolean = true) = items.joinToString("\n", "token mismatch:\n") { (text, expected) ->
        "  text=${text.take(60)!!}\n    expected=${expected.take(40)}\n    actual  =${(if (forModel) tokenizer.encodeForModel(text) else tokenizer.encode(text)).take(40)}\n    norm    =${tokenizer.normalize(text).take(60)}"
    }

    companion object {
        lateinit var tokenizer: E5Tokenizer
        lateinit var records: List<ReferenceRecord>
        lateinit var stress: List<StressRecord>

        private fun resource(name: String) = TokenizerParityTest::class.java.classLoader!!.getResource(name)!!.readText()

        @BeforeClass @JvmStatic
        fun load() {
            tokenizer = E5Tokenizer(File("src/main/assets/models/sentencepiece.bpe.model").readBytes())
            records = ReferenceData.records(resource("reference.json"))
            stress = ReferenceData.stress(resource("tokenizer_stress.json"))
        }
    }
}

package com.munin.app.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import kotlin.math.sqrt

/** multilingual-e5-small (int8) on ONNX Runtime Mobile: 384-d, mean-pooled, L2-normalised vectors. */
class E5Embedder private constructor(
    val tokenizer: E5Tokenizer,
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    fun embedPassage(text: String): FloatArray = embedRaw(E5Tokenizer.PASSAGE_PREFIX + text)

    fun embedQuery(text: String): FloatArray = embedRaw(E5Tokenizer.QUERY_PREFIX + text)

    /** Embeds [inputText] verbatim, i.e. with the "query: "/"passage: " prefix already applied. */
    fun embedRaw(inputText: String): FloatArray = embedIds(tokenizer.encodeForModel(inputText))

    fun embedIds(ids: IntArray): FloatArray {
        val n = ids.size
        val inputIds = arrayOf(LongArray(n) { ids[it].toLong() })
        val mask = arrayOf(LongArray(n) { 1L })
        val types = arrayOf(LongArray(n))
        OnnxTensor.createTensor(env, inputIds).use { t0 ->
            OnnxTensor.createTensor(env, mask).use { t1 ->
                OnnxTensor.createTensor(env, types).use { t2 ->
                    session.run(mapOf("input_ids" to t0, "attention_mask" to t1, "token_type_ids" to t2)).use { r ->
                        @Suppress("UNCHECKED_CAST")
                        val hidden = (r[0].value as Array<Array<FloatArray>>)[0] // [seq][384]
                        return meanPoolAndNormalize(hidden)
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val DIM = 384
        const val MODEL_VERSION = "multilingual-e5-small-int8"
        private const val MODEL_ASSET = "models/model_int8.onnx"
        private const val SPM_ASSET = "models/sentencepiece.bpe.model"

        /** Loads the bundled model; the ONNX file is copied to app cache once so ORT can mmap it. */
        fun load(context: Context): E5Embedder {
            val tokenizer = context.assets.open(SPM_ASSET).use { E5Tokenizer(it) }
            val modelFile = File(context.cacheDir, "model_int8.onnx")
            val assetLen = context.assets.openFd(MODEL_ASSET).use { it.length }
            if (!modelFile.exists() || modelFile.length() != assetLen) {
                context.assets.open(MODEL_ASSET).use { src -> modelFile.outputStream().use { src.copyTo(it) } }
            }
            val env = OrtEnvironment.getEnvironment()
            // Pinned to BASIC to match tools/embed_reference.py; the int8 model's output shifts with fused kernels.
            val options = OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT) }
            return E5Embedder(tokenizer, env, env.createSession(modelFile.absolutePath, options))
        }

        internal fun meanPoolAndNormalize(hidden: Array<FloatArray>): FloatArray {
            val v = FloatArray(DIM)
            for (row in hidden) for (d in 0 until DIM) v[d] += row[d]
            var norm = 0.0
            for (d in 0 until DIM) { v[d] /= hidden.size; norm += v[d].toDouble() * v[d] }
            val inv = (1.0 / sqrt(norm)).toFloat()
            for (d in 0 until DIM) v[d] *= inv
            return v
        }
    }
}

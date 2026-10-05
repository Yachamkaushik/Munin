package com.munin.app.ml

import java.io.InputStream

/**
 * Tokenizer for multilingual-e5-small (XLM-R vocabulary), matching the Python reference
 * `sentencepiece` output after fairseq id remapping.
 *
 * SentencePiece ids: `<unk>=0 <s>=1 </s>=2`, pieces from 3. XLM-R/HF ids: `<s>=0 <pad>=1 </s>=2 <unk>=3`,
 * so every ordinary piece shifts by +1 and the SentencePiece unknown becomes 3.
 */
class E5Tokenizer(modelBytes: ByteArray) {
    private val model = SentencePieceModel.parse(modelBytes)
    private val normalizer = SentencePieceNormalizer(model)
    private val encoder = UnigramEncoder(model)

    constructor(stream: InputStream) : this(stream.readBytes())

    /** Normalized text, exposed for debugging parity failures. */
    fun normalize(text: String): String = normalizer.normalize(text)

    /** Token ids for [text] without `<s>`/`</s>`. */
    fun encode(text: String): IntArray =
        encoder.encode(normalizer.normalize(text)).map { if (it == encoder.unkId) UNK else it + 1 }.toIntArray()

    /** `<s> ... </s>` ids truncated to [maxLength], ready for the model. */
    fun encodeForModel(text: String, maxLength: Int = MAX_LENGTH): IntArray {
        val body = encode(text)
        val kept = minOf(body.size, maxLength - 2)
        return IntArray(kept + 2).also {
            it[0] = BOS
            System.arraycopy(body, 0, it, 1, kept)
            it[kept + 1] = EOS
        }
    }

    companion object {
        const val BOS = 0
        const val EOS = 2
        const val UNK = 3
        const val MAX_LENGTH = 512
        const val PASSAGE_PREFIX = "passage: "
        const val QUERY_PREFIX = "query: "
    }
}

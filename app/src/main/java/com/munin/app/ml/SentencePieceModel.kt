package com.munin.app.ml

/**
 * Minimal reader for a SentencePiece `.model` file (a serialized `ModelProto`).
 * Only the fields needed for Unigram encoding are decoded; everything else is skipped.
 */
class SentencePieceModel(
    val pieces: List<Piece>,
    val precompiledCharsMap: ByteArray,
    val addDummyPrefix: Boolean,
    val removeExtraWhitespaces: Boolean,
    val escapeWhitespaces: Boolean,
    val modelType: Int,
) {
    class Piece(val text: String, val score: Float, val type: Int)

    companion object {
        const val TYPE_NORMAL = 1
        const val TYPE_UNKNOWN = 2
        const val TYPE_CONTROL = 3
        const val TYPE_USER_DEFINED = 4
        const val MODEL_TYPE_UNIGRAM = 1

        fun parse(bytes: ByteArray): SentencePieceModel {
            val pieces = ArrayList<Piece>(250_002)
            var charsMap = ByteArray(0)
            var addDummyPrefix = true
            var removeExtraWhitespaces = true
            var escapeWhitespaces = true
            var modelType = MODEL_TYPE_UNIGRAM

            val top = ProtoReader(bytes, 0, bytes.size)
            while (top.hasMore()) {
                val tag = top.readVarint().toInt()
                when (tag ushr 3) {
                    1 -> pieces += parsePiece(top.readLengthDelimited())
                    2 -> { // TrainerSpec: model_type = field 3
                        val r = top.readLengthDelimited()
                        while (r.hasMore()) {
                            val t = r.readVarint().toInt()
                            if (t ushr 3 == 3) modelType = r.readVarint().toInt() else r.skip(t and 7)
                        }
                    }
                    3 -> { // NormalizerSpec
                        val r = top.readLengthDelimited()
                        while (r.hasMore()) {
                            val t = r.readVarint().toInt()
                            when (t ushr 3) {
                                2 -> charsMap = r.readLengthDelimited().remainingBytes()
                                3 -> addDummyPrefix = r.readVarint() != 0L
                                4 -> removeExtraWhitespaces = r.readVarint() != 0L
                                5 -> escapeWhitespaces = r.readVarint() != 0L
                                else -> r.skip(t and 7)
                            }
                        }
                    }
                    else -> top.skip(tag and 7)
                }
            }
            return SentencePieceModel(
                pieces, charsMap, addDummyPrefix, removeExtraWhitespaces, escapeWhitespaces, modelType,
            )
        }

        private fun parsePiece(r: ProtoReader): Piece {
            var text = ""
            var score = 0f
            var type = TYPE_NORMAL
            while (r.hasMore()) {
                val t = r.readVarint().toInt()
                when (t ushr 3) {
                    1 -> text = String(r.readLengthDelimited().remainingBytes(), Charsets.UTF_8)
                    2 -> score = Float.fromBits(r.readFixed32())
                    3 -> type = r.readVarint().toInt()
                    else -> r.skip(t and 7)
                }
            }
            return Piece(text, score, type)
        }
    }
}

/** Cursor over a protobuf-encoded byte range. */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
    fun hasMore() = pos < end

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun readFixed32(): Int {
        val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun readLengthDelimited(): ProtoReader {
        val len = readVarint().toInt()
        val r = ProtoReader(buf, pos, pos + len)
        pos += len
        return r
    }

    fun remainingBytes(): ByteArray = buf.copyOfRange(pos, end)

    /** Skips a field value of the given wire type (0 varint, 1 fixed64, 2 length-delimited, 5 fixed32). */
    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> { val len = readVarint().toInt(); pos += len }
            5 -> pos += 4
            else -> error("Unsupported protobuf wire type $wireType")
        }
    }
}

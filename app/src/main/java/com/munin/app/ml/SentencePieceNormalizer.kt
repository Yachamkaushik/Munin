package com.munin.app.ml

import java.io.ByteArrayOutputStream

/**
 * Port of SentencePiece's `Normalizer` driven by the model's precompiled charsmap
 * (for XLM-R / E5 this is `nmt_nfkc`).
 *
 * The charsmap blob is: uint32 LE trie byte size, then a Darts double-array trie
 * (uint32 units), then a pool of NUL-terminated UTF-8 replacement strings. The trie
 * maps an input byte prefix to an offset in that pool.
 */
class SentencePieceNormalizer(model: SentencePieceModel) {
    private val units: IntArray
    private val pool: ByteArray
    private val removeExtraWhitespaces = model.removeExtraWhitespaces
    private val addDummyPrefix = model.addDummyPrefix
    private val escapeWhitespaces = model.escapeWhitespaces

    init {
        val map = model.precompiledCharsMap
        if (map.size < 4) {
            units = IntArray(0)
            pool = ByteArray(0)
        } else {
            val trieBytes = le32(map, 0)
            units = IntArray(trieBytes / 4) { le32(map, 4 + it * 4) }
            pool = map.copyOfRange(4 + trieBytes, map.size)
        }
    }

    fun normalize(text: String): String {
        val input = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(input.size + 8)
        var pos = 0

        if (removeExtraWhitespaces) { // drop leading whitespace (after normalization)
            while (pos < input.size) {
                val m = matchPrefix(input, pos)
                if (!(m.replacementLen == 1 && pool[m.replacementStart] == SPACE)) break
                pos += m.consumed
            }
        }
        if (pos >= input.size) return ""

        if (addDummyPrefix) writeSpace(out)

        var isPrevSpace = removeExtraWhitespaces
        while (pos < input.size) {
            val m = matchPrefix(input, pos)
            var start = m.replacementStart
            var len = m.replacementLen
            val src = m.replacementSource
            while (isPrevSpace && len > 0 && src[start] == SPACE) { start++; len-- }
            if (len > 0) {
                for (i in start until start + len) {
                    if (escapeWhitespaces && src[i] == SPACE) writeSpace(out) else out.write(src[i].toInt())
                }
                isPrevSpace = src[start + len - 1] == SPACE
            }
            pos += m.consumed
            if (!removeExtraWhitespaces) isPrevSpace = false
        }

        var bytes = out.toByteArray()
        if (removeExtraWhitespaces) {
            val sp = if (escapeWhitespaces) SPACE_SYMBOL_BYTES else byteArrayOf(SPACE)
            while (bytes.size >= sp.size && endsWith(bytes, sp)) bytes = bytes.copyOf(bytes.size - sp.size)
        }
        return String(bytes, Charsets.UTF_8)
    }

    private class Match(
        val replacementSource: ByteArray,
        val replacementStart: Int,
        val replacementLen: Int,
        val consumed: Int,
    )

    /** Longest charsmap match at [pos]; with no match, passes one UTF-8 character through unchanged. */
    private fun matchPrefix(input: ByteArray, pos: Int): Match {
        var longestLen = 0
        var longestValue = 0
        if (units.isNotEmpty()) {
            var node = 0
            node = node xor offset(units[0])
            var i = 0
            while (pos + i < input.size) {
                val k = input[pos + i].toInt() and 0xFF
                node = node xor k
                if (node < 0 || node >= units.size) break
                val unit = units[node]
                if (label(unit) != k) break
                node = node xor offset(unit)
                i++
                if ((unit ushr 8) and 1 == 1 && node in units.indices) {
                    longestLen = i
                    longestValue = units[node] and 0x7FFFFFFF
                }
            }
        }
        if (longestLen == 0) {
            val lead = input[pos].toInt() and 0xFF
            val n = when {
                lead < 0x80 -> 1
                lead < 0xE0 -> 2
                lead < 0xF0 -> 3
                else -> 4
            }.coerceAtMost(input.size - pos)
            return Match(input, pos, n, n)
        }
        var end = longestValue
        while (pool[end] != 0.toByte()) end++
        return Match(pool, longestValue, end - longestValue, longestLen)
    }

    private fun writeSpace(out: ByteArrayOutputStream) {
        if (escapeWhitespaces) out.write(SPACE_SYMBOL_BYTES) else out.write(SPACE.toInt())
    }

    private fun endsWith(a: ByteArray, suffix: ByteArray): Boolean {
        for (i in suffix.indices) if (a[a.size - suffix.size + i] != suffix[i]) return false
        return true
    }

    private companion object {
        const val SPACE: Byte = 0x20
        val SPACE_SYMBOL_BYTES = "▁".toByteArray(Charsets.UTF_8)

        fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        // Darts-clone double-array unit accessors.
        fun offset(unit: Int) = (unit ushr 10) shl ((unit and 0x200) shr 6)
        fun label(unit: Int) = unit and (Int.MIN_VALUE or 0xFF)
    }
}

package com.munin.app.index

import com.munin.app.data.VectorCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    private val words = { s: String -> s.split(' ').count { it.isNotEmpty() } }

    @Test fun emptyAndBlankTextGiveNoChunks() {
        assertEquals(emptyList<String>(), Chunker(10, words).split(""))
        assertEquals(emptyList<String>(), Chunker(10, words).split("  \n \t\n"))
    }

    @Test fun aScreenshotIsOneChunkAndWhitespaceIsTidied() {
        val chunks = Chunker(50, words).split("Hostel   Fee\n\n  Paid  45,000 \nSep 12")
        assertEquals(listOf("Hostel Fee\nPaid 45,000\nSep 12"), chunks)
    }

    @Test fun longTextSplitsOnLineBoundariesWithinBudget() {
        val text = (1..12).joinToString("\n") { "line $it has five tokens" } // 5 tokens each
        val chunks = Chunker(maxTokens = 12, countTokens = words).split(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { words(it) <= 12 })
        assertEquals(text, chunks.joinToString("\n")) // nothing lost, nothing reordered
    }

    @Test fun anOverlongSingleLineIsSplitOnWords() {
        val chunks = Chunker(maxTokens = 5, countTokens = words).split((1..23).joinToString(" "))
        assertTrue(chunks.all { words(it) <= 5 })
        assertEquals((1..23).joinToString(" "), chunks.joinToString(" "))
    }

    @Test fun vectorCodecRoundTrips() {
        val v = floatArrayOf(0.5f, -1.25f, 0f, 3.1415927f, Float.MIN_VALUE)
        assertArrayEquals(v, VectorCodec.decode(VectorCodec.encode(v)), 0f)
        assertEquals(5 * 4, VectorCodec.encode(v).size)
    }
}

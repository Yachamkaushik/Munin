package com.munin.app.index

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrLinesTest {
    @Test fun dropsLowConfidenceAndBlankLines() {
        val lines = listOf(OcrLine("Amount due: 1,250", 0.86f), OcrLine("3OoH vdo", 0.22f), OcrLine("  ", 0.9f), OcrLine("12 Sep 2026", 0.5f))
        assertEquals(listOf("Amount due: 1,250", "12 Sep 2026"), OcrLines.keep(lines).map { it.text })
    }

    @Test fun meanConfidenceOfNothingIsZero() {
        assertEquals(0f, OcrLines.meanConfidence(emptyList()), 0f)
        assertEquals(0.75f, OcrLines.meanConfidence(listOf(OcrLine("a", 0.5f), OcrLine("b", 1f))), 1e-6f)
    }
}

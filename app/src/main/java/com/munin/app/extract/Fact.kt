package com.munin.app.extract

enum class FactType { AMOUNT, DATE, PHONE, ADDRESS }

/**
 * A value read out of an item's text.
 *
 * [value] is normalized so it can be used exactly: amounts are plain decimals ("45000", "1250.50"), dates are ISO
 * ("2026-09-12", with "T18:45" when a time was found, or "--09-12" when the year is missing), phones are "+91..." or
 * bare digits. [raw] is the text as read, [label] is the words that introduced it ("Amount paid"), and [line] is the
 * index of the line it came from. [confidence] is a rule-based 0..1 estimate, not a probability.
 */
data class ExtractedFact(
    val type: FactType,
    val value: String,
    val raw: String,
    val label: String?,
    val line: Int,
    val confidence: Float,
)

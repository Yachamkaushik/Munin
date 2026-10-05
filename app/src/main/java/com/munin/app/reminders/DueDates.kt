package com.munin.app.reminders

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A date fact read from an indexed image, with enough context to name it. */
data class DueCandidate(val factId: Long, val itemId: Long, val label: String?, val isoDate: String, val raw: String, val confidence: Float, val itemName: String)

/** A date worth offering a reminder for. [key] identifies it so a dismissal sticks. */
data class ReminderSuggestion(val key: String, val candidate: DueCandidate, val date: LocalDate, val daysAway: Long)

/**
 * Picks the upcoming dates that look like deadlines ("Due date", "Valid till", "Last date", "Renewal") out of the dates Munin already read.
 * Pure: nothing here creates anything. A suggestion becomes a calendar entry only when the user taps it and confirms (see the planner's calendar plan).
 */
object DueDates {
    const val WINDOW_DAYS = 60L
    const val MAX_SUGGESTIONS = 5

    private val DUE_LABEL = Regex(
        "\\bdue\\b|expir|valid\\s*(?:till|until|up\\s?to|thru|through)|last\\s+date|deadline|renew|pay\\s+(?:by|before)|submit\\s+(?:by|before)|గడువు|చివరి తేదీ|अंतिम\\s*(?:तिथि|तारीख)|देय|समाप्ति|नवीनीकरण",
        RegexOption.IGNORE_CASE,
    )

    fun looksLikeDeadline(label: String?) = label != null && DUE_LABEL.containsMatchIn(label)

    /** Same date and label means the same reminder, even from several copies of one document, so it is offered (and dismissed) once. */
    fun keyOf(c: DueCandidate) = "${c.isoDate}|${c.label?.trim()?.trimEnd(':')?.lowercase().orEmpty()}"

    fun suggest(candidates: List<DueCandidate>, today: LocalDate, dismissed: Set<String>, windowDays: Long = WINDOW_DAYS): List<ReminderSuggestion> =
        candidates.asSequence()
            .filter { looksLikeDeadline(it.label) && !it.isoDate.startsWith("--") }
            .mapNotNull { c -> runCatching { LocalDate.parse(c.isoDate) }.getOrNull()?.let { c to it } }
            .map { (c, d) -> ReminderSuggestion(keyOf(c), c, d, ChronoUnit.DAYS.between(today, d)) }
            .filter { it.daysAway in 0..windowDays && it.key !in dismissed }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.daysAway }, { it.candidate.itemName }))
            .take(MAX_SUGGESTIONS)
            .toList()
}

package com.munin.app.reminders

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DueDatesTest {
    private val today = LocalDate.of(2026, 10, 5)
    private fun c(id: Long, label: String?, date: String, item: Long = id, name: String = "bill$id.png") = DueCandidate(id, item, label, date, date, 0.9f, name)

    @Test fun deadlineLabelsInThreeLanguages() {
        for (l in listOf("Due date", "Due Date:", "Valid till", "Valid up to", "Expires on", "Expiry", "Last date", "Renewal date", "Pay by", "గడువు తేదీ", "అంతిమ గడువు", "अंतिम तिथि", "देय तिथि"))
            assertTrue(l, DueDates.looksLikeDeadline(l))
        for (l in listOf("Date", "Paid on", "Issued", "Collected on", "Dated", null)) assertFalse(l.toString(), DueDates.looksLikeDeadline(l))
    }

    @Test fun onlyUpcomingDeadlinesWithinTheWindowAreSuggestedSoonestFirst() {
        val out = DueDates.suggest(
            listOf(c(1, "Due date", "2026-10-15"), c(2, "Due date", "2026-10-05"), c(3, "Due date", "2026-10-04"), c(4, "Due date", "2027-03-01"), c(5, "Paid on", "2026-10-10"), c(6, "Last date", "2026-12-04")),
            today, emptySet(),
        )
        assertEquals(listOf(2L, 1L, 6L), out.map { it.candidate.factId })
        assertEquals(listOf(0L, 10L, 60L), out.map { it.daysAway })
    }

    @Test fun yearlessAndMalformedDatesAreSkipped() =
        assertTrue(DueDates.suggest(listOf(c(1, "Due date", "--10-15"), c(2, "Due date", "2026-13-45"), c(3, "Due date", "garbage")), today, emptySet()).isEmpty())

    @Test fun copiesOfOneDocumentAreOfferedOnce() {
        val out = DueDates.suggest((1L..20L).map { c(it, "Due date:", "2026-10-15") }, today, emptySet())
        assertEquals(1, out.size)
    }

    @Test fun dismissedSuggestionsStayGone() {
        val first = DueDates.suggest(listOf(c(1, "Due date", "2026-10-15")), today, emptySet()).single()
        assertTrue(DueDates.suggest(listOf(c(1, "Due date", "2026-10-15")), today, setOf(first.key)).isEmpty())
    }

    @Test fun atMostFiveAreShown() =
        assertEquals(5, DueDates.suggest((1L..9L).map { c(it, "Due date", "2026-10-${10 + it}") }, today, emptySet()).size)
}

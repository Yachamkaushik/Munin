package com.munin.app.ledger

import com.munin.app.search.QueryTerms
import java.time.LocalDate
import java.time.YearMonth
import com.munin.app.extract.FactExtractor

/** "how much did I spend in September": which month (or all of them) the user means. */
data class SpendingQuery(val monthNumber: Int?, val year: Int?, val relative: Relative?) {
    enum class Relative { THIS_MONTH, LAST_MONTH }

    /**
     * Resolves to a concrete month. A month name without a year means the most recent such month that is not in the
     * future, preferring one that actually has payments. Null means "all payments read".
     */
    fun resolve(today: LocalDate, monthsWithData: Collection<YearMonth>): YearMonth? {
        val now = YearMonth.from(today)
        return when {
            relative == Relative.THIS_MONTH -> now
            relative == Relative.LAST_MONTH -> now.minusMonths(1)
            monthNumber == null -> null
            year != null -> YearMonth.of(year, monthNumber)
            else -> monthsWithData.filter { it.monthValue == monthNumber && it <= now }.maxOrNull()
                ?: YearMonth.of(if (monthNumber <= now.monthValue) now.year else now.year - 1, monthNumber)
        }
    }

    companion object {
        private val SPENDING = Regex("\\bspend\\b|\\bspent\\b|\\bspending\\b|\\bexpenses?\\b|खर्च|ఖర్చు|\\bkharcha\\b|\\bkharch\\b", RegexOption.IGNORE_CASE)
        private val THIS_MONTH = Regex("this month|इस महीने|ఈ నెల|\\bis mahine\\b|\\bee nela\\b", RegexOption.IGNORE_CASE)
        private val LAST_MONTH = Regex("last month|पिछले महीने|గత నెల|\\bpichle mahine\\b|\\bgata nela\\b", RegexOption.IGNORE_CASE)

        /** Null unless the query is about spending. */
        fun parse(query: String): SpendingQuery? {
            if (!SPENDING.containsMatchIn(query)) return null
            val relative = when {
                THIS_MONTH.containsMatchIn(query) -> Relative.THIS_MONTH
                LAST_MONTH.containsMatchIn(query) -> Relative.LAST_MONTH
                else -> null
            }
            val tokens = QueryTerms.tokens(query, keepAll = true)
            val month = tokens.firstNotNullOfOrNull { FactExtractor.monthNumber(it) }
            val year = tokens.firstOrNull { it.length == 4 && it.all(Char::isDigit) && it.toInt() in 2000..2100 }?.toInt()
            return SpendingQuery(month, year, relative)
        }
    }
}

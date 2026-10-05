package com.munin.app.router

import com.munin.app.answer.QuestionParser
import com.munin.app.extract.FactType
import com.munin.app.ledger.SpendingQuery
import com.munin.app.search.SearchMode

/**
 * What the search box input is asking for. Only kinds that are actually implemented appear here; each later feature (calculator, apps,
 * contacts, settings, commands) adds its own kind when it is built, so the "what I understood" line never claims something Munin cannot do.
 */
enum class RouteKind {
    /** Look for files whose text matches or is close in meaning to the input. Always applies to non-blank input. */
    FILE_SEARCH,
    /** Asks for a value (amount, date, phone, address): answer from the best matching file, or decline. */
    QUESTION,
    /** "How much did I spend ...": add up payment screenshots. */
    SPENDING,
}

/** The router's reading of one input. [understood] is shown under the search box in plain words. */
data class RouteDecision(val kinds: List<RouteKind>, val understood: String?) {
    fun has(kind: RouteKind) = kind in kinds
}

object QueryRouter {
    fun route(input: String, mode: SearchMode = SearchMode.MERGED): RouteDecision {
        val q = input.trim()
        if (q.isEmpty()) return RouteDecision(emptyList(), null)

        SpendingQuery.parse(q)?.let { s ->
            val scope = when {
                s.relative == SpendingQuery.Relative.THIS_MONTH -> "this month"
                s.relative == SpendingQuery.Relative.LAST_MONTH -> "last month"
                s.monthNumber != null -> "the month you named"
                else -> "all months"
            }
            return RouteDecision(listOf(RouteKind.SPENDING, RouteKind.FILE_SEARCH), "A spending question: adding up the payment screenshots Munin could read for $scope. Matching files are listed too.")
        }

        QuestionParser.parse(q)?.let { question ->
            val what = when (question.kind) {
                FactType.AMOUNT -> "an amount"
                FactType.DATE -> "a date"
                FactType.PHONE -> "a phone number"
                FactType.ADDRESS -> "an address"
            }
            return RouteDecision(listOf(RouteKind.QUESTION, RouteKind.FILE_SEARCH), "A question asking for $what: looking in your files, and answering from the best match only if it is clearly the right one.")
        }

        val how = when (mode) {
            SearchMode.MERGED -> "by words and by meaning"
            SearchMode.MEANING -> "by meaning only"
            SearchMode.KEYWORDS -> "by exact words only"
        }
        return RouteDecision(listOf(RouteKind.FILE_SEARCH), "Looking in your files for “$q”, $how.")
    }
}

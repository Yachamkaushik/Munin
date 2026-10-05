package com.munin.app.router

import com.munin.app.answer.QuestionParser
import com.munin.app.apps.AppMatch
import com.munin.app.contacts.ContactEntry
import com.munin.app.shortcuts.SettingsShortcut
import com.munin.app.calc.CalcOutcome
import com.munin.app.calc.Calculator
import com.munin.app.extract.FactType
import com.munin.app.ledger.SpendingQuery
import com.munin.app.search.SearchMode

/**
 * What the search box input is asking for. Only kinds that are actually implemented appear here; each later feature (calculator, apps,
 * contacts, settings, commands) adds its own kind when it is built, so the "what I understood" line never claims something Munin cannot do.
 */
enum class RouteKind {
    /** The input names an installed app: offered above the file results, launched on tap. */
    APP,
    /** The input is a saved contact's name or a family nickname (amma, nanna): shown with a confirm-first call. */
    CONTACT,
    /** The input names a system settings screen (wifi, bluetooth): one tap opens it. */
    SETTINGS,
    /** Arithmetic, a unit conversion, date maths or a currency conversion with the user's own rate: worked out on the phone, no file search. */
    CALCULATOR,
    /** Look for files whose text matches or is close in meaning to the input. Always applies to non-blank input. */
    FILE_SEARCH,
    /** Asks for a value (amount, date, phone, address): answer from the best matching file, or decline. */
    QUESTION,
    /** "How much did I spend ...": add up payment screenshots. */
    SPENDING,
}

/** The router's reading of one input. [understood] is shown under the search box in plain words. */
data class RouteDecision(val kinds: List<RouteKind>, val understood: String?, val calc: CalcOutcome? = null, val apps: List<AppMatch> = emptyList(), val contacts: List<ContactEntry> = emptyList(), val settings: List<SettingsShortcut> = emptyList()) {
    fun has(kind: RouteKind) = kind in kinds
}

object QueryRouter {
    /**
     * [calculator] is asked first: a calculation is not searched for in files. [allowCalculator] is false when the user chose "search my files
     * for this instead", so a query like "2026-45" can still be searched.
     */
    fun route(input: String, mode: SearchMode = SearchMode.MERGED, calculator: Calculator? = Calculator(), allowCalculator: Boolean = true, apps: List<AppMatch> = emptyList(), contacts: List<ContactEntry> = emptyList(), settings: List<SettingsShortcut> = emptyList()): RouteDecision {
        val q = input.trim()
        if (q.isEmpty()) return RouteDecision(emptyList(), null)

        if (allowCalculator) calculator?.evaluate(q)?.let { c ->
            val understood = when (c) {
                is CalcOutcome.Value -> "A calculation, worked out on this phone."
                is CalcOutcome.Failed -> "This looks like a calculation, but it cannot be worked out."
                is CalcOutcome.RateProposal -> "You typed an exchange rate. Nothing is saved unless you choose to save it."
            }
            return RouteDecision(listOf(RouteKind.CALCULATOR), understood, c)
        }

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
        if (apps.isNotEmpty() || contacts.isNotEmpty() || settings.isNotEmpty()) {
            val kinds = buildList {
                if (settings.isNotEmpty()) add(RouteKind.SETTINGS)
                if (contacts.isNotEmpty()) add(RouteKind.CONTACT)
                if (apps.isNotEmpty()) add(RouteKind.APP)
                add(RouteKind.FILE_SEARCH)
            }
            val what = buildList {
                if (settings.isNotEmpty()) add("the settings screen “${settings.first().label}”")
                if (contacts.isNotEmpty()) add("the contact “${contacts.first().name}”")
                if (apps.isNotEmpty()) add("the app “${apps.first().app.label}”")
            }.joinToString(" or ")
            return RouteDecision(kinds, "Matches $what. Also looking in your files for “$q”, $how.", apps = apps, contacts = contacts, settings = settings)
        }
        return RouteDecision(listOf(RouteKind.FILE_SEARCH), "Looking in your files for “$q”, $how.")
    }
}

package com.munin.app.router

import com.munin.app.actions.ActionKind
import com.munin.app.actions.ActionPayload
import com.munin.app.actions.ActionPlanner
import com.munin.app.search.SearchMode
import com.munin.app.search.SearchResult
import com.munin.app.search.Snippet
import com.munin.app.search.WhyThis
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryRouterTest {
    @Test fun anEmptyBoxHasNothingToUnderstand() {
        assertEquals(RouteDecision(emptyList(), null), QueryRouter.route("   "))
    }

    @Test fun aPlainDescriptionIsAFileSearch() {
        val d = QueryRouter.route("hostel fee receipt")
        assertEquals(listOf(RouteKind.FILE_SEARCH), d.kinds)
        assertTrue(d.understood!!, d.understood!!.contains("“hostel fee receipt”") && d.understood!!.contains("words and by meaning"))
    }

    @Test fun theLegsChipChangesTheWordingNotTheKind() {
        assertTrue(QueryRouter.route("hostel fee", SearchMode.MEANING).understood!!.contains("by meaning only"))
        assertTrue(QueryRouter.route("hostel fee", SearchMode.KEYWORDS).understood!!.contains("exact words only"))
    }

    @Test fun valueQuestionsAreNamedByWhatTheyAskFor() {
        val amount = QueryRouter.route("how much was the hostel fee")
        assertEquals(listOf(RouteKind.QUESTION, RouteKind.FILE_SEARCH), amount.kinds)
        assertTrue(amount.understood!!.contains("an amount"))
        assertTrue(QueryRouter.route("when is the electricity bill due").understood!!.contains("a date"))
        assertTrue(QueryRouter.route("clinic phone number").understood!!.contains("a phone number"))
        assertTrue(QueryRouter.route("clinic address").understood!!.contains("an address"))
        assertTrue(QueryRouter.route("హాస్టల్ ఫీజు ఎంత").understood!!.contains("an amount")) // the same in Telugu
    }

    @Test fun spendingQuestionsAreRecognisedWithTheirScope() {
        val d = QueryRouter.route("how much did I spend in September")
        assertEquals(listOf(RouteKind.SPENDING, RouteKind.FILE_SEARCH), d.kinds)
        assertTrue(d.understood!!.contains("the month you named"))
        assertTrue(QueryRouter.route("how much did I spend this month").understood!!.contains("this month"))
        assertTrue(QueryRouter.route("how much did I spend").understood!!.contains("all months"))
    }

    @Test fun neverClaimsAKindThatIsNotBuilt() { // later steps add kinds; today these are the only ones
        assertEquals(setOf("APP", "CALCULATOR", "FILE_SEARCH", "QUESTION", "SPENDING"), RouteKind.entries.map { it.name }.toSet())
    }
}

class RouterAppTest {
    private val chrome = com.munin.app.apps.AppMatch(com.munin.app.apps.AppEntry("Chrome", "com.android.chrome", "a/b"), 1.0)

    @Test fun anAppMatchIsOfferedAlongsideTheFileSearch() {
        val d = QueryRouter.route("chrome", apps = listOf(chrome))
        assertEquals(listOf(RouteKind.APP, RouteKind.FILE_SEARCH), d.kinds)
        assertTrue(d.understood!!.contains("Chrome"))
    }

    @Test fun noAppMatchLeavesRoutingUnchanged() {
        assertEquals(listOf(RouteKind.FILE_SEARCH), QueryRouter.route("hostel fee").kinds)
    }
}

class RouterCalculatorTest {
    private val calc = com.munin.app.calc.Calculator({ java.time.LocalDate.of(2026, 10, 5) })

    @Test fun aCalculationIsAnsweredWithoutAFileSearch() {
        val d = QueryRouter.route("20% of 4500", calculator = calc)
        assertEquals(listOf(RouteKind.CALCULATOR), d.kinds)
        assertEquals("A calculation, worked out on this phone.", d.understood)
        assertTrue(d.calc is com.munin.app.calc.CalcOutcome.Value)
    }

    @Test fun theUserCanOptOutOfTheCalculatorForOneInput() {
        assertEquals(listOf(RouteKind.FILE_SEARCH), QueryRouter.route("20% of 4500", calculator = calc, allowCalculator = false).kinds)
    }

    @Test fun ordinaryQuestionsAndSearchesStillGoWhereTheyDid() {
        assertEquals(listOf(RouteKind.QUESTION, RouteKind.FILE_SEARCH), QueryRouter.route("how much was the hostel fee", calculator = calc).kinds)
        assertEquals(listOf(RouteKind.FILE_SEARCH), QueryRouter.route("hostel fee 45,000", calculator = calc).kinds)
        assertEquals(listOf(RouteKind.FILE_SEARCH), QueryRouter.route("2026-09-12", calculator = calc).kinds)
    }

    @Test fun aFailedCalculationIsStillACalculationWithAnHonestMessage() {
        val d = QueryRouter.route("100 usd in inr", calculator = calc) // no rate saved
        assertEquals(listOf(RouteKind.CALCULATOR), d.kinds); assertTrue(d.calc is com.munin.app.calc.CalcOutcome.Failed)
    }

    @Test fun aTypedRateIsNotSaved() {
        val d = QueryRouter.route("1 usd = 83.5 inr", calculator = calc)
        assertTrue(d.calc is com.munin.app.calc.CalcOutcome.RateProposal); assertTrue(d.understood!!.contains("Nothing is saved"))
    }
}

class WhyThisTest {
    private fun result(text: String, highlights: List<IntRange>, kw: Int?, meaning: Int?, name: String = "Screenshot_20260912.png", date: Long? = SEP_12) =
        SearchResult(1, "u", name, 1, Snippet(text, highlights), meaning, meaning?.let { 0.9f }, kw, kw?.let { 1f }, 0.0, date)

    private val utc = ZoneId.of("UTC")
    private companion object { val SEP_12 = java.time.LocalDate.of(2026, 9, 12).atStartOfDay(ZoneOffset.UTC).toEpochSecond() }

    @Test fun matchedWordsAreQuotedWithTheirSourceAndDate() {
        val w = WhyThis.explain(result("Hostel Fee Receipt", listOf(0..5, 7..9), kw = 1, meaning = null), utc)
        assertEquals("Matched “Hostel”, “Fee” in the text in your screenshot from 12 Sep.", w)
    }

    @Test fun matchedWordsPlusMeaning() {
        val w = WhyThis.explain(result("Hostel Fee Receipt", listOf(0..5), kw = 1, meaning = 2), utc)
        assertTrue(w, w.startsWith("Matched “Hostel”") && w.endsWith("and it is close in meaning to your search."))
    }

    @Test fun meaningOnlyAdmitsThereWereNoMatchingWords() {
        val w = WhyThis.explain(result("छात्रावास शुल्क रसीद", emptyList(), kw = null, meaning = 1), utc)
        assertEquals("No exact words matched; the text in your screenshot from 12 Sep is close in meaning to your search.", w)
    }

    @Test fun anImageThatIsNotNamedScreenshotIsCalledAnImageAndAMissingDateIsLeftOut() {
        val w = WhyThis.explain(result("x", listOf(0..0), kw = 1, meaning = null, name = "IMG_0042.jpg", date = null), utc)
        assertEquals("Matched “x” in the text in your image.", w)
    }

    @Test fun repeatedMatchesAreListedOnceAndCappedAtThree() {
        val w = WhyThis.explain(result("aaa bbb ccc ddd aaa", listOf(0..2, 4..6, 8..10, 12..14, 16..18), kw = 1, meaning = null), utc)
        assertEquals("Matched “aaa”, “bbb”, “ccc” in the text in your screenshot from 12 Sep.", w)
    }
}

class WebSearchPlanTest {
    @Test fun theWebPlanSaysItLeavesThePhoneAndShowsTheExactText() {
        val plan = ActionPlanner().webSearch("hostel fee receipt")
        assertEquals(ActionKind.WEB, plan.kind)
        assertEquals("Search text" to "hostel fee receipt", plan.details.single())
        assertTrue(plan.notes.single().contains("leaves your phone"))
        assertEquals(ActionPayload.Web("hostel fee receipt"), plan.payload)
    }

    @Test fun factPlansNeverIncludeTheWeb() {
        assertFalse(ActionKind.WEB in ActionPlanner().plans(
            com.munin.app.actions.ActionSubject(com.munin.app.extract.FactType.AMOUNT, "1250", null, "1250", "Bill", "b.png", false),
        ).map { it.kind })
        assertNull(null)
    }
}

package com.munin.app.calc

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorTest {
    private val today = LocalDate.of(2026, 10, 5) // a Monday
    private val book = MemoryRateBook().also { it.put(Rate("USD", "INR", BigDecimal("83.5"), LocalDate.of(2026, 10, 3))) }
    private val calc = Calculator({ today }, book)
    private fun value(q: String) = calc.evaluate(q) as CalcOutcome.Value
    private fun primary(q: String) = value(q).primary

    // ---- units ----

    @Test fun commonUnits() {
        assertEquals("3.106856 mi", primary("5 km in miles")); assertEquals("220.462262 lb", primary("100 kg to lb")); assertEquals("120 min", primary("2 hours in minutes"))
        assertEquals("86 °F", primary("30 c to f")); assertEquals("37 °C", primary("98.6 f in c")); assertEquals("1,024 MB", primary("1 gb in mb")); assertEquals("27.777778 m/s", primary("100 kmph in m/s"))
        assertEquals("3.106856 mi", primary("convert 5 km to mi"))
    }

    @Test fun indianLandAndWeightUnits() {
        assertEquals("43,560 sq ft", primary("1 acre in sq ft")); assertEquals("40 guntha", primary("1 acre in guntha")); assertEquals("1,089 sq ft", primary("1 guntha in sq ft"))
        assertEquals("435.6 sq ft", primary("1 cent in sq ft")); assertEquals("9 sq ft", primary("1 gaj in sq ft")); assertEquals("100 kg", primary("1 quintal in kg"))
        assertEquals("11.663804 g", primary("1 tola in g")); assertEquals("1,00,000 sq ft", primary("100000 sqft in sq ft"))
    }

    @Test fun unitsShowTheOneUnitFactorAndTheLakhReading() {
        assertEquals(listOf("1 km = 0.621371 mi"), value("5 km in miles").secondary)
        assertEquals(listOf("1 m = 100 cm", "= 25 crore cm"), value("2500000 m in cm").secondary)
    }

    @Test fun unitsOfDifferentKindsAreAnErrorNotAGuess() {
        val f = calc.evaluate("5 km in kg") as CalcOutcome.Failed
        assertTrue(f.message, f.message.contains("length") && f.message.contains("mass"))
    }

    @Test fun unknownUnitsAndNonCurrenciesAreNotACalculation() { assertNull(calc.evaluate("5 foo in bar")); assertNull(calc.evaluate("5 apples to pears")); assertNull(calc.evaluate("1 box = 12 pcs")) }

    @Test fun devanagariDigitsInUnits() = assertEquals("3.106856 mi", primary("५ km in miles"))

    // ---- dates ----

    @Test fun daysUntilAndSince() {
        val d = value("days until 15 october"); assertEquals("10 days until Thu 15 Oct 2026", d.primary)
        assertEquals("23 days since Sat 12 Sep 2026", primary("days since 12 sep")); assertEquals("1 day until Tue 6 Oct 2026", primary("how many days until tomorrow"))
        assertEquals("Today", primary("days until today"))
    }

    @Test fun aDateWithoutAYearMeansTheNextOccurrence() = assertEquals("361 days until Fri 1 Oct 2027", primary("days until 1 october"))

    @Test fun daysBetweenTwoDates() = assertEquals("287 days", primary("days between 1 jan and 15 oct"))

    @Test fun addingAndSubtractingTime() {
        assertEquals("Sun 3 Jan 2027", primary("today + 90 days")); assertEquals("Thu 19 Nov 2026", primary("45 days from today")); assertEquals("Mon 19 Oct 2026", primary("in 2 weeks"))
        assertEquals("Mon 21 Sep 2026", primary("today - 2 weeks")); assertEquals("Tue 6 Oct 2026", primary("tomorrow")); assertEquals("Mon 5 Oct 2026", primary("today"))
    }

    @Test fun hindiAndTeluguMonthNamesWorkInDates() { assertEquals("10 days until Thu 15 Oct 2026", primary("days until 15 अक्टूबर")); assertEquals("10 days until Thu 15 Oct 2026", primary("days until 15 అక్టోబర్")) }

    @Test fun datesAreNotArithmetic() { assertNull(calc.evaluate("2026-09-12")); assertNull(calc.evaluate("15 october")); assertNull(calc.evaluate("12/09/2026")) }

    // ---- currency: only with the user's own rate ----

    @Test fun convertsWithASavedRateAndShowsItsAge() {
        val v = value("100 usd in inr"); assertEquals("8,350 INR", v.primary)
        assertEquals("Using your saved rate: 1 USD = 83.5 INR, saved 3 Oct (2 days ago).", v.secondary.single())
        assertEquals("8,350 INR", primary("\$100 to inr")); assertEquals("100 USD", primary("₹8350 in usd")) // the inverse of the saved rate
    }

    @Test fun anOldRateIsFlagged() {
        val old = MemoryRateBook().also { it.put(Rate("USD", "INR", BigDecimal("80"), LocalDate.of(2026, 7, 1))) }
        assertTrue(((Calculator({ today }, old).evaluate("1 usd in inr")) as CalcOutcome.Value).secondary.single().contains("This rate is old"))
    }

    @Test fun withoutARateItSaysSoAndNeverInventsOne() {
        val f = calc.evaluate("100 usd in eur") as CalcOutcome.Failed
        assertTrue(f.message, f.message.contains("No exchange rate") && f.message.contains("no internet access") && f.message.contains("1 USD = 83.5 EUR"))
        assertTrue((Calculator({ today }, null).evaluate("100 usd in inr") as CalcOutcome.Failed).message.contains("No exchange rate"))
    }

    @Test fun aTypedRateIsOnlyProposedUntilTheUserSavesIt() {
        val p = calc.evaluate("1 eur = 90 inr") as CalcOutcome.RateProposal
        assertEquals("EUR", p.from); assertEquals("INR", p.to); assertEquals(0, p.rate.compareTo(BigDecimal(90)))
        assertNull(book.get("EUR", "INR")) // nothing saved yet
        calc.save(p); assertEquals(0, book.get("EUR", "INR")!!.value.compareTo(BigDecimal(90))); assertEquals(today, book.get("EUR", "INR")!!.savedOn)
        assertEquals(0, ((calc.evaluate("10 usd = 835 inr") as CalcOutcome.RateProposal).rate).compareTo(BigDecimal("83.5")))
    }

    // ---- arithmetic through the facade, and what is not a calculation ----

    @Test fun arithmeticShowsTheIndianReading() {
        val v = value("2.5 lakh * 10"); assertEquals("25,00,000", v.primary); assertEquals(listOf("= 25 lakh"), v.secondary); assertEquals("Worked out: 2.5 lakh * 10", v.reading)
        assertEquals("900", primary("20% of 4500")); assertEquals("2,50,000", primary("दो लाख पचास हज़ार")); assertEquals("45,000", primary("నలభై ఐదు వేలు"))
    }

    @Test fun divisionByZeroIsExplained() = assertTrue((calc.evaluate("5 / 0") as CalcOutcome.Failed).message.contains("divide by zero"))

    @Test fun ordinarySearchesAreLeftAlone() {
        for (q in listOf("hostel fee receipt", "wifi", "how much was the hostel fee", "45000", "Rs 45,000", "amma", "clinic phone number", "")) assertNull(q, calc.evaluate(q))
    }
}

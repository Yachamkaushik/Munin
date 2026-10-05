package com.munin.app.calc

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NumberWordsTest {
    private fun run(vararg w: String) = NumberWords.parseRun(w.toList(), 0)?.first?.stripTrailingZeros()?.toPlainString()

    @Test fun english() {
        assertEquals("25", run("twenty", "five")); assertEquals("250", run("two", "hundred", "and", "fifty"))
        assertEquals("2500000", run("twenty", "five", "lakh"));         assertEquals("100", run("hundred")); assertEquals("10000000", run("crore"))
    }

    @Test fun hindiHasADistinctWordForEveryNumberUpToNinetyNine() {
        val expected = mapOf("शून्य" to 0, "एक" to 1, "दो" to 2, "पाँच" to 5, "ग्यारह" to 11, "बीस" to 20, "इक्कीस" to 21, "पच्चीस" to 25, "उनतीस" to 29, "तीस" to 30, "पैंतीस" to 35,
            "चालीस" to 40, "उनचास" to 49, "पचास" to 50, "साठ" to 60, "उनहत्तर" to 69, "सत्तर" to 70, "उन्यासी" to 79, "अस्सी" to 80, "नवासी" to 89, "नब्बे" to 90, "निन्यानवे" to 99)
        for ((w, n) in expected) assertEquals(w, n.toString(), run(w))
    }

    @Test fun hindiCompounds() {
        assertEquals("250000", run("दो", "लाख", "पचास", "हज़ार")); assertEquals("150", run("एक", "सौ", "पचास")); assertEquals("50000000", run("पाँच", "करोड़"))
        assertEquals("150000", run("डेढ़", "लाख")); assertEquals("250000", run("ढाई", "लाख")); assertEquals("100000", run("लाख"))
    }

    @Test fun telugu() {
        assertEquals("25", run("ఇరవై", "ఐదు")); assertEquals("500", run("ఐదు", "వందలు")); assertEquals("200000", run("రెండు", "లక్షలు"))
        assertEquals("10000000", run("కోటి")); assertEquals("1000", run("వెయ్యి")); assertEquals("45000", run("నలభై", "ఐదు", "వేలు")); assertEquals("19", run("పంతొమ్మిది"))
    }

    @Test fun notNumberWordsAndStopAtTheFirstOtherWord() {
        assertNull(run("hostel")); assertEquals("5", run("five").also { assertEquals(1, NumberWords.parseRun(listOf("five", "apples"), 0)!!.second) })
        assertEquals(2, NumberWords.parseRun(listOf("five", "lakh", "rupees"), 0)!!.second)
    }

    @Test fun magnitudeSuffixes() {
        assertEquals(BigDecimal(1000), NumberWords.suffix("k")); assertEquals(BigDecimal(100000), NumberWords.suffix("lakh")); assertEquals(BigDecimal(10000000), NumberWords.suffix("cr"))
        assertNull(NumberWords.suffix("l")); assertNull(NumberWords.suffix("rupees"))
    }
}

class ExpressionTest {
    private fun v(input: String) = Expression.parse(input)?.value?.stripTrailingZeros()?.toPlainString()

    @Test fun basicArithmeticAndPrecedence() {
        assertEquals("14", v("2 + 3 * 4")); assertEquals("20", v("(2 + 3) * 4")); assertEquals("2.5", v("10 / 4")); assertEquals("8", v("2 ^ 3")); assertEquals("-1", v("2 - 3"))
        assertEquals("512", v("2 ^ 3 ^ 2")); assertEquals("12", v("3 x 4")); assertEquals("12", v("3×4")); assertEquals("5", v("10 ÷ 2")); assertEquals("0.5", v("2 ^ -1"))
    }

    @Test fun exactDecimalArithmetic() {
        assertEquals("0.3", v("0.1 + 0.2")); assertEquals("0.01", v("0.1 * 0.1")); assertEquals("3.3333333333", v("10 / 3")?.take(12))
    }

    @Test fun wordOperatorsAndFillerPhrases() {
        assertEquals("7", v("what is 3 plus 4")); assertEquals("12", v("3 times 4")); assertEquals("2", v("10 divided by 5")); assertEquals("6", v("calculate 10 minus 4")); assertEquals("20", v("5 into 4"))
    }

    @Test fun percentages() {
        assertEquals("900", v("20% of 4500")); assertEquals("5310", v("4500 + 18%")); assertEquals("4050", v("4500 - 10%")); assertEquals("450", v("4500 * 10%"))
        assertEquals("900", v("20 percent of 4500")); assertEquals("1000", v("10% of 20% of 50000")); assertEquals("100", v("(5+5)% of 1000"))
        assertEquals("36000", v("15% of 2,40,000")); assertEquals("5236", v("4400 + 19%"))
    }

    @Test fun indianNumbers() {
        assertEquals("250000", v("2.5 lakh + 0")); assertEquals("1000000", v("5 lakh * 2")); assertEquals("30000000", v("3 crore + 0")); assertEquals("500000", v("5 lakh"))
        assertEquals("10000", v("10k")); assertEquals("1500000", v("10 lakh + 5 lakh")); assertEquals("21500000", v("1.5 crore + 65 lakh"))
        assertEquals("6000", v("2,000 * 3")); assertNull(v("12,00,000")) // a bare number is not a calculation
    }

    @Test fun hindiAndTeluguNumberWords() {
        assertEquals("250000", v("दो लाख पचास हज़ार")); assertEquals("300000", v("दो लाख + एक लाख")); assertEquals("150000", v("डेढ़ लाख"))
        assertEquals("45000", v("నలభై ఐదు వేలు")); assertEquals("30", v("ఇరవై + పది")); assertEquals("1000", v("పది వందలు"))
        assertEquals("120", v("पच्चीस + पंचानवे")); assertEquals("25", v("twenty five"))
    }

    @Test fun devanagariAndTeluguDigits() {
        assertEquals("14", v("२ + ३ * ४")); assertEquals("900", v("20% of ४५००")); assertEquals("30", v("౧౦ + ౨౦"))
    }

    @Test fun parenthesesAndUnary() {
        assertEquals("-5", v("-(2+3)")); assertEquals("10", v("+(4+6)")); assertEquals("21", v("(1+2)*(3+4)"))
    }

    @Test fun ordinaryTextIsNotACalculation() {
        for (q in listOf("hostel fee receipt", "hostel fee 45,000", "45000", "2026-09-12", "12/09/2026", "98765 43210", "how much was the hostel fee", "amount 500", "wifi", "5 km in miles",
            "Rs 45,000", "", "   ", "+", "2 +", "(2", "1 2", "twenty apples + 3", "of 5", "5% ")) assertNull(q, v(q))
    }

    @Test fun phoneNumbersAndShortDatesAreNotSubtractions() {
        for (q in listOf("040-23456789", "12-09", "100/4", "98765-43210", "2026/10")) assertNull(q, v(q))
        assertEquals("80", v("100 - 20")); assertEquals("25", v("100 / 4")); assertEquals("80", v("100 -20".replace("-20", "- 20")))
    }

    @Test fun aNumberWordOnItsOwnIsConvertedToDigits() {
        assertEquals("250000", v("दो लाख पचास हज़ार")); assertNull(v("5")) // but a bare digit number is just a number
    }

    @Test fun divisionByZeroIsAnErrorNotACrash() {
        try { Expression.parse("5 / 0"); fail("expected CalcError") } catch (e: CalcError) { assertTrue(e.message!!, e.message!!.contains("divide by zero")) }
    }

    @Test fun displayGroupingIsIndian() {
        assertEquals("12,34,567", Numbers.format(BigDecimal(1234567))); assertEquals("1,000", Numbers.format(BigDecimal(1000))); assertEquals("999", Numbers.format(BigDecimal(999)))
        assertEquals("12,34,567.5", Numbers.format(BigDecimal("1234567.5"))); assertEquals("-2,500", Numbers.format(BigDecimal(-2500))); assertEquals("0.333333", Numbers.format(BigDecimal.ONE.divide(BigDecimal(3), java.math.MathContext(20))))
        assertEquals("25 lakh", Numbers.indianWords(BigDecimal(2500000))); assertEquals("1.5 crore", Numbers.indianWords(BigDecimal(15000000))); assertNull(Numbers.indianWords(BigDecimal(99999)))
        assertEquals("2,500 crore", Numbers.indianWords(BigDecimal("25000000000")))
    }
}

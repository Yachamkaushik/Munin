package com.munin.app.ledger

import com.munin.app.extract.PaymentDirection
import com.munin.app.extract.PaymentExtractor
import com.munin.app.extract.PaymentOutcome
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentExtractorTest {
    private fun read(text: String) = PaymentExtractor.extract(text)

    @Test fun googlePayStyleLayout() {
        val p = read("Google Pay\n₹2,499\nCompleted\nTo Amazon Pay\namazonpay@apl\n20 Sep 2026, 6:45 PM\nUPI transaction ID\n612345678901\nGoogle transaction ID CICAgOD678901")!!
        assertEquals(249900L, p.amountPaise)
        assertEquals("Amazon Pay", p.payee); assertEquals("amazonpay@apl", p.upiId)
        assertEquals("2026-09-20", p.date); assertEquals("18:45", p.time)
        assertEquals("612345678901", p.reference); assertEquals("Google Pay", p.app)
        assertEquals(PaymentOutcome.SUCCESS, p.outcome); assertTrue(p.readable)
    }

    @Test fun phonePeStyleLayoutPrefersTheUtrOverTheTransactionId() {
        val p = read("PhonePe\nPayment Successful\n₹ 450\nPaid to Ravi Tea Stall\nSep 18, 2026 at 08:15 AM\nTransaction ID\nT2609180815123456\nUTR: 609123456789\nDebited from XXXX1234")!!
        assertEquals(45000L, p.amountPaise); assertEquals("Ravi Tea Stall", p.payee)
        assertEquals("2026-09-18", p.date); assertEquals("08:15", p.time)
        assertEquals("609123456789", p.reference); assertEquals("PhonePe", p.app)
    }

    @Test fun paytmStyleLayoutWithThePayeeOnTheLineAfterItsLabel() {
        val p = read("Paytm UPI\nPaid Successfully\nPaid to\nSunrise Pharmacy\n₹ 1,275.50\n20 Sep 2026, 11:02 AM\nUPI Ref No: 609876543210\nOrder ID: 2026987654")!!
        assertEquals(127550L, p.amountPaise); assertEquals("Sunrise Pharmacy", p.payee)
        assertEquals("609876543210", p.reference); assertEquals("11:02", p.time)
    }

    @Test fun theAmountComesFromThePaymentNotFromCashbackOrBalanceLines() {
        val p = read("Google Pay\n₹500\nCompleted\nTo Chai Point\nYou earned ₹10 cashback\nBalance ₹12,340\n20 Sep 2026\nUPI transaction ID\n612345678901")!!
        assertEquals(50000L, p.amountPaise)
    }

    @Test fun ocrDamageStillReadsButWithLowConfidence() { // the real emulator output: "I2,499" is the rupee sign misread
        val p = read("Payment successful\nI2,499\nPaid to Amazon Pay\n20 Sep 2026,6:45 PM\nUPI Ref No: 6123456789")!!
        assertEquals(249900L, p.amountPaise)
        assertEquals(0.45f, p.amountConfidence, 0f)
        assertEquals("2026-09-20", p.date); assertEquals("18:45", p.time); assertEquals("6123456789", p.reference)
    }

    @Test fun aRupeeSignReadAsADevanagariDigitIsNotTurnedIntoADigit() { // real OCR: "₹180" came back as "३180"
        val p = read("Google Pay\n३180\nCompleted\nTo Chai Point\n21 Sep 2026, 9:10 AM\nUPI transaction ID\n618800112233")!!
        assertEquals(18000L, p.amountPaise) // 180, not 3,180
        assertEquals(0.45f, p.amountConfidence, 0f) // and still a guess, so not counted until the user includes it
    }

    @Test fun damagedReferenceLabelsStillYieldTheReference() { // real OCR: "UP transaction ।D" and "UPI transaction lD"
        assertEquals("607711223344", read("Google Pay\n₹95\nCompleted\nTo Chai Point\n28 Aug 2026, 5:05 PM\nUP transaction ।D\n607711223344")!!.reference)
        assertEquals("613300445566", read("Google Pay\n₹210\nCompleted\nTo Campus Canteen\nUPI transaction lD\n613300445566")!!.reference)
    }

    @Test fun failedPendingAndReceivedAreRecognisedNotCountedAsPaid() {
        assertEquals(PaymentOutcome.FAILED, read("Google Pay\n₹300\nPayment failed\nTo Chai Point\n20 Sep 2026\nUPI transaction ID\n612345678901")!!.outcome)
        assertEquals(PaymentOutcome.PENDING, read("PhonePe\nPayment pending\n₹300\nPaid to Chai Point\n20 Sep 2026\nUTR: 612345678901")!!.outcome)
        val r = read("Google Pay\n₹500\nMoney received\nFrom Ravi\n20 Sep 2026\nUPI transaction ID\n612345678901")!!
        assertEquals(PaymentDirection.RECEIVED, r.direction); assertEquals("Ravi", r.payee)
    }

    @Test fun aMissingDateOrAmountMakesItUnreadableWithAReason() {
        assertEquals("date not read", read("Google Pay\n₹210\nCompleted\nTo Chai Point\nUPI transaction ID\n612345678901")!!.problem)
        assertEquals("amount not read", read("Google Pay\nCompleted\nTo Chai Point\n20 Sep 2026\nUPI transaction ID\n612345678901")!!.problem)
        assertEquals("date has no year", read("Google Pay\n₹210\nCompleted\nTo Chai Point\n20 Sep\nUPI transaction ID\n612345678901")!!.problem)
    }

    @Test fun receiptsBillsAndOtherScreenshotsAreNotPayments() {
        assertNull(read("Hostel Fee Receipt\nStudent: Ravi Kumar\nAmount paid: Rs 45,000\nPaid on: 12 Sep 2026\nTransaction ID: 4821937560"))
        assertNull(read("Electricity Bill\nAmount due: ₹1,250\nDue date: 15/10/2026"))
        assertNull(read("Your flight to Hyderabad is confirmed\n3 November 6:45 AM"))
        assertNull(read("Meeting notes: paid to be there, UPI is great")) // the words alone are not enough
        assertNull(read(""))
    }

    @Test fun payeeFallsBackToTheUpiIdAndNamesWithDigitsAreRejected() {
        val p = read("Google Pay\n₹90\nCompleted\nTo ravi123@okaxis\n20 Sep 2026\nUPI transaction ID\n612345678901")!!
        assertNull(p.payee); assertEquals("ravi123@okaxis", p.upiId)
    }
}

class LedgerCalcTest {
    private fun row(id: Long, paise: Long?, date: String? = "2026-09-10", payee: String? = "Chai Point", ref: String? = null, conf: Float = 0.9f,
                    outcome: PaymentOutcome = PaymentOutcome.SUCCESS, dir: PaymentDirection = PaymentDirection.PAID, time: String? = null, decision: String? = null, problem: String? = null) =
        PaymentRow(id, id, "u$id", "f$id", null, outcome, dir, paise, conf, payee, null, date, time, ref, problem, decision)

    @Test fun totalsAreExactSumsInPaise() {
        // 0.1 + 0.2 is the classic float trap: here 10 + 20 paise, exactly 30.
        val s = LedgerCalc.summarize(listOf(row(1, 10), row(2, 20), row(3, 123_456_78), row(4, 1)))
        assertEquals(10L + 20 + 12_345_678 + 1, s.totalPaise)
        assertEquals(listOf(MonthTotal(YearMonth.of(2026, 9), 12_345_709, 4)), s.months)
        assertEquals("₹1,23,457.09", Money.format(12_345_709))
    }

    @Test fun theSamePaymentScreenshottedTwiceIsCountedOnce() {
        val s = LedgerCalc.summarize(listOf(row(1, 249900, ref = "612345678901"), row(2, 249900, ref = "612345678901"), row(3, 5000, ref = "609999999999")))
        assertEquals(listOf(1L, 3L), s.counted.map { it.id })
        assertEquals(listOf(2L), s.duplicates.map { it.row.id })
        assertEquals(254900L, s.totalPaise)
    }

    @Test fun duplicateWithADifferentAmountIsFlaggedAndOnlyOneIsCounted() {
        val s = LedgerCalc.summarize(listOf(row(1, 249900, ref = "R1"), row(2, 249000, ref = "R1")))
        assertEquals(1, s.counted.size)
        assertTrue(s.duplicates.single().amountsDiffer)
    }

    @Test fun withoutAReferenceOnlyAFullTimestampMatchesDuplicates() {
        val withTime = LedgerCalc.summarize(listOf(row(1, 5000, time = "10:30"), row(2, 5000, time = "10:30")))
        assertEquals(1, withTime.counted.size); assertEquals(1, withTime.duplicates.size)
        val noTime = LedgerCalc.summarize(listOf(row(1, 5000), row(2, 5000))) // could be two real tea purchases on one day
        assertEquals(2, noTime.counted.size); assertTrue(noTime.duplicates.isEmpty())
    }

    @Test fun everyScreenshotLandsInExactlyOneBucket() {
        val rows = listOf(
            row(1, 1000), row(2, 2000, ref = "A"), row(3, 2000, ref = "A"), row(4, 3000, outcome = PaymentOutcome.FAILED),
            row(5, 4000, outcome = PaymentOutcome.PENDING), row(6, 5000, dir = PaymentDirection.RECEIVED), row(7, null, problem = "amount not read"),
            row(8, 6000, conf = 0.45f), row(9, 7000, decision = "EXCLUDE"), row(10, 8000, conf = 0.45f, decision = "INCLUDE"), row(11, 9000, date = null, problem = "date not read"),
        )
        val s = LedgerCalc.summarize(rows)
        val all = s.counted.map { it.id } + s.needsCheck.map { it.id } + s.duplicates.map { it.row.id } + s.notSpending.map { it.id } + s.unreadable.map { it.id } + s.excluded.map { it.id }
        assertEquals(rows.map { it.id }.sorted(), all.sorted())
        assertEquals(listOf(1L, 2L, 10L), s.counted.map { it.id })
        assertEquals(listOf(8L), s.needsCheck.map { it.id })
        assertEquals(listOf(4L, 5L, 6L), s.notSpending.map { it.id })
        assertEquals(listOf(7L, 11L), s.unreadable.map { it.id })
        assertEquals(listOf(9L), s.excluded.map { it.id })
        assertEquals(1000L + 2000 + 8000, s.totalPaise)
    }

    @Test fun aGuessedAmountIsNotCountedUntilTheUserIncludesIt() {
        assertEquals(0L, LedgerCalc.summarize(listOf(row(1, 249900, conf = 0.45f))).totalPaise)
        assertEquals(249900L, LedgerCalc.summarize(listOf(row(1, 249900, conf = 0.45f, decision = "INCLUDE"))).totalPaise)
    }

    @Test fun monthsAreSeparateAndPayeesAreRanked() {
        val s = LedgerCalc.summarize(listOf(row(1, 100, "2026-08-31"), row(2, 200, "2026-09-01"), row(3, 300, "2026-09-30", payee = "Ravi Tea Stall"), row(4, 50, "2026-09-02", payee = "ravi tea  stall")))
        assertEquals(listOf(100L, 550L), s.months.map { it.totalPaise })
        assertEquals(listOf("Ravi Tea Stall" to 350L, "Chai Point" to 200L), s.payees(YearMonth.of(2026, 9)).map { it.name to it.totalPaise })
        assertEquals(650L, s.totalPaise)
        assertEquals(listOf(3L, 4L, 2L), s.countedIn(YearMonth.of(2026, 9)).map { it.id }) // newest first
    }

    @Test fun moneyFormatting() {
        assertEquals("₹2,499", Money.format(249900)); assertEquals("₹1,275.50", Money.format(127550)); assertEquals("₹0.05", Money.format(5)); assertEquals("₹0", Money.format(0))
        assertEquals("₹12,50,000", Money.format(125_000_000))
    }
}

class SpendingQueryTest {
    private val today = LocalDate.of(2026, 10, 5)
    private fun resolve(q: String, data: Collection<YearMonth> = emptyList()) = SpendingQuery.parse(q)?.resolve(today, data)

    @Test fun recognisesSpendingQuestionsInEveryStyle() {
        assertEquals(YearMonth.of(2026, 9), resolve("how much did I spend in September"))
        assertEquals(YearMonth.of(2026, 9), resolve("कितना खर्च किया सितंबर में"))
        assertEquals(YearMonth.of(2026, 9), resolve("సెప్టెంబర్ ఖర్చు ఎంత"))
        assertEquals(YearMonth.of(2026, 9), resolve("sept spending"))
        assertNull(SpendingQuery.parse("how much was the hostel fee"))
    }

    @Test fun relativeMonthsAndNoMonth() {
        assertEquals(YearMonth.of(2026, 10), resolve("how much did I spend this month"))
        assertEquals(YearMonth.of(2026, 9), resolve("how much did I spend last month"))
        assertNull(resolve("how much did I spend"))
    }

    @Test fun aMonthWithoutAYearPicksTheLatestPastOneThatHasData() {
        assertEquals(YearMonth.of(2025, 12), resolve("spent in December", listOf(YearMonth.of(2025, 12), YearMonth.of(2024, 12))))
        assertEquals(YearMonth.of(2025, 12), resolve("spent in December")) // no data: December has not happened yet this year
        assertEquals(YearMonth.of(2024, 9), resolve("spent in September 2024"))
    }
}

class PaymentCorpusTest {
    /**
     * The whole path, text -> extraction -> ledger, over a reproducible synthetic corpus. Every readable payment must
     * come back exactly, and the monthly totals must equal the true sums to the paisa.
     */
    @Test fun ledgerTotalsEqualTheTrueTotalsToThePaisa() {
        val truths = PaymentLayouts.corpus(seed = 42, n = 60)
        val rows = ArrayList<PaymentRow>()
        var id = 1L
        fun add(t: Truth) {
            val p = PaymentExtractor.extract(PaymentLayouts.render(t)) ?: error("not detected as a payment:\n${PaymentLayouts.render(t)}")
            rows += PaymentRow(id, id, "u$id", "f$id", p.app, p.outcome, p.direction, p.amountPaise, p.amountConfidence, p.payee, p.upiId, p.date, p.time, p.reference, p.problem, null)
            id++
        }

        // field-level exactness on the clean originals
        for (t in truths) {
            val p = PaymentExtractor.extract(PaymentLayouts.render(t))!!
            val ctx = PaymentLayouts.render(t)
            assertEquals(ctx, t.amountPaise, p.amountPaise)
            assertEquals(ctx, t.payee, p.payee)
            assertEquals(ctx, t.date.toString(), p.date)
            assertEquals(ctx, "%02d:%02d".format(t.time.hour, t.time.minute), p.time)
            assertEquals(ctx, t.ref, p.reference)
        }

        truths.forEach(::add)
        // the same payments screenshotted again, in a different layout and currency style: must not be counted twice
        truths.take(10).forEach { add(it.copy(layout = (it.layout + 1) % 3, currencyStyle = it.currencyStyle + 1)) }
        // failed, pending, received and date-less screenshots: none of them belong in the totals
        val noise = PaymentLayouts.corpus(seed = 7, n = 12).mapIndexed { i, t ->
            when (i % 4) { 0 -> t.copy(outcome = "failed"); 1 -> t.copy(outcome = "pending"); 2 -> t.copy(received = true); else -> t.copy(hideDate = true) }
        }
        noise.forEach(::add)

        val summary = LedgerCalc.summarize(rows)
        val expected = truths.groupBy { YearMonth.of(it.date.year, it.date.month) }.mapValues { (_, v) -> v.sumOf { it.amountPaise } }
        assertEquals(expected.toSortedMap(), summary.months.associate { it.month to it.totalPaise }.toSortedMap())
        assertEquals(truths.sumOf { it.amountPaise }, summary.totalPaise)
        assertEquals(truths.size, summary.counted.size)
        assertEquals(10, summary.duplicates.size)
        assertEquals(9, summary.notSpending.size) // 3 failed + 3 pending + 3 received
        assertEquals(3, summary.unreadable.size)
        assertTrue(summary.needsCheck.isEmpty())
    }
}

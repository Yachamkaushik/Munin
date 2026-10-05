package com.munin.app.extract

import com.munin.app.extract.FactType.ADDRESS
import com.munin.app.extract.FactType.AMOUNT
import com.munin.app.extract.FactType.DATE
import com.munin.app.extract.FactType.PHONE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FactExtractorTest {
    private fun values(text: String, type: FactType) = FactExtractor.extract(text).filter { it.type == type }.map { it.value }
    private fun one(text: String, type: FactType) = FactExtractor.extract(text).single { it.type == type }

    // ---- amounts ----

    @Test fun amountsWithEachCurrencyMarker() {
        assertEquals(listOf("45000"), values("Amount paid: Rs 45,000", AMOUNT))
        assertEquals(listOf("45000"), values("Total ₹45,000", AMOUNT))
        assertEquals(listOf("1250.5"), values("Due INR 1,250.50", AMOUNT))
        assertEquals(listOf("45000"), values("शुल्क रु. 45,000", AMOUNT))
        assertEquals(listOf("45000"), values("చెల్లించిన మొత్తం: రూ. 45,000", AMOUNT))
        assertEquals(listOf("500"), values("Fee 500/- only", AMOUNT))
    }

    @Test fun indianAndWesternGroupingAndDecimals() {
        assertEquals(listOf("125000"), values("Amount: ₹1,25,000", AMOUNT))
        assertEquals(listOf("1250000"), values("Amount: ₹12,50,000", AMOUNT))
        assertEquals(listOf("2499.99"), values("Rs. 2,499.99", AMOUNT))
        assertEquals(listOf("45000"), values("Amount: Rs 45000", AMOUNT))
    }

    @Test fun devanagariAndTeluguDigits() {
        assertEquals(listOf("45000"), values("जमा राशि: ₹४५,०००", AMOUNT))
        assertEquals(listOf("1250"), values("మొత్తం: రూ. ౧,౨౫౦", AMOUNT))
    }

    @Test fun mixedScriptDigitRunsAreNotConverted() {
        assertEquals("३180", Digits.toAscii("३180")) // OCR's rendering of ₹180
        assertEquals("45,000 45,000", Digits.toAscii("४५,००० ४५,०००")) // separate pure runs convert independently
        assertEquals("45,000", Digits.toAscii("४५,०००"))
        assertEquals("12/09/2026", Digits.toAscii("१२/०९/२०२६"))
    }

    @Test fun amountWhenTheRupeeSignWasLostByOcr() { // real OCR output from the emulator run
        assertEquals(listOf("1250"), values("Amount due: 1,250", AMOUNT))
        assertEquals(listOf("45000"), values("जमा राशिः ₹45,000", AMOUNT))
        val f = one("Amount due: 1,250", AMOUNT)
        assertEquals(0.7f, f.confidence, 0f) // lower than an explicit currency marker
        assertEquals("Amount due", f.label)
    }

    @Test fun aBareNumberOnItsOwnLineIsALowConfidenceAmount() { // payment screenshots: "I2,499" is a misread rupee sign
        val f = one("Payment successful\nI2,499\nPaid to Amazon Pay", AMOUNT)
        assertEquals("2499", f.value)
        assertEquals(0.45f, f.confidence, 0f)
        assertEquals("Payment successful", f.label)
    }

    @Test fun idsDatesAndPhonesAreNotAmounts() {
        assertEquals(emptyList<String>(), values("Transaction ID: 4821937560", AMOUNT))
        assertEquals(emptyList<String>(), values("UPI Ref No: 6123456789", AMOUNT))
        assertEquals(emptyList<String>(), values("Paid on: 12/09/2026", AMOUNT))
        assertEquals(emptyList<String>(), values("Fee receipt no: 12345", AMOUNT))
        assertEquals(emptyList<String>(), values("Call 98765 43210 for the total", AMOUNT))
        assertEquals(emptyList<String>(), values("Semester: 2", AMOUNT))
    }

    @Test fun absurdAmountsAreRejected() = assertEquals(emptyList<String>(), values("Amount: Rs 99,99,99,99,999", AMOUNT))

    @Test fun severalAmountsAreAllKeptWithTheirLabels() {
        val f = FactExtractor.extract("Fee: Rs 45,000\nLate fine: Rs 500").filter { it.type == AMOUNT }
        assertEquals(listOf("45000", "500"), f.map { it.value })
        assertEquals(listOf("Fee", "Late fine"), f.map { it.label })
    }

    // ---- dates ----

    @Test fun dateFormats() {
        assertEquals(listOf("2026-09-12"), values("Paid on: 12/09/2026", DATE))
        assertEquals(listOf("2026-09-12"), values("Date: 12-09-2026", DATE))
        assertEquals(listOf("2026-09-12"), values("Date: 12.09.26", DATE))
        assertEquals(listOf("2026-09-12"), values("Paid on:12 Sep 2026", DATE)) // missing space, as OCR gave
        assertEquals(listOf("2026-09-12"), values("12th September 2026", DATE))
        assertEquals(listOf("2026-09-12"), values("September 12, 2026", DATE))
        assertEquals(listOf("2026-09-14"), values("Collected on: 14-Sep-2026", DATE))
        assertEquals(listOf("2026-09-30"), values("Sept 30 2026", DATE))
    }

    @Test fun hindiAndTeluguMonthNames() {
        assertEquals(listOf("2026-09-12"), values("दिनांक: 12 सितंबर 2026", DATE))
        assertEquals(listOf("2026-10-15"), values("15 अक्टूबर 2026", DATE))
        assertEquals(listOf("2026-09-12"), values("తేదీ: 12 సెప్టెంబర్ 2026", DATE))
        assertEquals(listOf("2026-09-12"), values("दिनांक: १२/०९/२०२६", DATE))
    }

    @Test fun dayFirstUnlessTheFirstNumberIsClearlyAMonth() {
        assertEquals(listOf("2026-03-04"), values("04/03/2026", DATE)) // 4 March, not April 3
        assertEquals(listOf("2026-12-25"), values("12/25/2026", DATE)) // 25 cannot be a month
        assertEquals(0.6f, one("12/25/2026", DATE).confidence, 0f)
    }

    @Test fun timesAreAttachedWhenRightNextToTheDate() {
        assertEquals(listOf("2026-09-20T18:45"), values("20 Sep 2026, 6:45 PM", DATE))
        assertEquals(listOf("2026-09-20T00:05"), values("20/09/2026 12:05 AM", DATE))
        assertEquals(listOf("2026-09-20"), values("20/09/2026 ... Helpline open till 6:45", DATE))
    }

    @Test fun yearlessDatesAreMarkedAndLowConfidence() {
        val f = one("Your flight is on 3 November 6:45 AM", DATE)
        assertEquals("--11-03T06:45", f.value) // the time right after the date is kept
        assertEquals(0.5f, f.confidence, 0f)
    }

    @Test fun impossibleDatesAreRejected() {
        assertEquals(emptyList<String>(), values("31/02/2026", DATE))
        assertEquals(emptyList<String>(), values("32 Sep 2026", DATE))
        assertEquals(emptyList<String>(), values("Rs 45,000", DATE))
        assertEquals(emptyList<String>(), values("March of the penguins", DATE))
    }

    @Test fun dateLabelIsKept() = assertEquals("Due date", one("Due date: 15/10/2026", DATE).label)

    // ---- phones ----

    @Test fun indianMobileNumbersInCommonFormats() {
        assertEquals(listOf("+919876543210"), values("Phone: 9876543210", PHONE))
        assertEquals(listOf("+919876543210"), values("Contact +91 98765 43210", PHONE))
        assertEquals(listOf("+919876543210"), values("Mobile: 91-98765-43210", PHONE))
        assertEquals(listOf("+919876543210"), values("फोन: ९८७६५ ४३२१०", PHONE))
    }

    @Test fun otherPhoneShapes() {
        assertEquals(listOf("18001234567"), values("Helpline 1800 123 4567", PHONE))
        assertEquals(listOf("04023456789"), values("Tel: 040-23456789", PHONE))
    }

    @Test fun idsThatLookLikePhonesAreNotPhones() {
        assertEquals(emptyList<String>(), values("Transaction ID: 9876543210", PHONE))
        assertEquals(emptyList<String>(), values("UPI Ref No: 612345678901", PHONE))
        assertEquals(emptyList<String>(), values("Transaction ID: 4821937560", PHONE)) // does not start with 6-9
        assertEquals(emptyList<String>(), values("Amount: 1234567", PHONE))
    }

    @Test fun phoneConfidenceReflectsTheEvidence() {
        assertEquals(0.9f, one("Phone: 9876543210", PHONE).confidence, 0f)
        assertEquals(0.5f, one("9876543210", PHONE).confidence, 0f)
    }

    // ---- addresses ----

    @Test fun addressAfterALabelRunsUntilTheNextField() {
        val text = "Venue Details\nAddress: Sunrise Hostel, Plot 12\nJubilee Hills, Hyderabad 500033\nPhone: 9876543210\nSeat: 14"
        val a = one(text, ADDRESS)
        assertEquals("Sunrise Hostel, Plot 12, Jubilee Hills, Hyderabad 500033", a.value)
        assertEquals(0.8f, a.confidence, 0f)
        assertEquals("Address", a.label) // no trailing colon
    }

    @Test fun addressHeadedByAHindiOrTeluguLabel() {
        assertEquals("गांधी रोड, पुणे 411001", one("पता: गांधी रोड\nपुणे 411001", ADDRESS).value)
        assertEquals("రోడ్ నం 5, హైదరాబాద్ 500034", one("చిరునామా: రోడ్ నం 5\nహైదరాబాద్ 500034", ADDRESS).value)
    }

    @Test fun addressWithoutALabelIsFoundFromItsPinCodeAtLowerConfidence() {
        val a = one("Order shipped\n12 MG Road\nBengaluru 560001\nThank you", ADDRESS)
        assertEquals("12 MG Road, Bengaluru 560001", a.value.substringAfter("Order shipped, ").ifEmpty { a.value })
        assertEquals(0.6f, a.confidence, 0f)
    }

    @Test fun aSixDigitIdOrLoneNumberIsNotAnAddress() {
        assertEquals(emptyList<String>(), values("Order ID: 560001", ADDRESS))
        assertEquals(emptyList<String>(), values("500081", ADDRESS))
    }

    // ---- whole documents (the synthetic samples as the OCR actually returned them) ----

    @Test fun hostelFeeReceiptAsRead() {
        val text = "Hostel Fee Receipt\nStudent: Ravi Kumar\nSemester: Second semester 2026\nAmount paid: Rs 45,000\nPaid on:12 Sep 2026\nTransaction ID: 4821937560\nSYNTHETIC SAMPLE - not a real document"
        assertEquals(listOf("45000"), values(text, AMOUNT))
        assertEquals(listOf("2026-09-12"), values(text, DATE))
        assertEquals(emptyList<String>(), values(text, PHONE))
    }

    @Test fun electricityBillAsRead() {
        val text = "Electricity Bill\nConsumer: Ravi Kumar\nAmount due: 1,250\nDue date: 15/10/2026\nPay at the nearest centre or online"
        assertEquals(listOf("1250"), values(text, AMOUNT))
        assertEquals(listOf("2026-10-15"), values(text, DATE))
    }

    @Test fun hindiReceiptAsRead() {
        val text = "छात्रावास शुल्क रसीद\nछात्र:रव कुमार\nजमा राशिः ₹45,000\nदिनांक: 12 सितंबर 2026\nलेन-देन संख्या: 4821937560"
        assertEquals(listOf("45000"), values(text, AMOUNT))
        assertEquals(listOf("2026-09-12"), values(text, DATE))
    }

    @Test fun lineNumbersMatchTheChunkerLines() {
        val f = FactExtractor.extract("\n  Header  \n\nAmount paid: Rs 100\n")
        assertEquals(1, f.single { it.type == AMOUNT }.line) // "Header" is line 0, the amount line is line 1
        assertEquals(listOf("Header", "Amount paid: Rs 100"), FactExtractor.lines("\n  Header  \n\nAmount paid: Rs 100\n"))
    }

    @Test fun emptyAndFactlessTextGivesNothing() {
        assertTrue(FactExtractor.extract("").isEmpty())
        assertTrue(FactExtractor.extract("Meeting notes: submit the project report by Monday").isEmpty())
    }
}

package com.munin.app.extract

import java.math.BigDecimal

enum class PaymentOutcome { SUCCESS, FAILED, PENDING }
enum class PaymentDirection { PAID, RECEIVED }

/**
 * What was read from one UPI payment screenshot. [amountPaise] is an integer number of paise, so sums are exact.
 * A screenshot can be recognized as a payment yet still be unreadable for the ledger ([problem] says why).
 */
data class PaymentRead(
    val app: String?,
    val outcome: PaymentOutcome,
    val direction: PaymentDirection,
    val amountPaise: Long?,
    val amountConfidence: Float,
    val amountRaw: String?,
    val payee: String?,
    val upiId: String?,
    /** ISO date, or null if there was none or it had no year. */
    val date: String?,
    val time: String?,
    /** UTR / UPI reference / transaction id, upper-case with no spaces; used to spot the same payment twice. */
    val reference: String?,
    val problem: String?,
) {
    val readable get() = problem == null
}

/**
 * Detects UPI payment screenshots and reads amount, payee, date and reference with rules, not a model.
 * Rules are label-driven rather than position-driven, because payment apps lay the same fields out differently
 * (amount above or below the payee, reference on the same line as its label or the line after).
 * Tested on synthetic layouts only; real app layouts may need more rules.
 */
object PaymentExtractor {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val UPI_SIGNAL = r("\\bUPI\\b|\\bUTR\\b|\\bVPA\\b|google\\s?pay|\\bgpay\\b|phonepe|paytm|\\bBHIM\\b|amazon pay|[\\w.\\-]{2,}@[a-z][a-z0-9]{1,}\\b")
    private val PAYMENT_CUE = r("paid to|payment (?:successful|completed|failed|done|pending)|paid successfully|sent to|money sent|transaction (?:successful|failed|completed)|received from|money received|payment received|debited|credited|\\bcompleted\\b|\\bpaying\\b")
    private val FAILED = r("\\bfail(?:ed|ure)?\\b|declined|unsuccessful|could not|couldn't|not (?:completed|successful)|reversed")
    private val PENDING = r("\\bpending\\b|processing|in progress|awaiting")
    private val RECEIVED = r("received from|money received|payment received|you received|credited to")
    private val CURRENCY = r("₹|\\bRs\\b\\.?|\\bINR\\b|रु|రూ")
    private val SENT = r("paid to|sent to|money sent|paying|transferred to")

    /** Returns null for anything that is not a UPI payment screenshot (e.g. a fee receipt, a bill, a flight). */
    fun extract(text: String): PaymentRead? {
        val lines = FactExtractor.lines(text)
        if (lines.isEmpty()) return null
        val whole = lines.joinToString("\n")
        // A UPI word and a cue phrase alone also appear in notes ("paid to be there, UPI is great"), so a real
        // payment screenshot must also show a reference label or a currency marker.
        val evidence = STRONG_REF.containsMatchIn(whole) || WEAK_REF.containsMatchIn(whole) || CURRENCY.containsMatchIn(whole)
        if (!UPI_SIGNAL.containsMatchIn(whole) || !PAYMENT_CUE.containsMatchIn(whole) || !evidence) return null

        val outcome = when {
            FAILED.containsMatchIn(whole) -> PaymentOutcome.FAILED
            PENDING.containsMatchIn(whole) -> PaymentOutcome.PENDING
            else -> PaymentOutcome.SUCCESS
        }
        val direction = if (RECEIVED.containsMatchIn(whole) && !SENT.containsMatchIn(whole)) PaymentDirection.RECEIVED else PaymentDirection.PAID

        val facts = FactExtractor.extract(text)
        val amount = pickAmount(facts, lines)
        val paise = amount?.let { runCatching { BigDecimal(it.value).movePointRight(2).longValueExact() }.getOrNull() }
        val date = facts.filter { it.type == FactType.DATE && !it.value.startsWith("--") }
            .sortedWith(compareByDescending<ExtractedFact> { it.confidence }.thenBy { it.line }).firstOrNull()
        val hadYearless = facts.any { it.type == FactType.DATE && it.value.startsWith("--") }
        val (payee, upiId) = payee(lines, direction)

        val problems = ArrayList<String>()
        if (paise == null) problems += "amount not read"
        if (date == null) problems += if (hadYearless) "date has no year" else "date not read"
        return PaymentRead(
            app = appName(lines), outcome = outcome, direction = direction,
            amountPaise = paise, amountConfidence = amount?.confidence ?: 0f, amountRaw = amount?.raw,
            payee = payee, upiId = upiId,
            date = date?.value?.substringBefore('T'), time = date?.value?.substringAfter('T', "")?.takeIf { it.isNotEmpty() },
            reference = reference(lines), problem = problems.takeIf { it.isNotEmpty() }?.joinToString(", "),
        )
    }

    // ---- amount ----------------------------------------------------------------------------------------------------

    /** Lines whose amount is something other than the payment itself. */
    private val NOT_THE_PAYMENT = r("cashback|reward|offer|\\bfee\\b|charge|balance|\\bgst\\b|discount|scratch|coupon|earned|saved|limit|points")

    /** Prefers an explicit-currency amount, then a labelled one, then a bare guessed one; ties go to the earliest line. */
    private fun pickAmount(facts: List<ExtractedFact>, lines: List<String>): ExtractedFact? =
        facts.filter { it.type == FactType.AMOUNT && !NOT_THE_PAYMENT.containsMatchIn("${it.label.orEmpty()} ${lines.getOrNull(it.line).orEmpty()}") }
            .sortedWith(compareByDescending<ExtractedFact> { tier(it.confidence) }.thenBy { it.line })
            .firstOrNull()

    private fun tier(c: Float) = when { c >= 0.85f -> 2; c >= 0.7f -> 1; else -> 0 }

    // ---- payee -----------------------------------------------------------------------------------------------------

    private val UPI_ID = Regex("[\\w.\\-]{2,}@[a-zA-Z][a-zA-Z0-9]{1,}")
    private val PAID_LABEL = r("^(?:paid to|payment to|sent to|money sent to|transferred to|paying|to)\\b\\s*[:\\-]?\\s*(.*)$")
    private val RECEIVED_LABEL = r("^(?:received from|money received from|from)\\b\\s*[:\\-]?\\s*(.*)$")
    private val NOT_A_NAME = r("successful|completed|failed|pending|processing|\\bupi\\b|transaction|₹|\\brs\\b|\\binr\\b")

    private fun payee(lines: List<String>, direction: PaymentDirection): Pair<String?, String?> {
        val label = if (direction == PaymentDirection.PAID) PAID_LABEL else RECEIVED_LABEL
        var upi: String? = null
        var name: String? = null
        for ((i, line) in lines.withIndex()) {
            val m = label.find(line) ?: continue
            var candidate = m.groupValues[1].trim()
            if (candidate.isEmpty()) candidate = lines.drop(i + 1).firstOrNull { it.isNotBlank() }.orEmpty() // name on the next line
            val id = UPI_ID.find(candidate)?.value
            if (id != null) { upi = upi ?: id; candidate = candidate.replace(id, "").trim(' ', '(', ')', '-', ',') }
            candidate = candidate.trim()
            if (candidate.length in 2..60 && !NOT_A_NAME.containsMatchIn(candidate) && !candidate.any { it.isDigit() } && name == null) name = candidate
        }
        if (upi == null) upi = lines.firstNotNullOfOrNull { UPI_ID.find(it)?.value }
        return name to upi
    }

    // ---- reference -------------------------------------------------------------------------------------------------

    /** UTR and UPI reference numbers identify the payment across apps, so they win over a generic "Transaction ID". */
    // OCR confuses the letter I with l, 1, | and the Devanagari danda, so "ID" and "UPI" are matched loosely.
    private const val I = "[Il1|\u0964]"
    private const val ID = "$I" + "d"
    private val STRONG_REF = r("UP$I?\\s*(?:ref(?:erence)?(?:\\s*(?:no\\.?|number|$ID))?|transaction\\s*$ID|txn\\s*$ID)|UTR(?:\\s*(?:no\\.?|number))?")
    private val WEAK_REF = r("transaction\\s*$ID|txn\\s*$ID|ref(?:erence)?\\s*(?:no\\.?|number|$ID)")
    private val REF_VALUE = Regex("[A-Za-z0-9]{8,35}")

    private fun reference(lines: List<String>): String? {
        val ascii = lines.map(Digits::toAscii)
        for (label in listOf(STRONG_REF, WEAK_REF)) {
            for ((i, line) in ascii.withIndex()) {
                val m = label.find(line) ?: continue
                val after = line.substring(m.range.last + 1).trimStart(':', '#', '.', '-', ' ')
                val sameLine = REF_VALUE.find(after.replace(" ", "")).takeIf { after.isNotBlank() }?.value
                val next = ascii.getOrNull(i + 1)?.replace(" ", "")?.takeIf { REF_VALUE.matches(it) }
                val value = (sameLine ?: next)?.uppercase() ?: continue
                if (value.count { it.isDigit() } >= 6) return value
            }
        }
        return null
    }

    // ---- app -------------------------------------------------------------------------------------------------------

    /** Only the first lines are checked: "Paid to Amazon Pay" further down names the payee, not the app. */
    private fun appName(lines: List<String>): String? {
        val head = lines.take(2).joinToString(" ").lowercase()
        return when {
            "google pay" in head || "gpay" in head -> "Google Pay"
            "phonepe" in head -> "PhonePe"
            "paytm" in head -> "Paytm"
            "bhim" in head -> "BHIM"
            "amazon pay" in head -> "Amazon Pay"
            else -> null
        }
    }
}

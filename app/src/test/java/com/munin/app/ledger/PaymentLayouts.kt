package com.munin.app.ledger

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

/** The truth behind one synthetic payment screenshot, and the text a payment app of each layout would show. */
data class Truth(
    val amountPaise: Long, val payee: String, val date: LocalDate, val time: LocalTime, val ref: String, val upi: String,
    val layout: Int, val currencyStyle: Int, val outcome: String = "success", val received: Boolean = false, val hideDate: Boolean = false,
)

/**
 * Synthetic layouts modelled on how UPI apps commonly arrange a receipt. They are written from general knowledge of
 * the formats, not copied from any app, and are the only layouts the rules have been tested on.
 */
object PaymentLayouts {
    private val DATE_A = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
    private val DATE_B = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' hh:mm a", Locale.ENGLISH)

    fun money(paise: Long, style: Int): String {
        val whole = paise / 100; val frac = paise % 100
        val grouped = com.munin.app.answer.AnswerFormat.indianGrouping(whole.toString())
        val body = when (style % 5) { 4 -> whole.toString(); else -> grouped } + if (frac != 0L) ".%02d".format(frac) else ""
        return when (style % 5) { 0 -> "₹$body"; 1 -> "₹ $body"; 2 -> "Rs. $body"; 3 -> "Rs $body"; else -> "₹$body" }
    }

    fun render(t: Truth): String {
        val amount = money(t.amountPaise, t.currencyStyle)
        val dt = LocalDate.of(t.date.year, t.date.month, t.date.dayOfMonth).atTime(t.time)
        val status = when { t.outcome == "failed" -> "Payment failed"; t.outcome == "pending" -> "Payment pending"; t.received -> "Money received"; else -> null }
        val lines = when (t.layout) {
            0 -> listOfNotNull( // Google Pay-like: big amount first, payee below, reference label then value on the next line
                "Google Pay", amount, status ?: "Completed",
                if (t.received) "From ${t.payee}" else "To ${t.payee}", t.upi,
                if (t.hideDate) null else dt.format(DATE_A), "UPI transaction ID", t.ref, "Google transaction ID CICAgOD${t.ref.takeLast(6)}",
            )
            1 -> listOfNotNull( // PhonePe-like: status, amount, "Paid to", date with "at", two ids
                "PhonePe", status ?: "Payment Successful", amount,
                if (t.received) "Received from ${t.payee}" else "Paid to ${t.payee}",
                if (t.hideDate) null else dt.format(DATE_B), "Transaction ID", "T${t.date.year % 100}${t.date.monthValue}${t.ref.takeLast(10)}",
                "UTR: ${t.ref}", "Debited from XXXX1234",
            )
            else -> listOfNotNull( // Paytm-like: label on its own line, payee on the next, amount after
                "Paytm UPI", status ?: "Paid Successfully", if (t.received) "Received from" else "Paid to", t.payee, amount,
                if (t.hideDate) null else dt.format(DATE_A), "UPI Ref No: ${t.ref}", "Order ID: 20${t.date.monthValue}${t.ref.takeLast(7)}",
            )
        }
        return lines.joinToString("\n")
    }

    private val PAYEES = listOf(
        "Ravi Tea Stall", "Sunrise Pharmacy", "Amazon Pay", "Hotel Annapurna", "Chai Point", "Swathi Stationery", "Anil Kumar", "Priya Sharma",
        "Metro Mart", "City Bookshop", "Lakshmi Tiffins", "Campus Canteen", "Auto Rajesh", "Green Grocers", "Fresh Juice Centre", "Print And Copy",
    )

    /** A reproducible set of payments across Aug-Oct 2026, with the dates, amounts and references known exactly. */
    fun corpus(seed: Long, n: Int): List<Truth> {
        val rnd = Random(seed)
        return (0 until n).map { i ->
            val cents = if (rnd.nextInt(3) == 0) rnd.nextInt(100).toLong() else 0L
            val whole = when (rnd.nextInt(4)) { 0 -> 10 + rnd.nextInt(90); 1 -> 100 + rnd.nextInt(900); 2 -> 1_000 + rnd.nextInt(9_000); else -> 10_000 + rnd.nextInt(89_000) }
            val payee = PAYEES[rnd.nextInt(PAYEES.size)]
            Truth(
                amountPaise = whole * 100L + cents, payee = payee, date = LocalDate.of(2026, 8 + rnd.nextInt(3), 1 + rnd.nextInt(28)),
                time = LocalTime.of(rnd.nextInt(24), rnd.nextInt(60)), ref = (600_000_000_000L + rnd.nextInt(Int.MAX_VALUE) + i * 1_000_003L).toString(),
                upi = payee.lowercase().replace(" ", "") + "@okaxis", layout = rnd.nextInt(3), currencyStyle = rnd.nextInt(5),
            )
        }
    }
}

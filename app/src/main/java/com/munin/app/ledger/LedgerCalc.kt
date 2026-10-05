package com.munin.app.ledger

import com.munin.app.extract.PaymentDirection
import com.munin.app.extract.PaymentOutcome
import java.time.LocalDate
import java.time.YearMonth

/** One payment screenshot as the ledger sees it (the stored read plus where it came from). */
data class PaymentRow(
    val id: Long,
    val itemId: Long,
    val uri: String,
    val displayName: String,
    val app: String?,
    val outcome: PaymentOutcome,
    val direction: PaymentDirection,
    val amountPaise: Long?,
    val amountConfidence: Float,
    val payee: String?,
    val upiId: String?,
    val paidDate: String?,
    val paidTime: String?,
    val reference: String?,
    val problem: String?,
    /** What the user decided: "INCLUDE" or "EXCLUDE", or null to follow the rules. */
    val decision: String?,
) {
    val readable get() = problem == null && amountPaise != null && paidDate != null
    val payeeName get() = payee ?: upiId ?: "Unknown payee"
    private val payeeKey get() = (payee ?: upiId)?.lowercase()?.replace(Regex("[^\\p{L}\\p{N}@]+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    val month: YearMonth? get() = paidDate?.let { runCatching { YearMonth.from(LocalDate.parse(it)) }.getOrNull() }

    /** Two screenshots of the same payment share this key. A reference number is best; otherwise only a full timestamp. */
    internal val dedupeKey: String? get() = when {
        reference != null -> "ref:$reference"
        paidTime != null && payeeKey != null && amountPaise != null -> "ts:$amountPaise|$payeeKey|$paidDate|$paidTime"
        else -> null
    }
}

data class Duplicate(val row: PaymentRow, val of: PaymentRow, val amountsDiffer: Boolean)
data class MonthTotal(val month: YearMonth, val totalPaise: Long, val count: Int)
data class PayeeTotal(val name: String, val totalPaise: Long, val count: Int)

/**
 * Every payment screenshot ends up in exactly one bucket, so nothing disappears silently, and [counted] is the only
 * set that adds up to a total.
 */
data class LedgerSummary(
    val counted: List<PaymentRow>,
    /** Read, but the amount was a guess (e.g. the rupee sign was not read); not in totals until the user includes it. */
    val needsCheck: List<PaymentRow>,
    val duplicates: List<Duplicate>,
    /** Failed or pending payments, and money received. */
    val notSpending: List<PaymentRow>,
    val unreadable: List<PaymentRow>,
    val excluded: List<PaymentRow>,
) {
    val months: List<MonthTotal> = counted.filter { it.month != null }.groupBy { it.month!! }
        .map { (m, rows) -> MonthTotal(m, rows.sumOf { it.amountPaise!! }, rows.size) }.sortedBy { it.month }

    val totalPaise: Long get() = counted.sumOf { it.amountPaise!! }

    fun countedIn(month: YearMonth?): List<PaymentRow> =
        (if (month == null) counted else counted.filter { it.month == month }).sortedWith(compareByDescending<PaymentRow> { it.paidDate }.thenByDescending { it.paidTime })

    fun payees(month: YearMonth?): List<PayeeTotal> =
        countedIn(month).groupBy { it.payeeKeyForGrouping() }
            .map { (_, rows) -> PayeeTotal(rows.first().payeeName, rows.sumOf { it.amountPaise!! }, rows.size) }
            .sortedWith(compareByDescending<PayeeTotal> { it.totalPaise }.thenBy { it.name })

    private fun PaymentRow.payeeKeyForGrouping() = payeeName.lowercase().replace(Regex("[^\\p{L}\\p{N}@]+"), " ").trim()
}

object LedgerCalc {
    /** An amount read with less confidence than this is a guess and needs the user's say-so. */
    const val MIN_AMOUNT_CONFIDENCE = 0.6f

    fun summarize(rows: List<PaymentRow>): LedgerSummary {
        val unreadable = ArrayList<PaymentRow>()
        val notSpending = ArrayList<PaymentRow>()
        val eligible = ArrayList<PaymentRow>()
        for (r in rows.sortedBy { it.id }) when {
            !r.readable -> unreadable += r
            r.outcome != PaymentOutcome.SUCCESS || r.direction != PaymentDirection.PAID -> notSpending += r
            else -> eligible += r
        }

        // The same payment screenshotted twice is counted once. Within a group the user's "include" wins, then the
        // surest amount, then the earliest item.
        val kept = ArrayList<PaymentRow>()
        val duplicates = ArrayList<Duplicate>()
        val groups = eligible.groupBy { it.dedupeKey }
        for ((key, group) in groups) {
            if (key == null) { kept += group; continue }
            val ordered = group.sortedWith(compareByDescending<PaymentRow> { it.decision == "INCLUDE" }.thenByDescending { it.amountConfidence }.thenBy { it.id })
            val winner = ordered.first()
            kept += winner
            ordered.drop(1).forEach { duplicates += Duplicate(it, winner, it.amountPaise != winner.amountPaise) }
        }

        val counted = ArrayList<PaymentRow>()
        val needsCheck = ArrayList<PaymentRow>()
        val excluded = ArrayList<PaymentRow>()
        for (r in kept.sortedBy { it.id }) when {
            r.decision == "EXCLUDE" -> excluded += r
            r.decision == "INCLUDE" || r.amountConfidence >= MIN_AMOUNT_CONFIDENCE -> counted += r
            else -> needsCheck += r
        }
        return LedgerSummary(counted, needsCheck, duplicates.sortedBy { it.row.id }, notSpending, unreadable, excluded)
    }
}

package com.munin.app.ledger

import com.munin.app.answer.AnswerFormat
import java.math.BigDecimal

/** Money is held as whole paise (a Long) everywhere in the ledger, so adding it up is exact. */
object Money {
    /** "₹2,499", "₹1,275.50": the decimals only appear when there are paise. Uses Indian digit grouping. */
    fun format(paise: Long): String {
        val d = BigDecimal.valueOf(paise, 2)
        return "₹" + AnswerFormat.indianGrouping(if (paise % 100 == 0L) d.toBigInteger().toString() else d.toPlainString())
    }
}

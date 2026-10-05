package com.munin.app.answer

import com.munin.app.extract.FactType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a normalized fact value is shown to the user. The stored value is never changed; this is display only. */
object AnswerFormat {
    fun display(kind: FactType, value: String): String = when (kind) {
        FactType.AMOUNT -> "₹" + indianGrouping(value)
        FactType.DATE -> date(value)
        FactType.PHONE -> phone(value)
        FactType.ADDRESS -> value
    }

    /** "1250000" -> "12,50,000"; "2499.5" -> "2,499.50". */
    fun indianGrouping(plain: String): String {
        val d = BigDecimal(plain)
        val whole = d.toBigInteger().toString()
        val grouped = if (whole.length <= 3) whole else {
            val head = whole.dropLast(3)
            val headGrouped = head.reversed().chunked(2).joinToString(",").reversed()
            "$headGrouped,${whole.takeLast(3)}"
        }
        return if (d.scale() > 0) grouped + "." + d.setScale(2).toPlainString().substringAfter('.') else grouped
    }

    private fun date(value: String): String {
        val time = value.substringAfter('T', "")
        val day = value.substringBefore('T')
        val t = if (time.isEmpty()) "" else ", " + java.time.LocalTime.parse(time).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
        return if (day.startsWith("--")) {
            val md = LocalDate.of(2024, day.substring(2, 4).toInt(), day.substring(5, 7).toInt())
            md.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " (year not read)" + t
        } else LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) + t
    }

    private fun phone(v: String): String =
        if (v.startsWith("+91") && v.length == 13) "+91 ${v.substring(3, 8)} ${v.substring(8)}" else v
}

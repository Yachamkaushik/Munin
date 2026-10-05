package com.munin.app.calc

import com.munin.app.extract.FactExtractor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The answer to a date question and how it was read. */
data class DateAnswer(val headline: String, val detail: String?, val reading: String)

/**
 * Date arithmetic from plain phrases: "days until 15 October", "days since 12 Sep", "days between 1 Jan and 15 Oct", "today + 90 days",
 * "45 days from today", "tomorrow". Dates with no year mean the next occurrence for "until" and the most recent for "since"; Hindi and Telugu
 * month names work because dates are read with the same rules as the rest of the app.
 */
class DateMath(private val today: () -> LocalDate = { LocalDate.now() }) {
    private val full = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    fun evaluate(raw: String): DateAnswer? {
        val s = raw.lowercase().trim().trimEnd('?').replace(Regex("^(how\\s+many|how\\s+much|what\\s+is|what's|calculate)\\s+"), "").trim()
        val t = today()

        Regex("^(?:days?|time)\\s+(?:left\\s+)?(?:until|till|to|for|before)\\s+(.+)$").matchEntire(s)?.let { m ->
            val d = date(m.groupValues[1], future = true) ?: return null
            return span(t, d, "until", s)
        }
        Regex("^(?:days?)\\s+(?:since|from|after)\\s+(.+)$").matchEntire(s)?.let { m ->
            val d = date(m.groupValues[1], future = false) ?: return null
            return span(d, t, "since", s)
        }
        Regex("^(?:days?)\\s+between\\s+(.+?)\\s+and\\s+(.+)$").matchEntire(s)?.let { m ->
            val a = date(m.groupValues[1], future = false) ?: return null
            val b = date(m.groupValues[2], future = true) ?: return null
            val days = ChronoUnit.DAYS.between(a, b)
            return DateAnswer("${plural(Math.abs(days), "day")}", "Between ${full.format(a)} and ${full.format(b)}${weeks(Math.abs(days))}.", s)
        }
        // "<date> + 45 days", "today - 2 weeks", "45 days from today", "in 30 days"
        Regex("^(.+?)\\s*(\\+|plus|-|minus)\\s*(\\d+)\\s*(day|days|week|weeks|month|months)$").matchEntire(s)?.let { m ->
            val base = date(m.groupValues[1], future = true) ?: return null
            return shift(base, if (m.groupValues[2] in listOf("+", "plus")) 1 else -1, m.groupValues[3].toLong(), m.groupValues[4], s)
        }
        Regex("^(\\d+)\\s*(day|days|week|weeks|month|months)\\s+(from|after|before|ago)\\s*(.*)$").matchEntire(s)?.let { m ->
            val sign = if (m.groupValues[3] == "before" || m.groupValues[3] == "ago") -1 else 1
            val base = if (m.groupValues[3] == "ago" || m.groupValues[4].isBlank()) t else date(m.groupValues[4], future = true) ?: return null
            return shift(base, sign, m.groupValues[1].toLong(), m.groupValues[2], s)
        }
        Regex("^in\\s+(\\d+)\\s*(day|days|week|weeks|month|months)$").matchEntire(s)?.let { m -> return shift(t, 1, m.groupValues[1].toLong(), m.groupValues[2], s) }
        if (s in setOf("today", "tomorrow", "yesterday", "date today", "today's date", "what day is it", "what is today's date")) {
            val d = when (s) { "tomorrow" -> t.plusDays(1); "yesterday" -> t.minusDays(1); else -> t }
            return DateAnswer(full.format(d), null, s)
        }
        return null
    }

    private fun span(from: LocalDate, to: LocalDate, word: String, reading: String): DateAnswer {
        val days = ChronoUnit.DAYS.between(from, to)
        val other = if (word == "until") to else from
        val head = when {
            days == 0L -> "Today"
            word == "until" && days > 0 -> "${plural(days, "day")} until ${full.format(other)}"
            word == "until" -> "${full.format(other)} was ${plural(-days, "day")} ago"
            else -> "${plural(days, "day")} since ${full.format(other)}"
        }
        return DateAnswer(head, if (days == 0L) full.format(other) else weeks(Math.abs(days)).trimStart(' ', '(', ')').ifBlank { null }?.let { "That is $it." }, reading)
    }

    private fun shift(base: LocalDate, sign: Int, n: Long, unit: String, reading: String): DateAnswer {
        val d = when { unit.startsWith("day") -> base.plusDays(sign * n); unit.startsWith("week") -> base.plusWeeks(sign * n); else -> base.plusMonths(sign * n) }
        return DateAnswer(full.format(d), "${if (sign > 0) "" else "minus "}$n $unit ${if (sign > 0) "after" else "before"} ${full.format(base)}.", reading)
    }

    private fun weeks(days: Long) = if (days >= 14) " (about ${days / 7} weeks)" else ""
    private fun plural(n: Long, w: String) = "$n $w${if (n == 1L) "" else "s"}"

    /** A date from words: today, tomorrow, "15 october", "october 15 2026", "15/10/2026", Hindi/Telugu months. */
    private fun date(text: String, future: Boolean): LocalDate? {
        val t = today(); val s = text.trim()
        when (s) { "today", "now" -> return t; "tomorrow" -> return t.plusDays(1); "yesterday" -> return t.minusDays(1) }
        val fact = FactExtractor.extract(s).firstOrNull { it.type == com.munin.app.extract.FactType.DATE } ?: return null
        val v = fact.value.substringBefore('T')
        if (!v.startsWith("--")) return runCatching { LocalDate.parse(v) }.getOrNull()
        val month = v.substring(2, 4).toInt(); val day = v.substring(5, 7).toInt()
        for (year in (t.year - 1)..(t.year + 8)) {
            val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            if (future && !d.isBefore(t)) return d
        }
        if (!future) for (year in t.year downTo t.year - 8) { val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue; if (!d.isAfter(t)) return d }
        return null
    }

}

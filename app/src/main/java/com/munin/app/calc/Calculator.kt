package com.munin.app.calc

import com.munin.app.extract.Digits
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class CalcKind { ARITHMETIC, UNITS, DATE, CURRENCY }

sealed interface CalcOutcome {
    /** [primary] is the answer; [secondary] lines explain it; [reading] says how the input was understood. */
    data class Value(val kind: CalcKind, val primary: String, val secondary: List<String>, val reading: String, val copyText: String = primary) : CalcOutcome
    /** It is a calculation, but cannot be worked out (divide by zero, no exchange rate, units that do not match). */
    data class Failed(val message: String, val reading: String?) : CalcOutcome
    /** The user typed "1 usd = 83.5 inr": offered for saving, never saved without a tap. */
    data class RateProposal(val from: String, val to: String, val rate: BigDecimal) : CalcOutcome
}

/**
 * Decides whether the search box holds a calculation and works it out entirely on the phone: arithmetic (with percentages, lakh/crore and
 * Hindi/Telugu number words), unit conversion, date maths, and currency only with a rate the user saved themselves.
 */
class Calculator(private val today: () -> LocalDate = { LocalDate.now() }, private val rates: RateBook? = null) {
    private val dates = DateMath(today)
    private val MC = MathContext(30)
    private val short = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** Null when the input is not a calculation (so it is searched as usual). */
    fun evaluate(input: String): CalcOutcome? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        return try {
            proposal(raw) ?: currency(raw) ?: units(raw) ?: date(raw) ?: arithmetic(raw)
        } catch (e: CalcError) {
            CalcOutcome.Failed(e.message ?: "Cannot work that out.", raw)
        }
    }

    // ---- arithmetic ----

    private fun arithmetic(raw: String): CalcOutcome? {
        val e = Expression.parse(raw) ?: return null
        val words = Numbers.indianWords(e.value)
        return CalcOutcome.Value(CalcKind.ARITHMETIC, Numbers.format(e.value), listOfNotNull(words?.let { "= $it" }), "Worked out: ${e.reading}")
    }

    // ---- units ----

    private val NUMBER = Regex("^(?:convert\\s+)?(-?\\d[\\d,]*(?:\\.\\d+)?|-?\\.\\d+)\\s*(.+)$")
    private val SEPARATOR = Regex("\\s+(?:in|to|into|as)\\s+")

    private fun units(raw: String): CalcOutcome? {
        val s = Digits.toAscii(raw).lowercase().trim().trimEnd('?', '.')
        val m = NUMBER.matchEntire(s) ?: return null
        val amount = BigDecimal(m.groupValues[1].replace(",", ""))
        val rest = m.groupValues[2]
        for (sep in SEPARATOR.findAll(rest)) {
            val from = rest.substring(0, sep.range.first).trim(); val to = rest.substring(sep.range.last + 1).trim()
            val c = Units.convert(amount, from, to) ?: continue
            val secondary = listOfNotNull(c.factorNote.takeIf { it.isNotEmpty() }, Numbers.indianWords(c.value)?.let { "= $it ${c.to.symbol}" })
            return CalcOutcome.Value(CalcKind.UNITS, "${Numbers.format(c.value)} ${c.to.symbol}", secondary, "Converted ${Numbers.format(amount)} ${c.from.symbol} to ${c.to.symbol}")
        }
        return null
    }

    // ---- dates ----

    private fun date(raw: String): CalcOutcome? {
        val a = dates.evaluate(raw) ?: return null
        return CalcOutcome.Value(CalcKind.DATE, a.headline, listOfNotNull(a.detail), "Date maths: ${a.reading}")
    }

    // ---- currency (only with a rate the user saved) ----

    private val SYMBOLS = mapOf('$' to "usd", '€' to "eur", '£' to "gbp", '₹' to "inr")
    /** Real ISO currency codes only, so ordinary three-letter words ("foo", "box") are never read as money. */
    private val CURRENCIES = ("usd eur gbp inr aed sgd aud cad jpy cny chf sar qar kwd omr bhd myr thb idr lkr npr pkr bdt zar nzd hkd krw rub brl mxn try sek nok dkk pln czk huf ils egp ngn kes vnd php twd").split(' ').toSet()
    private fun isCurrency(code: String) = code in CURRENCIES

    private fun normaliseMoney(raw: String): String {
        var s = Digits.toAscii(raw).lowercase().trim().trimEnd('?', '.')
        s = Regex("([\\$€£₹])\\s*(\\d[\\d,.]*)").replace(s) { "${it.groupValues[2]} ${SYMBOLS.getValue(it.groupValues[1][0])}" }
        return s.replace(Regex("\\brs\\.?\\b|\\brupees?\\b"), "inr").replace(Regex("\\bdollars?\\b"), "usd").replace(Regex("\\beuros?\\b"), "eur").replace(Regex("\\bpounds? sterling\\b"), "gbp")
    }

    private val PROPOSAL = Regex("^(\\d[\\d,]*(?:\\.\\d+)?)?\\s*([a-z]{3})\\s*(?:=|is|equals)\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*([a-z]{3})$")

    private fun proposal(raw: String): CalcOutcome? {
        val m = PROPOSAL.matchEntire(normaliseMoney(raw)) ?: return null
        val from = m.groupValues[2]; val to = m.groupValues[4]
        if (!isCurrency(from) || !isCurrency(to) || from == to) return null
        val per = (m.groupValues[1].ifEmpty { "1" }).replace(",", "").toBigDecimal()
        val total = m.groupValues[3].replace(",", "").toBigDecimal()
        if (per.signum() == 0 || total.signum() == 0) return null
        return CalcOutcome.RateProposal(from.uppercase(), to.uppercase(), total.divide(per, MC).stripTrailingZeros())
    }

    private fun currency(raw: String): CalcOutcome? {
        val s = normaliseMoney(raw)
        val m = NUMBER.matchEntire(s) ?: return null
        val amount = BigDecimal(m.groupValues[1].replace(",", ""))
        for (sep in SEPARATOR.findAll(m.groupValues[2])) {
            val rest = m.groupValues[2]
            val from = rest.substring(0, sep.range.first).trim(); val to = rest.substring(sep.range.last + 1).trim()
            if (!isCurrency(from) || !isCurrency(to)) continue
            return convertMoney(amount, from.uppercase(), to.uppercase())
        }
        return null
    }

    private fun convertMoney(amount: BigDecimal, from: String, to: String): CalcOutcome {
        val book = rates
        val direct = book?.get(from, to); val inverse = book?.get(to, from)
        val (factor, saved) = when {
            direct != null -> direct.value to direct.savedOn
            inverse != null -> BigDecimal.ONE.divide(inverse.value, MC) to inverse.savedOn
            else -> return CalcOutcome.Failed(
                "No exchange rate is saved for $from to $to. Munin cannot look rates up because it has no internet access. To use your own rate, type for example: 1 $from = 83.5 $to",
                "$from to $to",
            )
        }
        val age = ChronoUnit.DAYS.between(saved, today())
        val ageText = when { age <= 0 -> "today"; age == 1L -> "1 day ago"; else -> "$age days ago" }
        val result = amount.multiply(factor, MC)
        val note = "Using your saved rate: 1 $from = ${Numbers.format(factor)} $to, saved ${short.format(saved)} ($ageText)." + if (age > 30) " This rate is old; real rates may differ a lot." else ""
        return CalcOutcome.Value(CalcKind.CURRENCY, "${Numbers.format(Numbers.tidy(result), 2)} $to", listOf(note), "Converted ${Numbers.format(amount)} $from to $to with your own rate")
    }

    /** Saves a rate the user approved. */
    fun save(p: CalcOutcome.RateProposal) { rates?.put(Rate(p.from, p.to, p.rate, today())) }
}

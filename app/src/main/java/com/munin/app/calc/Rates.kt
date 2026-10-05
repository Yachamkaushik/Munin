package com.munin.app.calc

import android.content.Context
import java.math.BigDecimal
import java.time.LocalDate

/** An exchange rate the user typed in: 1 [from] = [value] [to], saved on [savedOn]. Munin never fetches rates. */
data class Rate(val from: String, val to: String, val value: BigDecimal, val savedOn: LocalDate)

interface RateBook {
    fun get(from: String, to: String): Rate?
    fun put(rate: Rate)
}

class MemoryRateBook : RateBook {
    private val m = HashMap<String, Rate>()
    override fun get(from: String, to: String) = m["$from>$to"]
    override fun put(rate: Rate) { m["${rate.from}>${rate.to}"] = rate }
}

/** Rates kept in the app's private preferences: the only state the calculator has. */
class PrefsRateBook(context: Context) : RateBook {
    private val prefs = context.getSharedPreferences("munin_rates", Context.MODE_PRIVATE)
    override fun get(from: String, to: String): Rate? = prefs.getString("$from>$to", null)?.split('|')?.takeIf { it.size == 2 }?.let {
        runCatching { Rate(from, to, BigDecimal(it[0]), LocalDate.parse(it[1])) }.getOrNull()
    }
    override fun put(rate: Rate) { prefs.edit().putString("${rate.from}>${rate.to}", "${rate.value.toPlainString()}|${rate.savedOn}").apply() }
}

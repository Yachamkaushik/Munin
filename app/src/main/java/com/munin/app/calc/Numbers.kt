package com.munin.app.calc

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Display of calculator numbers: Indian digit grouping, trimmed decimals, and the lakh/crore reading of big values. */
object Numbers {
    /** "1234567.5" -> "12,34,567.5". At most [maxDecimals] places, trailing zeros removed. */
    fun format(value: BigDecimal, maxDecimals: Int = 6): String {
        val v = value.setScale(maxDecimals, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        val neg = v.signum() < 0
        val plain = v.abs().toPlainString()
        val whole = plain.substringBefore('.'); val frac = plain.substringAfter('.', "")
        return (if (neg) "-" else "") + group(whole) + if (frac.isNotEmpty()) ".$frac" else ""
    }

    fun group(whole: String): String {
        if (whole.length <= 3) return whole
        val head = whole.dropLast(3)
        return head.reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
    }

    /** "25 lakh", "1.5 crore", "2,500 crore" for values of a lakh or more, else null. Up to two decimals. */
    fun indianWords(value: BigDecimal): String? {
        val a = value.abs()
        val (unit, size) = when {
            a >= BigDecimal("10000000") -> "crore" to BigDecimal("10000000")
            a >= BigDecimal("100000") -> "lakh" to BigDecimal("100000")
            else -> return null
        }
        val n = (a.divide(size)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
        return (if (value.signum() < 0) "-" else "") + format(n, 2) + " " + unit
    }

    /** Trims a computed value to something printable: at most 12 significant digits for long divisions. */
    fun tidy(v: BigDecimal): BigDecimal = if (v.precision() > 20) v.round(java.math.MathContext(12)) else v

    fun isInteger(v: BigDecimal) = v.stripTrailingZeros().scale() <= 0 || v.remainder(BigDecimal.ONE).signum() == 0

    fun bigInt(v: BigDecimal): BigInteger = v.toBigInteger()
}

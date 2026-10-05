package com.munin.app.calc

import com.munin.app.extract.Digits
import java.math.BigDecimal
import java.math.MathContext

/** A worked-out expression and how it was read, e.g. "20% of 4,500". */
data class Evaluated(val value: BigDecimal, val reading: String)

/** Why an input that looked like a calculation could not be worked out. */
class CalcError(message: String) : Exception(message)

/**
 * Arithmetic with `+ - * / ^`, brackets, percentages ("20% of 4500", "4500 + 18%"), Indian magnitude words ("2.5 lakh", "10k") and number words in
 * English, Hindi and Telugu. Uses exact decimal arithmetic.
 *
 * [parse] returns null for anything that is not clearly a calculation, so ordinary searches ("hostel fee 45,000", a date like 12-09-2026, a phone
 * number) are never hijacked: the whole input must be numbers and operators, and it must contain an operator, a percentage or a number word.
 */
object Expression {
    private sealed interface Tok {
        data class Num(val v: BigDecimal, val word: Boolean) : Tok
        data class Op(val c: Char) : Tok
        data object Pct : Tok
        data object Of : Tok
        data object LP : Tok
        data object RP : Tok
    }

    private val DATE_LIKE = Regex("\\d{1,4}\\s*[-/.]\\s*\\d{1,2}\\s*[-/.]\\s*\\d{1,4}")
    private val JOINED_DIGITS = Regex("^\\d+(?:[-/]\\d+)+$")
    private val FILLER = Regex("^(?:what\\s+is|what's|whats|calculate|calc|compute|how\\s+much\\s+is|solve)\\s+")
    private val MC = MathContext(30)

    fun parse(input: String): Evaluated? {
        var s = Digits.toAscii(input).lowercase().trim().trimEnd('?', '=', ' ')
        s = FILLER.replace(s, "").trim()
        if (s.isEmpty() || DATE_LIKE.containsMatchIn(s)) return null
        // digits joined only by hyphens or slashes ("040-23456789", "12-09", "100/4") are phone numbers, ids and dates far more often than sums;
        // to calculate, put spaces around the operator ("100 - 20", "100 / 4")
        if (JOINED_DIGITS.matches(s)) return null
        s = s.replace("×", "*").replace("÷", "/").replace("−", "-").replace("–", "-")
        s = s.replace(Regex("(?<=[\\d)%])\\s*x\\s*(?=[\\d(])"), "*")
        s = s.replace(Regex("\\bmultiplied\\s+by\\b|\\btimes\\b|\\binto\\b"), "*").replace(Regex("\\bdivided\\s+by\\b|\\bover\\b"), "/")
        s = s.replace(Regex("\\bplus\\b"), "+").replace(Regex("\\bminus\\b"), "-").replace(Regex("\\bper\\s*cent\\b|\\bpercent\\b"), "%")

        val toks = tokenize(s) ?: return null
        val hasOperation = toks.any { it is Tok.Op || it is Tok.Of } // a lone "5%" is not a calculation
        val hasWord = toks.any { it is Tok.Num && it.word }
        if (!hasOperation && !hasWord) return null // a bare number is not a calculation
        if (toks.none { it is Tok.Num }) return null
        val p = Parser(toks)
        val v = try { p.expr().also { if (p.i != toks.size) return null } } catch (e: ParseFail) { return null }
        return Evaluated(Numbers.tidy(v.n.let { if (v.pct) it.divide(BigDecimal(100), MC) else it }), reading(s))
    }

    private fun reading(s: String): String = s.replace(Regex("(\\d)(?=(?:\\d{3})+(?!\\d))"), "$1").trim()

    // ---- tokenizer ----

    private fun tokenize(s: String): List<Tok>? {
        val out = ArrayList<Tok>(); var i = 0
        val wordTokens = ArrayList<String>()
        fun flushWords(): Boolean {
            var j = 0
            while (j < wordTokens.size) {
                val w = wordTokens[j]
                if (w == "of") { out += Tok.Of; j++; continue }
                // a magnitude word right after a number: "2.5 lakh", "10k". Checked first, or "lakh" would be read as a number of its own.
                val last = out.lastOrNull()
                val mult = NumberWords.suffix(w)
                if (last is Tok.Num && mult != null) { out[out.size - 1] = Tok.Num(last.v * mult, true); j++; continue }
                val run = NumberWords.parseRun(wordTokens, j)
                if (run != null) { out += Tok.Num(run.first, true); j += run.second; continue }
                return false // an unknown word: not a calculation
            }
            wordTokens.clear(); return true
        }
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> { i++ }
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    if (!flushWords()) return null
                    var j = i; var seenDot = false
                    while (j < s.length && (s[j].isDigit() || (s[j] == ',' && j + 1 < s.length && s[j + 1].isDigit()) || (s[j] == '.' && !seenDot && j + 1 < s.length && s[j + 1].isDigit()))) { if (s[j] == '.') seenDot = true; j++ }
                    out += Tok.Num(BigDecimal(s.substring(i, j).replace(",", "")), false); i = j
                }
                c in "+-*/^" -> { if (!flushWords()) return null; out += Tok.Op(c); i++ }
                c == '%' -> { if (!flushWords()) return null; out += Tok.Pct; i++ }
                c == '(' -> { if (!flushWords()) return null; out += Tok.LP; i++ }
                c == ')' -> { if (!flushWords()) return null; out += Tok.RP; i++ }
                c.isLetter() || Character.getType(c).toByte() in WORD_MARKS -> {
                    var j = i
                    while (j < s.length && (s[j].isLetter() || Character.getType(s[j]).toByte() in WORD_MARKS)) j++
                    wordTokens += s.substring(i, j); i = j
                }
                else -> return null
            }
            // a digit number directly followed by a word is handled when the word run flushes at the next symbol or at the end
        }
        if (!flushWords()) return null
        return out
    }

    private val WORD_MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)

    // ---- parser ----

    private class ParseFail : Exception()
    private class V(val n: BigDecimal, val pct: Boolean = false)

    private class Parser(val t: List<Tok>) {
        var i = 0
        private fun peek() = t.getOrNull(i)
        private fun fail(): Nothing = throw ParseFail()

        fun expr(): V {
            var left = term()
            while (true) {
                val op = (peek() as? Tok.Op)?.takeIf { it.c == '+' || it.c == '-' } ?: break
                i++
                var right = term()
                // "4500 + 18%" means 4500 plus 18% of 4500
                if (right.pct && !left.pct) right = V(left.n.multiply(right.n, MC).divide(BigDecimal(100), MC))
                left = V(if (op.c == '+') left.n + right.n else left.n - right.n, left.pct && right.pct)
            }
            return left
        }

        private fun term(): V {
            var left = factor()
            while (true) {
                when (val tk = peek()) {
                    is Tok.Of -> {
                        i++; var right = factor()
                        if (!left.pct) fail() // "of" only follows a percentage
                        // "10% of 20% of 50000" reads right to left: 20% of 50000 first
                        while (right.pct && peek() is Tok.Of) { i++; val inner = factor(); right = V(right.n.divide(BigDecimal(100), MC).multiply(plain(inner), MC)) }
                        left = V(left.n.divide(BigDecimal(100), MC).multiply(plain(right), MC))
                    }
                    is Tok.Op -> {
                        if (tk.c != '*' && tk.c != '/') return left
                        i++; val right = factor()
                        val l = plain(left); val r = plain(right)
                        left = V(if (tk.c == '*') l.multiply(r, MC) else { if (r.signum() == 0) throw CalcError("Cannot divide by zero."); l.divide(r, MC) })
                    }
                    else -> return left
                }
            }
        }

        private fun plain(v: V) = if (v.pct) v.n.divide(BigDecimal(100), MC) else v.n

        private fun factor(): V {
            val base = unary()
            if ((peek() as? Tok.Op)?.c == '^') {
                i++; val e = plain(factor())
                return V(power(plain(base), e))
            }
            return base
        }

        private fun unary(): V {
            val op = peek() as? Tok.Op
            if (op != null && (op.c == '-' || op.c == '+')) { i++; val v = unary(); return V(if (op.c == '-') v.n.negate() else v.n, v.pct) }
            return atom()
        }

        private fun atom(): V = when (val tk = peek()) {
            is Tok.Num -> { i++; if (peek() is Tok.Pct) { i++; V(tk.v, true) } else V(tk.v) }
            is Tok.LP -> { i++; val v = expr(); if (peek() !is Tok.RP) fail(); i++; if (peek() is Tok.Pct) { i++; V(plain(v), true) } else v }
            else -> fail()
        }

        private fun power(b: BigDecimal, e: BigDecimal): BigDecimal {
            if (e.stripTrailingZeros().scale() <= 0 && e.abs() <= BigDecimal(1000)) {
                val n = e.toInt()
                return if (n >= 0) b.pow(n, MC) else BigDecimal.ONE.divide(b.pow(-n, MC), MC)
            }
            val r = Math.pow(b.toDouble(), e.toDouble())
            if (r.isNaN() || r.isInfinite()) throw CalcError("That result is too large or not a real number.")
            return BigDecimal(r, MathContext(12))
        }
    }
}

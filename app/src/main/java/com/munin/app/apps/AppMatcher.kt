package com.munin.app.apps

import java.text.Normalizer

/** An installed app that can be launched. [component] is "package/activity" and is what gets started. */
data class AppEntry(val label: String, val packageName: String, val component: String)

data class AppMatch(val app: AppEntry, val score: Double)

/**
 * Finds apps by name, tolerant of typos ("whatsap") and of the script the name is written in: Telugu and Hindi spellings are compared by sound
 * ([PhoneticKey]). Pure and in memory, so it is instant and testable.
 */
object AppMatcher {
    const val MIN_SCORE = 0.7
    private const val MAX_RESULTS = 4

    /** Words around an app name that are not part of it: "open whatsapp", "whatsapp app", Telugu యాప్ / ఓపెన్, Hindi ऐप / खोलो. */
    private val NOISE = setOf("open", "launch", "start", "run", "app", "apps", "application", "యాప్", "ఓపెన్", "ऐप", "ऐप्प", "खोलो", "खोलें", "ओपन")

    /** Common short names people type for an app (matched against the label by sound too). */
    private val ALIASES = mapOf("gpay" to "google pay", "yt" to "youtube", "fb" to "facebook", "insta" to "instagram", "wa" to "whatsapp", "gmail" to "gmail", "ig" to "instagram")

    fun search(query: String, apps: List<AppEntry>, limit: Int = MAX_RESULTS): List<AppMatch> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.lowercase() !in NOISE }
        if (words.isEmpty() || words.size > 3 || query.length > 40) return emptyList() // long phrases are searches, not app names
        val q = words.joinToString(" ").lowercase().let { ALIASES[it] ?: it }
        if (q.none { it.isLetter() }) return emptyList()
        return apps.map { AppMatch(it, score(q, it)) }.filter { it.score >= MIN_SCORE }
            .sortedWith(compareByDescending<AppMatch> { it.score }.thenBy { it.app.label.lowercase() }).take(limit)
    }

    private fun score(q: String, app: AppEntry): Double {
        val label = app.label.lowercase()
        var best = textScore(norm(q), norm(label), tokens(label))
        // an app's package name often carries the brand even when the label is localised ("com.whatsapp")
        val pkgTail = app.packageName.substringAfterLast('.').lowercase()
        best = maxOf(best, textScore(norm(q), norm(pkgTail), listOf(norm(pkgTail))) * 0.95)
        best = maxOf(best, phoneticScore(q, label))
        return best
    }

    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).filter { it.isLetterOrDigit() || Character.getType(it).toByte() in MARKS }.lowercase()
    private val MARKS = setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)
    private fun tokens(label: String) = label.split(Regex("[\\s\\-_.]+")).map(::norm).filter { it.isNotEmpty() }

    private fun textScore(q: String, l: String, toks: List<String>): Double {
        if (q.length < 2 || l.isEmpty()) return 0.0
        return when {
            q == l -> 1.0
            toks.any { it == q } -> 0.95
            l.startsWith(q) -> 0.93
            toks.any { it.startsWith(q) } -> 0.9
            q.length >= 3 && l.contains(q) -> 0.8
            else -> {
                val tol = tolerance(maxOf(q.length, l.length))
                val d = minOf(distance(q, l), toks.minOfOrNull { distance(q, it) } ?: Int.MAX_VALUE)
                if (d <= tol) 0.8 - 0.05 * (d - 1).coerceAtLeast(0) else 0.0
            }
        }
    }

    private fun phoneticScore(q: String, label: String): Double {
        val kq = PhoneticKey.of(q)
        if (kq.length < 3) return 0.0
        val keys = listOf(PhoneticKey.of(label)) + tokens(label).map(PhoneticKey::of)
        var best = 0.0
        for (kl in keys.filter { it.length >= 2 }) {
            best = maxOf(best, when {
                kq == kl -> 0.82
                kl.startsWith(kq) -> 0.72
                else -> { val d = soundDistance(kq, kl); if (d <= soundTolerance(maxOf(kq.length, kl.length))) 0.74 - 0.03 * (d - 1).coerceAtLeast(0.0) else 0.0 }
            })
        }
        return best
    }

    /** Allowed sound differences: a 3-sound name tolerates one *voicing* swap (g/j in Gmail written जीमेल), longer names one or two slips. */
    private fun soundTolerance(len: Int) = when { len >= 8 -> 2.0; len >= 4 -> 1.0; len == 3 -> 0.5; else -> 0.0 }

    /** Pairs of sounds that spellings in different scripts routinely swap; replacing one by the other costs half a slip. */
    private val CONFUSABLE = setOf("gk", "jc", "dt", "bp", "gj", "sj", "sc").flatMap { listOf(it, it.reversed()) }.toSet()

    /** Edit distance on sound keys where a confusable substitution costs 0.5. */
    internal fun soundDistance(a: String, b: String): Double {
        val d = Array(a.length + 1) { DoubleArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i.toDouble()
        for (j in 0..b.length) d[0][j] = j.toDouble()
        for (i in 1..a.length) for (j in 1..b.length) {
            val sub = when { a[i - 1] == b[j - 1] -> 0.0; "${a[i - 1]}${b[j - 1]}" in CONFUSABLE -> 0.5; else -> 1.0 }
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + sub)
        }
        return d[a.length][b.length]
    }

    /** One slip is allowed once a name is 4+ letters long, two once it is 8+. */
    private fun tolerance(len: Int) = when { len >= 8 -> 2; len >= 4 -> 1; else -> 0 }

    /** Damerau-Levenshtein (adjacent swaps count as one slip). */
    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}

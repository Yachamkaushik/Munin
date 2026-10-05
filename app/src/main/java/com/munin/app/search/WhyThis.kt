package com.munin.app.search

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A plain-words reason a file appeared in the results, built only from facts the search actually has. */
object WhyThis {
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun explain(r: SearchResult, zone: ZoneId = ZoneId.systemDefault()): String {
        val words = r.snippet.highlights.map { r.snippet.text.substring(it.first, it.last + 1) }.distinctBy { it.lowercase() }.take(3)
        val noun = if (r.displayName.contains("screenshot", ignoreCase = true)) "screenshot" else "image"
        val from = r.itemDate?.let { " from ${DAY.format(Instant.ofEpochSecond(it).atZone(zone))}" }.orEmpty()
        val where = "the text in your $noun$from"
        val quoted = words.joinToString(", ") { "“$it”" }
        return when {
            r.keywordRank != null && words.isNotEmpty() && r.meaningRank != null -> "Matched $quoted in $where, and it is close in meaning to your search."
            r.keywordRank != null && words.isNotEmpty() -> "Matched $quoted in $where."
            r.keywordRank != null -> "Matched your words in $where."
            r.meaningRank != null -> "No exact words matched; the text in your $noun$from is close in meaning to your search."
            else -> "Found in your $noun$from."
        }
    }
}

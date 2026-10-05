package com.munin.app.notifications

/** What to keep from a notification and how to look it up. Pure, so it is unit tested. */
object NotificationFilter {
    const val RETENTION_DAYS = 30
    const val MAX_ROWS = 5000
    const val MAX_TEXT_CHARS = 1000
    const val MAX_TERMS = 5
    const val RESULT_LIMIT = 40

    private val SKIP_CATEGORIES = setOf("progress", "service", "transport", "call", "alarm", "navigation", "sys", "err", "stopwatch", "status")
    // explicit lookarounds, because \\b does not treat Devanagari or Telugu combining marks as word characters
    private val CODE_WORDS = Regex("(?<![\\p{L}\\p{M}\\p{N}])(otp|one[- ]time|verification|passcode|password|cvv|pin|security code|login code|code is|कोड|ओटीपी|పాస్‌వర్డ్|ఓటీపీ)(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /** One-time codes and similar secrets are never worth keeping: a message that mentions a code word together with a 4 to 8 digit number is skipped whole. */
    fun looksSensitive(title: String, text: String): Boolean {
        val all = "$title $text"
        return CODE_WORDS.containsMatchIn(all) && DIGITS.containsMatchIn(all)
    }

    fun clean(raw: String?): String = (raw ?: "").replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_CHARS)

    /** True when this notification belongs in the history. */
    fun shouldStore(packageName: String, ownPackage: String, ongoing: Boolean, groupSummary: Boolean, category: String?, title: String, text: String): Boolean = when {
        packageName == ownPackage -> false
        ongoing || groupSummary -> false // music players, timers, "app is running" notices
        category != null && category in SKIP_CATEGORIES -> false
        title.isBlank() && text.isBlank() -> false
        looksSensitive(title, text) -> false
        else -> true
    }

    /** Words to look for: all of them must appear (anywhere in the app name, title or text). Drops one-letter bits and caps the count. */
    fun terms(query: String): List<String> =
        query.trim().split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }.distinct().take(MAX_TERMS)

    /** Escapes % _ and \\ for use in a LIKE pattern. */
    fun escapeLike(term: String): String = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun cutoff(nowMillis: Long, days: Int = RETENTION_DAYS): Long = nowMillis - days * 24L * 3600_000L
}

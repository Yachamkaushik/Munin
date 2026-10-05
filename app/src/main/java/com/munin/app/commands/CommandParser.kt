package com.munin.app.commands

/** A quick command read from the search box. Pure data; the confirm dialog shows it and only then is an intent built. */
sealed interface QuickCommand {
    /** [hour] is 0 to 23. */
    data class Alarm(val hour: Int, val minute: Int) : QuickCommand
    data class Timer(val seconds: Int) : QuickCommand
}

/**
 * Reads "alarm 6:30 am", "set alarm for 7", "timer 10 minutes", "1 hour 30 min timer", also అలారం / टाइमर style words.
 * A time without am/pm is ambiguous, so both readings are returned and the user picks one; nothing is guessed.
 */
object CommandParser {
    private val ALARM = setOf("alarm", "అలారం", "అలారమ్", "अलार्म", "అలార్మ్")
    private val TIMER = setOf("timer", "టైమర్", "टाइमर")
    private val FILLER = setOf("set", "an", "a", "the", "for", "at", "to", "me", "my", "please", "wake", "up", "start", "put", "create", "of", "in", "సెట్", "పెట్టు", "लगाओ", "सेट", "करो", "के", "लिए", "కి")
    private val SEC = setOf("s", "sec", "secs", "second", "seconds", "సెకన్లు", "సెకను", "सेकंड", "सेकण्ड")
    private val MIN = setOf("m", "min", "mins", "minute", "minutes", "నిమిషాలు", "నిమిషం", "నిమిషాల", "मिनट")
    private val HOUR = setOf("h", "hr", "hrs", "hour", "hours", "గంట", "గంటలు", "గంటల", "घंटा", "घंटे", "घण्टा")
    private const val MAX_TIMER_SECONDS = 24 * 3600

    fun parse(input: String): List<QuickCommand> {
        val text = input.trim().lowercase()
        if (text.isEmpty() || text.length > 40) return emptyList()
        val words = Regex("[\\p{L}\\p{M}]+|\\d+(?:[:.]\\d+)?|:").findAll(text).map { it.value }.toList()
        // "6am" / "10min" arrive glued; split digits from letters
        val tokens = words.flatMap { w -> Regex("\\d+(?:[:.]\\d+)?|[\\p{L}\\p{M}]+").findAll(w).map { it.value }.toList() }
        val isAlarm = tokens.any { it in ALARM }
        val isTimer = tokens.any { it in TIMER }
        if (isAlarm == isTimer) return emptyList()
        val rest = tokens.filter { it !in ALARM && it !in TIMER && it !in FILLER }
        return if (isAlarm) alarm(rest) else timer(rest)
    }

    private fun alarm(rest: List<String>): List<QuickCommand> {
        if (rest.isEmpty()) return emptyList()
        val clock = rest.firstOrNull() ?: return emptyList()
        if (!clock[0].isDigit()) return emptyList()
        val parts = clock.split(':', '.')
        val h = parts[0].toIntOrNull() ?: return emptyList()
        val m = if (parts.size > 1) parts[1].toIntOrNull() ?: return emptyList() else 0
        if (parts.size > 2 || m !in 0..59) return emptyList()
        val tail = rest.drop(1)
        val meridiem = tail.singleOrNull()
        val am = meridiem in setOf("am", "a", "morning", "ఉదయం", "सुबह")
        val pm = meridiem in setOf("pm", "p", "evening", "night", "afternoon", "సాయంత్రం", "రాత్రి", "మధ్యాహ్నం", "शाम", "रात", "दोपहर")
        if (tail.isNotEmpty() && !am && !pm) return emptyList() // something else follows: probably a search, not a command
        return when {
            h > 23 -> emptyList()
            h == 0 || h >= 13 -> if (am || pm) emptyList() else listOf(QuickCommand.Alarm(h, m)) // 24-hour time is unambiguous
            am -> listOf(QuickCommand.Alarm(h % 12, m))
            pm -> listOf(QuickCommand.Alarm(h % 12 + 12, m))
            else -> listOf(QuickCommand.Alarm(h % 12, m), QuickCommand.Alarm(h % 12 + 12, m)) // 1 to 12 with no am/pm
        }
    }

    private fun timer(rest: List<String>): List<QuickCommand> {
        var seconds = 0L
        var i = 0
        var any = false
        while (i < rest.size) {
            val n = rest[i].toIntOrNull() ?: return emptyList()
            val unit = rest.getOrNull(i + 1) ?: return emptyList() // a bare number is not guessed: it could be minutes or seconds
            seconds += n.toLong() * when (unit) { in SEC -> 1; in MIN -> 60; in HOUR -> 3600; else -> return emptyList() }
            any = true
            i += 2
        }
        return if (any && seconds in 1..MAX_TIMER_SECONDS) listOf(QuickCommand.Timer(seconds.toInt())) else emptyList()
    }
}

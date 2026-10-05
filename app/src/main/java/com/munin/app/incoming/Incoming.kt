package com.munin.app.incoming

/** Something another app handed to Munin: selected or shared text, or a shared image. */
sealed interface Incoming {
    data class Text(val text: String) : Incoming
    data class Image(val uri: String) : Incoming

    /** Opened from the Quick Settings tile or the home-screen widget: show search with the keyboard ready. */
    data object OpenSearch : Incoming
}

/**
 * Turns the raw pieces of an Android intent into an [Incoming]. Kept free of Android classes so it is unit tested.
 * The caller reads the intent: [action], MIME [type], and the text/uri extras as plain strings.
 */
object IncomingParser {
    const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_OPEN_SEARCH = "com.munin.app.action.OPEN_SEARCH"
    const val MAX_QUERY_CHARS = 300

    fun parse(action: String?, type: String?, processText: String?, sendText: String?, streamUri: String?): Incoming? = when (action) {
        ACTION_OPEN_SEARCH -> Incoming.OpenSearch
        ACTION_PROCESS_TEXT -> cleanText(processText)?.let { Incoming.Text(it) }
        ACTION_SEND -> when {
            type?.startsWith("image/") == true -> streamUri?.takeIf { it.isNotBlank() }?.let { Incoming.Image(it) }
            type?.startsWith("text/") == true || type == null -> cleanText(sendText)?.let { Incoming.Text(it) }
            else -> null
        }
        else -> null
    }

    /** Collapses whitespace and cuts very long text at a word boundary, so a pasted page still becomes a usable search. */
    fun cleanText(raw: String?): String? {
        val t = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length <= MAX_QUERY_CHARS) return t
        val cut = t.take(MAX_QUERY_CHARS)
        return cut.substringBeforeLast(' ', cut).trim()
    }
}

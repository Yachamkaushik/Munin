package com.munin.app.contacts

import com.munin.app.apps.AppMatcher

/** One phone number of a saved contact, as read from the phone's contacts. */
data class ContactEntry(val id: Long, val name: String, val number: String)

/**
 * Finds contacts by name and by family nickname: "amma", "అమ్మ" and "mom" all find a contact saved as Amma or Mom. A nickname must match a whole word of
 * the saved name ("amma" finds "Amma" but not "Ammamma"). Other names are matched like app names: typos and Telugu/Hindi spellings tolerated.
 */
object ContactMatcher {
    private const val MIN_SCORE = 0.8
    private const val MAX_RESULTS = 4

    /** Words that only say "this is about a person": removed before matching. */
    val NOISE = setOf("call", "dial", "phone", "contact", "number", "ring", "కాల్", "ఫోన్", "కాంటాక్ట్", "कॉल", "फोन", "फ़ोन", "नंबर")

    /** Family words people save contacts under, in the three scripts. Each group is one meaning. */
    val NICKNAMES: List<List<String>> = listOf(
        listOf("amma", "ammaa", "mom", "mother", "mummy", "mum", "అమ్మ", "అమ్మా", "माँ", "मां", "मम्मी", "माता"),
        listOf("nanna", "nana", "dad", "daddy", "father", "papa", "appa", "నాన్న", "నాన్నా", "పాపా", "पापा", "पिताजी", "डैडी"),
        listOf("akka", "didi", "sister", "sis", "అక్క", "दीदी"),
        listOf("anna", "bhaiya", "bhai", "brother", "bro", "అన్న", "భయ్యా", "भैया", "भाई"),
        listOf("thatha", "tata", "taata", "grandpa", "dada", "తాత", "दादा"),
        listOf("ammamma", "nayanamma", "grandma", "dadi", "అమ్మమ్మ", "నాయనమ్మ", "दादी", "नानी"),
        listOf("tammudu", "thammudu", "తమ్ముడు", "छोटा भाई"),
        listOf("chelli", "చెల్లి", "बहन"),
    )

    /** The nickname group a typed word belongs to, if any. Used to decide when to offer the contacts permission. */
    fun nicknameGroup(word: String): List<String>? = NICKNAMES.firstOrNull { g -> g.any { it.equals(word.trim(), ignoreCase = true) } }

    fun cleaned(query: String): String? {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it.lowercase() !in NOISE }
        if (words.isEmpty() || words.size > 3 || query.length > 40) return null
        return words.joinToString(" ")
    }

    fun search(query: String, contacts: List<ContactEntry>): List<ContactEntry> {
        val q = cleaned(query)?.lowercase() ?: return emptyList()
        if (q.length < 3 || q.none { it.isLetter() }) return emptyList()
        val group = nicknameGroup(q)
        val scored = contacts.mapNotNull { c ->
            val s = if (group != null) group.maxOf { AppMatcher.nameScore(it, c.name) }.let { if (it >= 0.95) it else 0.0 }
            else AppMatcher.nameScore(q, c.name)
            if (s >= MIN_SCORE) c to s else null
        }
        return scored.sortedWith(compareByDescending<Pair<ContactEntry, Double>> { it.second }.thenBy { it.first.name.lowercase() })
            .map { it.first }.distinctBy { it.id to it.number.filter(Char::isDigit).takeLast(10) }.take(MAX_RESULTS)
    }
}

package com.munin.app.apps

import java.text.Normalizer

/**
 * A rough "how it sounds" skeleton of a name, the same for Latin, Telugu and Devanagari spellings: vowels are dropped and similar consonants
 * share a letter, so "వాట్సాప్", "वॉट्सऐप" and "whatsapp" all become `vtsp`, and "క్రోమ్" and "Chrome" become `krm`.
 *
 * Deliberately crude: it only has to bring spellings of one name close enough for an edit-distance check. Languages other than English,
 * Telugu and Hindi (Devanagari) are not mapped.
 */
object PhoneticKey {
    private val TELUGU = buildMap<Char, Char> {
        "కఖ".forEach { put(it, 'k') }; "గఘ".forEach { put(it, 'g') }; "చఛ".forEach { put(it, 'c') }; "జఝ".forEach { put(it, 'j') }
        "టఠతథ".forEach { put(it, 't') }; "డఢదధ".forEach { put(it, 'd') }; "నణఙఞ".forEach { put(it, 'n') }; "పఫ".forEach { put(it, 'p') }; "బభ".forEach { put(it, 'b') }
        put('మ', 'm'); put('య', 'y'); "రఱ".forEach { put(it, 'r') }; "లళ".forEach { put(it, 'l') }; put('వ', 'v'); "శషస".forEach { put(it, 's') }
        put('ం', 'n'); put('ఁ', 'n')
    }
    private val DEVANAGARI = buildMap<Char, Char> {
        "कखक़ख़".forEach { put(it, 'k') }; "गघग़".forEach { put(it, 'g') }; "चछ".forEach { put(it, 'c') }; "जझज़".forEach { put(it, 'j') }
        "टठतथ".forEach { put(it, 't') }; "डढदधड़ढ़".forEach { put(it, 'd') }; "नणङञ".forEach { put(it, 'n') }; "पफफ़".forEach { put(it, 'p') }; "बभ".forEach { put(it, 'b') }
        put('म', 'm'); put('य', 'y'); put('र', 'r'); "लळ".forEach { put(it, 'l') }; put('व', 'v'); "शषस".forEach { put(it, 's') }
        put('ं', 'n'); put('ँ', 'n')
    }

    /** The skeleton of [text] ("" if it has no letters). */
    fun of(text: String): String {
        val sb = StringBuilder()
        // Latin runs are rewritten as a whole (digraphs need context); Indic characters map one by one.
        val latin = StringBuilder()
        fun flushLatin() { if (latin.isNotEmpty()) { sb.append(latinKey(latin.toString())); latin.setLength(0) } }
        for (c in text) {
            val indic = TELUGU[c] ?: DEVANAGARI[c]
            when {
                indic != null -> { flushLatin(); sb.append(indic) }
                c.isLetter() && c.code < 0x250 -> latin.append(c)
                else -> flushLatin() // vowels, signs, spaces, digits: no sound in the key
            }
        }
        flushLatin()
        return collapse(sb)
    }

    private fun latinKey(word: String): String {
        var s = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).filter { it.isLetter() && it.code < 0x80 }
        s = s.replace("chr", "kr").replace("ch", "C").replace("ck", "k").replace("qu", "k").replace("ph", "p").replace("sh", "s").replace("kh", "k").replace("gh", "g")
            .replace("bh", "b").replace("dh", "d").replace("th", "t").replace("wh", "v").replace("x", "ks")
        s = s.replace(Regex("c(?=[eiy])"), "s").replace('c', 'k').replace('C', 'c').replace('q', 'k').replace('z', 'j').replace('f', 'p').replace('w', 'v')
        return s.filter { it !in "aeiouh" }
    }

    private fun collapse(s: CharSequence): String {
        val out = StringBuilder()
        for (c in s) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }
}

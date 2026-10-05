package com.munin.app.calc

import java.math.BigDecimal

/**
 * Number words in English, Hindi and Telugu, and the Indian magnitude words (thousand, lakh, crore).
 *
 * Covers the common spellings; Hindi and Telugu have many spelling variants (पाँच/पांच, హజార్...), so an unusual one may not be recognised, in which
 * case the input is simply not treated as a calculation.
 */
object NumberWords {
    private enum class Kind { SMALL, TENS, HUNDRED, MULT }
    private class Word(val kind: Kind, val value: BigDecimal)

    private fun d(n: Long) = BigDecimal.valueOf(n)
    private fun d(s: String) = BigDecimal(s)

    private val words = HashMap<String, Word>()

    private fun small(value: Long, vararg names: String) = names.forEach { words[it] = Word(Kind.SMALL, d(value)) }
    private fun tens(value: Long, vararg names: String) = names.forEach { words[it] = Word(Kind.TENS, d(value)) }
    private fun mult(value: Long, vararg names: String) = names.forEach { words[it] = Word(Kind.MULT, d(value)) }
    private fun hundred(vararg names: String) = names.forEach { words[it] = Word(Kind.HUNDRED, d(100)) }

    init {
        // ---- English ----
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen").forEachIndexed { i, w -> small(i.toLong(), w) }
        listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety").forEachIndexed { i, w -> tens((i + 2) * 10L, w) }
        hundred("hundred"); mult(1_000, "thousand"); mult(100_000, "lakh", "lakhs", "lac", "lacs"); mult(10_000_000, "crore", "crores"); mult(1_000_000, "million"); mult(1_000_000_000, "billion")

        // ---- Hindi: 0-99 each have their own word ----
        val hi = "शून्य,एक,दो,तीन,चार,पाँच,छह,सात,आठ,नौ,दस,ग्यारह,बारह,तेरह,चौदह,पंद्रह,सोलह,सत्रह,अठारह,उन्नीस,बीस,इक्कीस,बाईस,तेईस,चौबीस,पच्चीस,छब्बीस,सत्ताईस,अट्ठाईस,उनतीस," +
            "तीस,इकतीस,बत्तीस,तैंतीस,चौंतीस,पैंतीस,छत्तीस,सैंतीस,अड़तीस,उनतालीस,चालीस,इकतालीस,बयालीस,तैंतालीस,चौवालीस,पैंतालीस,छियालीस,सैंतालीस,अड़तालीस,उनचास,पचास," +
            "इक्यावन,बावन,तिरपन,चौवन,पचपन,छप्पन,सत्तावन,अट्ठावन,उनसठ,साठ,इकसठ,बासठ,तिरसठ,चौंसठ,पैंसठ,छियासठ,सड़सठ,अड़सठ,उनहत्तर,सत्तर,इकहत्तर,बहत्तर,तिहत्तर,चौहत्तर,पचहत्तर,छिहत्तर," +
            "सतहत्तर,अठहत्तर,उन्यासी,अस्सी,इक्यासी,बयासी,तिरासी,चौरासी,पचासी,छियासी,सतासी,अट्ठासी,नवासी,नब्बे,इक्यानवे,बानवे,तिरानवे,चौरानवे,पंचानवे,छियानवे,सत्तानवे,अट्ठानवे,निन्यानवे"
        hi.split(',').forEachIndexed { i, w -> small(i.toLong(), w) }
        small(5, "पांच"); small(6, "छः", "छे"); small(15, "पन्द्रह"); small(16, "सोलह"); small(18, "अट्ठारह"); small(28, "अठाईस"); small(40, "चालिस"); small(49, "उनचास")
        words["डेढ़"] = Word(Kind.SMALL, d("1.5")); words["डेढ"] = words.getValue("डेढ़"); words["ढाई"] = Word(Kind.SMALL, d("2.5")); words["सवा"] = Word(Kind.SMALL, d("1.25"))
        hundred("सौ", "सैकड़ा"); mult(1_000, "हज़ार", "हजार"); mult(100_000, "लाख"); mult(10_000_000, "करोड़", "करोड", "करोड़ों"); mult(1_000_000_000, "अरब")

        // ---- Telugu ----
        small(0, "సున్న", "సున్నా"); small(1, "ఒకటి", "ఒక"); small(2, "రెండు"); small(3, "మూడు"); small(4, "నాలుగు"); small(5, "ఐదు", "అయిదు"); small(6, "ఆరు"); small(7, "ఏడు")
        small(8, "ఎనిమిది"); small(9, "తొమ్మిది"); small(10, "పది"); small(11, "పదకొండు"); small(12, "పన్నెండు"); small(13, "పదమూడు"); small(14, "పద్నాలుగు", "పధ్నాలుగు")
        small(15, "పదిహేను"); small(16, "పదహారు"); small(17, "పదిహేడు"); small(18, "పద్దెనిమిది"); small(19, "పంతొమ్మిది")
        tens(20, "ఇరవై"); tens(30, "ముప్పై"); tens(40, "నలభై"); tens(50, "యాభై", "ఏభై"); tens(60, "అరవై"); tens(70, "డెబ్బై"); tens(80, "ఎనభై", "ఎనభై"); tens(90, "తొంభై")
        hundred("వంద", "వందలు", "నూరు"); mult(1_000, "వెయ్యి", "వేయి", "వేలు"); mult(100_000, "లక్ష", "లక్షలు"); mult(10_000_000, "కోటి", "కోట్లు")
    }

    /** Multiplier for a magnitude word that follows a digit number: "2.5 lakh", "10k". Null if [w] is not one. */
    fun suffix(w: String): BigDecimal? = when (w) {
        "k", "thousand", "hazar", "hazaar" -> d(1_000)
        "lakh", "lakhs", "lac", "lacs", "l" -> if (w == "l") null else d(100_000)
        "cr", "crore", "crores" -> d(10_000_000)
        "mn", "million" -> d(1_000_000)
        "bn", "billion" -> d(1_000_000_000)
        "hundred" -> d(100)
        else -> words[w]?.takeIf { it.kind == Kind.MULT || it.kind == Kind.HUNDRED }?.value
    }

    fun isNumberWord(w: String) = w in words || w == "and"

    /**
     * Reads the longest run of number words starting at [start] ("दो लाख पचास हज़ार", "twenty five", "ఇరవై ఐదు"). Returns the value and the number
     * of tokens consumed, or null if the first token is not a number word.
     */
    fun parseRun(tokens: List<String>, start: Int): Pair<BigDecimal, Int>? {
        var total = BigDecimal.ZERO; var cur = BigDecimal.ZERO
        var i = start; var consumed = 0; var any = false
        while (i < tokens.size) {
            val t = tokens[i]
            if (t == "and" && any) { i++; consumed++; continue } // "two hundred and fifty"
            val w = words[t] ?: break
            when (w.kind) {
                Kind.SMALL, Kind.TENS -> cur += w.value
                Kind.HUNDRED -> cur = (if (cur.signum() == 0) BigDecimal.ONE else cur) * w.value
                Kind.MULT -> { total += (if (cur.signum() == 0) BigDecimal.ONE else cur) * w.value; cur = BigDecimal.ZERO }
            }
            any = true; i++; consumed++
        }
        // a trailing "and" belongs to the sentence, not the number
        while (consumed > 0 && tokens[start + consumed - 1] == "and") consumed--
        return if (any) (total + cur) to consumed else null
    }
}

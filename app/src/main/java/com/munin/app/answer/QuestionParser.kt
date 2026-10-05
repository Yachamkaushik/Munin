package com.munin.app.answer

import com.munin.app.extract.FactType
import com.munin.app.search.QueryTerms

/** A query that asks for a value, e.g. "how much was the hostel fee" -> [FactType.AMOUNT]. */
data class Question(
    val kind: FactType,
    /** Every word of the query, lowercased; used to match labels like "due" or "paid". */
    val terms: Set<String>,
    /** The words that say what the question is *about* ("hostel", "fee"), with question words removed. */
    val topic: List<String>,
)

/**
 * Decides whether a query asks for an amount, date, phone number or address, in English, Hindi, Telugu, Roman-script
 * Telugu/Hindi, or a mix. Plain rules, no model. A query that is merely a description ("hostel fee receipt") is
 * *not* a question and gets no answer card.
 */
object QuestionParser {
    private class Trigger(val kind: FactType, val pattern: Regex, val needsMarker: Boolean = false)

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val TRIGGERS = listOf(
        // amount: interrogatives are enough on their own
        Trigger(FactType.AMOUNT, r("how much|how many rupees|\\bwhat(?:'s| is| was)? the (?:amount|total|price|cost)|ఎంత|कितना|कितनी|कितने|\\bentha\\b|\\benta\\b|\\bkitna\\b|\\bkitni\\b|\\bkitne\\b")),
        // amount: bare nouns need a question marker ("what is the amount", "amount?"), so "amount ₹500" stays a search
        Trigger(FactType.AMOUNT, r("\\bamount\\b|\\btotal\\b|\\bprice\\b|\\bcost\\b|राशि|रकम|మొత్తం"), needsMarker = true),
        Trigger(FactType.DATE, r("\\bwhen\\b|what date|which date|what day|due date|last date|deadline|expiry date|expires?\\b|ఎప్పుడు|తేదీ|(?<![\\p{L}\\p{M}])कब(?![\\p{L}\\p{M}])|तारीख|तिथि|दिनांक|\\beppudu\\b|\\bkab\\b|\\btarikh\\b|\\btareekh\\b")),
        Trigger(FactType.DATE, r("\\bdate\\b"), needsMarker = true),
        Trigger(FactType.PHONE, r("phone number|mobile number|contact number|phone no|mobile no|contact no|helpline|ఫోన్ నంబర్|ఫోన్ నెంబర్|మొబైల్ నంబర్|ఫోన్ నంబరు|फोन नंबर|फ़ोन नंबर|मोबाइल नंबर|संपर्क नंबर|phone nambar|mobile number")),
        Trigger(FactType.PHONE, r("\\bnumber\\b|नंबर|నంబర్"), needsMarker = true),
        Trigger(FactType.ADDRESS, r("\\baddress\\b|\\bvenue\\b|చిరునామా|का पता|पता क्या|पता बताओ|\\bka pata\\b|\\bpata kya\\b")),
    )

    /** Words that make a bare noun trigger count as a question. */
    private val MARKER = r("\\?|\\bwhat\\b|\\bwhich\\b|\\btell\\b|\\bwhats\\b|क्या|बताओ|बताइए|ఏమిటి|చెప్పు|ఏంటి|\\bkya\\b|\\benti\\b")

    /** Spending totals are the ledger's job (step 6), not a single-document answer. */
    private val SPENDING = r("\\bspend\\b|\\bspent\\b|\\bspending\\b|\\bexpenses?\\b|खर्च|ఖర్చు|\\bkharcha\\b|\\bkharch\\b")

    private val PRIORITY = listOf(FactType.PHONE, FactType.ADDRESS, FactType.DATE, FactType.AMOUNT)

    /** Words that belong to the question rather than the topic. */
    private val QUESTION_WORDS = setOf(
        "amount", "total", "price", "cost", "how", "much", "many", "rupees", "what", "which", "when", "date", "day", "tell", "show", "give",
        "phone", "mobile", "contact", "number", "no", "address", "venue", "helpline", "last", "deadline", "expiry", "expires", "kya", "enti",
        "ఎంత", "कितना", "कितनी", "कितने", "ఎప్పుడు", "తేదీ", "कब", "तारीख", "तिथि", "दिनांक", "क्या", "बताओ", "बताइए", "ఏమిటి", "చెప్పు",
        "ఏంటి", "మొత్తం", "राशि", "रकम", "नंबर", "నంబర్", "ఫోన్", "फोन", "फ़ोन", "मोबाइल", "మొబైల్", "చిరునామా", "पता", "का", "entha", "enta", "kitna",
        "kitni", "kitne", "eppudu", "kab", "tarikh", "tareekh", "ka", "pata", "is", "was", "the", "of", "me", "my",
    )

    /**
     * Everyday words about *paying* that say what is being asked, not which document: "when do I **pay**" while the receipt says "paid",
     * Hindi भरना/जमा, Telugu కట్టాను/కట్టాలి, Roman kattanu/bharna. Written from general knowledge of how these questions are phrased.
     */
    private val GENERIC_WORDS = setOf(
        "pay", "paid", "paying", "payment", "payments", "spend", "spent", "cost", "costs", "charge", "charged", "buy", "bought", "purchase", "purchased",
        "give", "gave", "get", "got", "take", "took", "need", "needed", "money", "rupees", "rs",
        "भरना", "भरनी", "भरी", "भरा", "भरे", "जमा", "दिया", "दी", "चुकाया", "चुकाई", "लगा", "लगी", "लगे", "दें",
        "కట్టాను", "కట్టాలి", "కట్టాం", "చెల్లించాను", "చెల్లించాలి", "ఇచ్చాను", "అయ్యింది", "అయింది",
        "kattanu", "kattali", "chellinchanu", "ichchanu", "ayyindi", "bhara", "bharna", "bhari", "jama", "diya", "chukaya", "laga", "lagi",
    )

    /**
     * The question's topic words for the grounding gate. [functionWords] drops Hindi/Telugu/Roman function words (so "था" or "కి" is not a
     * topic); [genericWords] also drops everyday payment verbs. Both default to off, which is the topic the original gate used.
     */
    fun groundingTopic(q: Question, functionWords: Boolean, genericWords: Boolean): List<String> =
        q.topic.filterNot { functionWords && QueryTerms.isFunctionWord(it) }.filterNot { genericWords && it in GENERIC_WORDS }

    fun parse(query: String): Question? {
        val q = query.trim()
        if (q.isEmpty() || SPENDING.containsMatchIn(q)) return null
        val marker = MARKER.containsMatchIn(q)
        val hit = TRIGGERS.mapNotNull { t ->
            if (t.needsMarker && !marker) return@mapNotNull null
            t.pattern.find(q)?.let { t.kind to it.range.first }
        }.minWithOrNull(compareBy<Pair<FactType, Int>> { it.second }.thenBy { PRIORITY.indexOf(it.first) }) ?: return null

        val terms = QueryTerms.tokens(q).toSet()
        return Question(hit.first, terms, terms.filterNot { it in QUESTION_WORDS })
    }
}

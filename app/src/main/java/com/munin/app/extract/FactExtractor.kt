package com.munin.app.extract

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Rule-based extraction of amounts, dates, phone numbers and addresses from OCR text. No model is involved.
 *
 * OCR damages the very things these rules look for (the rupee sign is often dropped or read as "I", spaces after
 * colons vanish, Hindi text may carry Devanagari digits), so each rule has more than one route to a value and a
 * confidence that records which route was used.
 */
object FactExtractor {
    /** Bump when the rules change; stored items with an older version are re-extracted from their saved text. */
    const val VERSION = 5

    /** 1..12 for an English, Hindi or Telugu month name or abbreviation, else null. */
    fun monthNumber(word: String): Int? = MONTHS[word.lowercase()]

    /** Splits text into the same trimmed, non-empty lines the chunker produces, so line numbers agree. */
    fun lines(text: String): List<String> = text.lines().map { it.trim().replace(WS, " ") }.filter { it.isNotEmpty() }

    fun extract(text: String, sanity: AmountSanity.Mode = AmountSanity.DEFAULT): List<ExtractedFact> {
        val lines = lines(text)
        val out = ArrayList<ExtractedFact>()
        lines.forEachIndexed { i, original ->
            val line = Digits.toAscii(original)
            val masked = line.toCharArray()
            val (labelRaw, _) = splitLabel(original)
            val label = labelRaw?.trim()?.trimEnd(':', '-', ' ')
            val idLine = label != null && ID_LABEL.containsMatchIn(label)

            out += phones(line, original, label, i, masked, idLine)
            out += dates(line, original, label, i, masked)
            if (sanity == AmountSanity.Mode.OFF) {
                out += amounts(line, original, label, i, masked, idLine, previous = lines.getOrNull(i - 1))
            } else {
                // Amounts are read from a copy in which look-alike digits are repaired (same length, so offsets and the raw text still agree).
                val fixed = AmountSanity.repair(line)
                val amountMasked = if (fixed.changed) CharArray(masked.size) { k -> if (masked[k] == line[k]) fixed.text[k] else masked[k] } else masked
                out += amounts(fixed.text, original, label, i, amountMasked, idLine, previous = lines.getOrNull(i - 1)).flatMap { sane(it, original, sanity) }
            }
        }
        out += addresses(lines)
        return out.distinctBy { Triple(it.type, it.value, it.line) }
    }

    /** Applies the amount sanity rules of [mode] to one amount found on a line; [original] is the line as the reader produced it. */
    private fun sane(f: ExtractedFact, original: String, mode: AmountSanity.Mode): List<ExtractedFact> {
        var out = f
        if (AmountSanity.hasLookalike(f.raw)) out = out.copy(confidence = minOf(out.confidence, AmountSanity.REPAIRED))
        if (mode >= AmountSanity.Mode.GUARD) {
            val start = original.indexOf(f.raw)
            if (start >= 0 && AmountSanity.touchesForeignDigit(original, start + f.raw.length)) out = out.copy(confidence = minOf(out.confidence, AmountSanity.DAMAGED))
        }
        val result = mutableListOf(out)
        if (mode >= AmountSanity.Mode.ALTERNATIVE && f.confidence == BARE_AMOUNT && f.raw.none { it in "₹Rs" }) {
            AmountSanity.withoutFirstDigit(f.value)?.let { alt -> result += out.copy(value = alt, confidence = AmountSanity.ALTERNATE) }
        }
        return result
    }

    /** Confidence given to a bare number alone on a line (see [amounts]). */
    private const val BARE_AMOUNT = 0.45f

    // ---- phones ----------------------------------------------------------------------------------------------------

    private val MOBILE = Regex("(?<!\\d)(\\+?91[\\s\\-]?)?([6-9]\\d{4}[\\s\\-]?\\d{5})(?!\\d)")
    private val TOLL_FREE = Regex("(?<!\\d)(1800[\\s\\-]?\\d{3}[\\s\\-]?\\d{3,4})(?!\\d)")
    private val LANDLINE = Regex("(?<!\\d)(0\\d{2,4}[\\s\\-]?\\d{6,8})(?!\\d)")
    private val PHONE_LABEL = Regex("phone|mobile|\\bmob\\b|contact|\\bcall\\b|\\btel\\b|helpline|whatsapp|ఫోన్|మొబైల్|ఫోను|फोन|फ़ोन|मोबाइल|संपर्क", RegexOption.IGNORE_CASE)

    private fun phones(line: String, original: String, label: String?, index: Int, masked: CharArray, idLine: Boolean): List<ExtractedFact> {
        val found = ArrayList<ExtractedFact>()
        val labelled = label != null && PHONE_LABEL.containsMatchIn(label)
        for (m in MOBILE.findAll(line)) {
            val hasCode = m.groupValues[1].isNotEmpty()
            if (idLine && !hasCode) continue
            val digits = m.groupValues[2].filter(Char::isDigit)
            val spaced = m.groupValues[2].any { it == ' ' || it == '-' }
            val conf = when { labelled -> 0.9f; hasCode -> 0.85f; spaced -> 0.75f; else -> 0.5f }
            found += ExtractedFact(FactType.PHONE, "+91$digits", original.substring(m.range), label, index, conf)
            mask(masked, m.range)
        }
        for (m in TOLL_FREE.findAll(line)) {
            found += ExtractedFact(FactType.PHONE, m.groupValues[1].filter(Char::isDigit), original.substring(m.range), label, index, if (labelled) 0.9f else 0.7f)
            mask(masked, m.range)
        }
        if (!idLine) for (m in LANDLINE.findAll(line)) {
            found += ExtractedFact(FactType.PHONE, m.groupValues[1].filter(Char::isDigit), original.substring(m.range), label, index, if (labelled) 0.8f else 0.5f)
            mask(masked, m.range)
        }
        return found
    }

    // ---- dates -----------------------------------------------------------------------------------------------------

    private val MONTHS: Map<String, Int> = buildMap {
        listOf(
            "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6, "july" to 7, "august" to 8,
            "september" to 9, "october" to 10, "november" to 11, "december" to 12,
            "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9,
            "oct" to 10, "nov" to 11, "dec" to 12,
            "जनवरी" to 1, "फ़रवरी" to 2, "फरवरी" to 2, "मार्च" to 3, "अप्रैल" to 4, "मई" to 5, "जून" to 6, "जुलाई" to 7, "अगस्त" to 8,
            "सितंबर" to 9, "सितम्बर" to 9, "अक्टूबर" to 10, "अक्तूबर" to 10, "नवंबर" to 11, "नवम्बर" to 11, "दिसंबर" to 12, "दिसम्बर" to 12,
            "జనవరి" to 1, "ఫిబ్రవరి" to 2, "మార్చి" to 3, "ఏప్రిల్" to 4, "మే" to 5, "జూన్" to 6, "జూలై" to 7, "జులై" to 7,
            "ఆగస్టు" to 8, "ఆగష్టు" to 8, "సెప్టెంబర్" to 9, "సెప్టెంబరు" to 9, "అక్టోబర్" to 10, "అక్టోబరు" to 10,
            "నవంబర్" to 11, "నవంబరు" to 11, "డిసెంబర్" to 12, "డిసెంబరు" to 12,
        ).forEach { put(it.first, it.second) }
    }
    private val MONTH_ALT = MONTHS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val NUMERIC_DATE = Regex("(?<!\\d)(\\d{1,2})\\s?[/\\-.]\\s?(\\d{1,2})\\s?[/\\-.]\\s?(\\d{4}|\\d{2})(?!\\d)")
    private val DAY_MONTH = Regex("(?<!\\d)(\\d{1,2})(?:st|nd|rd|th)?[\\s\\-.,]*($MONTH_ALT)\\.?(?:[\\s\\-.,]*(\\d{4}|\\d{2})(?!\\d))?", RegexOption.IGNORE_CASE)
    private val MONTH_DAY_YEAR = Regex("($MONTH_ALT)\\.?\\s*(\\d{1,2})(?:st|nd|rd|th)?\\s*,?\\s*(\\d{4})(?!\\d)", RegexOption.IGNORE_CASE)
    private val TIME = Regex("(?<!\\d)(\\d{1,2})[:.](\\d{2})\\s?(AM|PM)?(?![\\d])", RegexOption.IGNORE_CASE)

    private fun dates(line: String, original: String, label: String?, index: Int, masked: CharArray): List<ExtractedFact> {
        val found = ArrayList<ExtractedFact>()
        fun add(range: IntRange, date: String?, conf: Float) {
            if (date == null) return
            val time = timeAfter(line, range.last + 1)
            val value = if (time != null) "${date}T$time" else date
            found += ExtractedFact(FactType.DATE, value, original.substring(range), label, index, conf)
            mask(masked, range)
        }
        val src = String(masked)
        for (m in NUMERIC_DATE.findAll(src)) {
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt()
            val y = m.groupValues[3]; val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            // India writes day first; month-first is only used when the first number cannot be a month... and vice versa.
            val (day, month, conf) = when {
                b > 12 && a <= 12 -> Triple(b, a, 0.6f)
                else -> Triple(a, b, if (y.length == 2) 0.7f else 0.85f)
            }
            add(m.range, iso(year, month, day), conf)
        }
        val src2 = String(masked)
        for (m in DAY_MONTH.findAll(src2)) {
            val month = MONTHS[m.groupValues[2].lowercase()] ?: continue
            val y = m.groupValues[3]
            val day = m.groupValues[1].toInt()
            if (y.isEmpty()) add(m.range, yearless(month, day), 0.5f)
            else add(m.range, iso(if (y.length == 2) 2000 + y.toInt() else y.toInt(), month, day), if (y.length == 2) 0.7f else 0.9f)
        }
        val src3 = String(masked)
        for (m in MONTH_DAY_YEAR.findAll(src3)) {
            val month = MONTHS[m.groupValues[1].lowercase()] ?: continue
            add(m.range, iso(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()), 0.9f)
        }
        return found
    }

    /** A time directly after a date ("20 Sep 2026, 6:45 PM"), as HH:mm. */
    private fun timeAfter(line: String, from: Int): String? {
        val m = TIME.find(line, from) ?: return null
        if (m.range.first - from > 4) return null // must be right next to the date, not elsewhere on the line
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        val ampm = m.groupValues[3].uppercase()
        if (ampm == "PM" && h < 12) h += 12
        if (ampm == "AM" && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) "%02d:%02d".format(h, min) else null
    }

    private fun iso(year: Int, month: Int, day: Int): String? =
        runCatching { LocalDate.of(year, month, day).toString() }.getOrNull()

    private fun yearless(month: Int, day: Int): String? =
        runCatching { LocalDate.of(2024, month, day); "--%02d-%02d".format(month, day) }.getOrNull() // 2024: leap year, so 29 Feb is allowed

    // ---- amounts ---------------------------------------------------------------------------------------------------

    private const val NUM = "(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)(?:\\.\\d{1,2})?"
    private const val CUR = "(?:₹|Rs\\.?|INR|रु\\.?|रुपये|रुपए|రూ\\.?|రూపాయలు)"
    private val CUR_PREFIX = Regex("$CUR\\s?($NUM)", RegexOption.IGNORE_CASE)
    private val CUR_SUFFIX = Regex("($NUM)\\s?(?:/-|rs\\b|rupees|रुपये|రూపాయలు)", RegexOption.IGNORE_CASE)
    private val PLAIN_NUM = Regex("(?<![\\d,.])($NUM)(?![\\d,]|\\.\\d)")
    private val STANDALONE = Regex("^[₹ITt?|\u0966-\u096F\u0C66-\u0C6F\u0660-\u0669]?\\s?($NUM)$")
    private val AMOUNT_LABEL = Regex(
        "amount|paid|total|fees?\\b|due|balance|payable|price|charges?|bill|cost|राशि|रकम|शुल्क|कुल|जमा|मूल्य|మొత్తం|ఫీజు|చెల్లించిన|ధర",
        RegexOption.IGNORE_CASE,
    )
    private val ID_LABEL = Regex(
        "transaction|txn|\\bref\\b|reference|utr|\\bid\\b|order|booking|account|a/c|invoice|receipt no|\\bno\\b|number|संख्या|नंबर|సంఖ్య|నంబర్",
        RegexOption.IGNORE_CASE,
    )

    private fun amounts(
        line: String, original: String, label: String?, index: Int, masked: CharArray, idLine: Boolean, previous: String?,
    ): List<ExtractedFact> {
        val found = ArrayList<ExtractedFact>()
        val src = String(masked)
        fun add(m: MatchResult, group: Int, conf: Float, lbl: String?) {
            val value = parseAmount(m.groupValues[group]) ?: return
            found += ExtractedFact(FactType.AMOUNT, value, original.substring(m.range).trim(), lbl, index, conf)
            mask(masked, m.range)
        }
        for (m in CUR_PREFIX.findAll(src)) add(m, 1, 0.9f, labelBefore(line, m.range.first) ?: label)
        for (m in CUR_SUFFIX.findAll(String(masked))) add(m, 1, 0.85f, labelBefore(line, m.range.first) ?: label)

        if (found.isEmpty() && !idLine) {
            val trimmed = String(masked).trim()
            val standalone = STANDALONE.find(trimmed)
            if (standalone != null && standalone.groupValues[1].count(Char::isDigit) >= 3) {
                // A bare number on its own line, as on payment screenshots where the rupee sign was not read.
                val own = ExtractedFact(FactType.AMOUNT, parseAmount(standalone.groupValues[1]) ?: "", original.trim(), previous?.takeIf { it.length <= 40 }, index, 0.45f)
                if (own.value.isNotEmpty()) found += own
            } else if (label != null && AMOUNT_LABEL.containsMatchIn(label)) {
                for (m in PLAIN_NUM.findAll(String(masked))) {
                    val digits = m.groupValues[1].count(Char::isDigit)
                    if (digits < 3 && !m.groupValues[1].contains(',')) continue
                    add(m, 1, 0.7f, label)
                }
            }
        }
        return found
    }

    /** The words just before position [at] on the line, e.g. "Amount paid" for "Amount paid Rs 45,000". */
    private fun labelBefore(line: String, at: Int): String? {
        val before = line.substring(0, at).trim().trimEnd(':', '-', ' ', 'ः')
        val tail = before.substringAfterLast(':', before).trim()
        return tail.takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    /** Plain decimal text for an amount ("1,25,000" -> "125000", "45,000.50" -> "45000.50"), or null if absurd. */
    fun parseAmount(s: String): String? {
        val d = runCatching { BigDecimal(s.replace(",", "")) }.getOrNull() ?: return null
        if (d.signum() <= 0 || d > MAX_AMOUNT) return null
        return d.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
    }
    private val MAX_AMOUNT = BigDecimal("100000000") // 10 crore: anything bigger is far likelier to be an id

    // ---- addresses -------------------------------------------------------------------------------------------------

    private val ADDRESS_LABEL = Regex("^(address|addr|venue|location|deliver(?:y)? to|ship(?:ping)? to|చిరునామా|पता)\\s*(?:[:ः\\-–]|$)", RegexOption.IGNORE_CASE)
    private val PIN = Regex("(?<!\\d)[1-9]\\d{2}\\s?\\d{3}(?!\\d)")
    private val ANY_LABELLED_LINE = Regex("^[A-Za-z][A-Za-z .()/]{1,30}\\s*:")

    private fun addresses(lines: List<String>): List<ExtractedFact> {
        val found = ArrayList<ExtractedFact>()
        val used = HashSet<Int>()
        lines.forEachIndexed { i, original ->
            val m = ADDRESS_LABEL.find(original) ?: return@forEachIndexed
            val first = original.substring(m.range.last + 1).trimStart(':', 'ः', ' ', '-')
            val block = ArrayList<String>()
            if (first.isNotEmpty()) block += first
            var j = i + 1
            while (j < lines.size && block.size < 4 && !ANY_LABELLED_LINE.containsMatchIn(lines[j]) && !ADDRESS_LABEL.containsMatchIn(lines[j])) { block += lines[j]; j++ }
            if (block.isEmpty()) return@forEachIndexed
            (i until j).forEach(used::add)
            found += ExtractedFact(FactType.ADDRESS, block.joinToString(", "), block.joinToString("\n"), m.value.trimEnd(':', 'ः', '-', '–', ' '), i, 0.8f)
        }
        lines.forEachIndexed { i, original ->
            if (i in used || ID_LABEL.containsMatchIn(original.substringBefore(':')) && ':' in original) return@forEachIndexed
            val pin = PIN.find(Digits.toAscii(original)) ?: return@forEachIndexed
            // A PIN code closes an address: take up to two lines above it plus the PIN line, if they are not labelled fields.
            val block = ArrayList<String>()
            for (k in maxOf(0, i - 2)..i) if (k !in used && !ANY_LABELLED_LINE.containsMatchIn(lines[k]) || k == i) block += lines[k]
            if (block.size < 2 && pin.range.first < 6) return@forEachIndexed // a lone "500081" is not an address
            found += ExtractedFact(FactType.ADDRESS, block.joinToString(", "), block.joinToString("\n"), null, i, 0.6f)
        }
        return found
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Splits "Label: value" at the first colon that is not part of a time like 10:30. */
    private fun splitLabel(line: String): Pair<String?, String> {
        for (i in line.indices) {
            val c = line[i]
            if ((c == ':' || c == '：' || c == 'ः') && !(i > 0 && i + 1 < line.length && line[i - 1].isDigit() && line[i + 1].isDigit())) {
                val label = line.substring(0, i).trim()
                return if (label.isNotEmpty() && label.length <= 40) label to line.substring(i + 1).trim() else null to line
            }
        }
        return null to line
    }

    private fun mask(masked: CharArray, range: IntRange) { for (i in range) masked[i] = ' ' }

    private val WS = Regex("\\s+")
}

package com.munin.app.actions

import com.munin.app.answer.AnswerFormat
import com.munin.app.extract.FactType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ActionKind { CALENDAR, CALL, MAPS, SHARE }

/** A fact plus enough context to act on it. Built from an answer card or from the item detail screen. */
data class ActionSubject(
    val kind: FactType,
    /** Normalized stored value (ISO date, +91 phone, plain amount, joined address). */
    val value: String,
    val label: String?,
    /** The text as the OCR read it. */
    val raw: String,
    /** The item's first line of text, e.g. "Electricity Bill"; used to name things. */
    val itemTitle: String,
    val sourceName: String,
    /** True when the extractor itself was unsure (guessed amount, missing year, ...). */
    val lowConfidence: Boolean,
)

/** What to hand to the other app. Pure data, so the plan can be inspected and tested without Android. */
sealed interface ActionPayload {
    data class Calendar(val title: String, val description: String, val startMillis: Long, val endMillis: Long, val allDay: Boolean) : ActionPayload
    data class Call(val number: String) : ActionPayload
    data class Maps(val query: String) : ActionPayload
    data class Share(val text: String, val subject: String) : ActionPayload
}

/**
 * One thing the user can do with a fact. [details] and [notes] are exactly what the confirmation dialog shows,
 * so the user sees the value, and what will happen, before anything leaves Munin.
 */
data class ActionPlan(
    val kind: ActionKind,
    val buttonLabel: String,
    val dialogTitle: String,
    val confirmLabel: String,
    val details: List<Pair<String, String>>,
    val notes: List<String>,
    val payload: ActionPayload,
)

/** Decides which actions a fact offers and what each one will do. [today] and [zone] are injectable for tests. */
class ActionPlanner(
    private val today: () -> LocalDate = { LocalDate.now() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun plans(s: ActionSubject): List<ActionPlan> = buildList {
        when (s.kind) {
            FactType.DATE -> calendar(s)?.let { add(it) }
            FactType.PHONE -> add(call(s))
            FactType.ADDRESS -> add(maps(s))
            FactType.AMOUNT -> Unit
        }
        add(share(s))
    }

    // ---- calendar --------------------------------------------------------------------------------------------------

    private fun calendar(s: ActionSubject): ActionPlan? {
        val parsed = parseDate(s.value) ?: return null
        val notes = ArrayList<String>()
        val date = parsed.date ?: run {
            val next = nextOccurrence(parsed.month, parsed.day) ?: return null
            notes += "The year was not in the text, so the next ${format(parsed.month, parsed.day)} (${next.year}) is used. You can change it in the calendar."
            next
        }
        if (date.isBefore(today())) notes += "This date has already passed."
        if (s.lowConfidence && parsed.date != null) notes += "The date was read from the image with low confidence. Check it."

        val title = eventTitle(s)
        val description = "From Munin: ${s.sourceName}" + if (s.raw.isNotBlank()) " (read as \"${s.raw}\")" else ""
        val time = parsed.time
        val (start, end, allDay) = if (time == null) {
            // All-day events are stored as UTC midnight to UTC midnight.
            val begin = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            Triple(begin, begin + DAY_MS, true)
        } else {
            val begin = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
            Triple(begin, begin + HOUR_MS, false)
        }
        val whenText = if (time == null) "${date.format(DATE_FMT)} (all day)" else "${date.format(DATE_FMT)}, ${time.format(TIME_FMT)}"
        notes += "Your calendar app opens with these details filled in. Nothing is saved until you save it there."
        return ActionPlan(
            ActionKind.CALENDAR, "Add to calendar", "Add to calendar?", "Open calendar",
            listOf("Title" to title, "When" to whenText, "Notes" to description), notes,
            ActionPayload.Calendar(title, description, start, end, allDay),
        )
    }

    private fun eventTitle(s: ActionSubject): String {
        val label = s.label?.trim()?.trimEnd(':', '-', ' ')?.takeIf { it.isNotEmpty() }
        val title = when {
            label != null && s.itemTitle.isNotBlank() -> "${s.itemTitle.trim()}: $label"
            label != null -> label
            s.itemTitle.isNotBlank() -> s.itemTitle.trim()
            else -> "Date from ${s.sourceName}"
        }
        return title.take(MAX_TITLE)
    }

    private class ParsedDate(val date: LocalDate?, val month: Int, val day: Int, val time: LocalTime?)

    /** Stored dates look like `2026-10-15`, `2026-09-20T18:45` or `--11-03` (year not read). */
    private fun parseDate(value: String): ParsedDate? = runCatching {
        val day = value.substringBefore('T')
        val time = value.substringAfter('T', "").takeIf { it.isNotEmpty() }?.let(LocalTime::parse)
        if (day.startsWith("--")) ParsedDate(null, day.substring(2, 4).toInt(), day.substring(5, 7).toInt(), time)
        else LocalDate.parse(day).let { ParsedDate(it, it.monthValue, it.dayOfMonth, time) }
    }.getOrNull()

    /** The next date on or after today with this month and day; 29 Feb waits for the next leap year. */
    private fun nextOccurrence(month: Int, day: Int): LocalDate? {
        val t = today()
        for (year in t.year..t.year + 8) {
            val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: continue
            if (!d.isBefore(t)) return d
        }
        return null
    }

    private fun format(month: Int, day: Int) = LocalDate.of(2024, month, day).format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH))

    // ---- call, maps, share -----------------------------------------------------------------------------------------

    private fun call(s: ActionSubject): ActionPlan {
        val shown = AnswerFormat.display(FactType.PHONE, s.value)
        val notes = mutableListOf("Opens the dialer with this number. You still have to press call.")
        if (s.lowConfidence) notes.add(0, "The number was read from the image with low confidence. Check it.")
        return ActionPlan(ActionKind.CALL, "Call", "Call this number?", "Open dialer", listOf("Number" to shown), notes, ActionPayload.Call(s.value))
    }

    private fun maps(s: ActionSubject): ActionPlan {
        val notes = mutableListOf("Opens your maps app searching for this address. The text is passed to that app.")
        if (s.lowConfidence) notes.add(0, "The address was read from the image with low confidence. Check it.")
        return ActionPlan(ActionKind.MAPS, "Open in maps", "Open in maps?", "Open maps", listOf("Address" to s.value), notes, ActionPayload.Maps(s.value))
    }

    private fun share(s: ActionSubject): ActionPlan {
        val shown = AnswerFormat.display(s.kind, s.value)
        val what = s.label?.trim()?.trimEnd(':', '-', ' ')?.takeIf { it.isNotEmpty() } ?: s.kind.name.lowercase().replaceFirstChar { it.uppercase() }
        val head = if (s.itemTitle.isNotBlank()) "${s.itemTitle.trim()} - $what: $shown" else "$what: $shown"
        val text = "$head\nFrom: ${s.sourceName} (read by Munin from the image; check the original)"
        val notes = mutableListOf("You choose where to send it on the next screen.")
        if (s.lowConfidence) notes.add(0, "This value was read with low confidence. Check it before sending.")
        return ActionPlan(ActionKind.SHARE, "Share", "Share this?", "Choose app", listOf("Text to share" to text), notes, ActionPayload.Share(text, head))
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val HOUR_MS = 60L * 60 * 1000
        const val MAX_TITLE = 100
        val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    }
}

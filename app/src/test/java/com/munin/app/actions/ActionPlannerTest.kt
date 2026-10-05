package com.munin.app.actions

import com.munin.app.extract.FactType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlannerTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private fun planner(today: String = "2026-09-25") = ActionPlanner({ LocalDate.parse(today) }, ist)

    private fun subject(kind: FactType, value: String, label: String? = null, raw: String = value, title: String = "Electricity Bill", low: Boolean = false) =
        ActionSubject(kind, value, label, raw, title, "bill.png", low)

    private fun calendar(s: ActionSubject, today: String = "2026-09-25") =
        planner(today).plans(s).single { it.kind == ActionKind.CALENDAR }

    @Test fun whichActionsEachKindOffers() {
        fun kinds(s: ActionSubject) = planner().plans(s).map { it.kind }
        assertEquals(listOf(ActionKind.CALENDAR, ActionKind.SHARE), kinds(subject(FactType.DATE, "2026-10-15")))
        assertEquals(listOf(ActionKind.CALL, ActionKind.SHARE), kinds(subject(FactType.PHONE, "+919876543210")))
        assertEquals(listOf(ActionKind.MAPS, ActionKind.SHARE), kinds(subject(FactType.ADDRESS, "Road 36, Jubilee Hills, Hyderabad 500033")))
        assertEquals(listOf(ActionKind.SHARE), kinds(subject(FactType.AMOUNT, "1250")))
    }

    @Test fun aDateWithoutATimeBecomesAnAllDayEventInUtc() {
        val p = calendar(subject(FactType.DATE, "2026-10-15", "Due date", raw = "15/10/2026")).payload as ActionPayload.Calendar
        assertTrue(p.allDay)
        assertEquals(ZonedDateTime.of(2026, 10, 15, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), p.startMillis)
        assertEquals(24L * 3600 * 1000, p.endMillis - p.startMillis)
        assertEquals("Electricity Bill: Due date", p.title)
        assertTrue(p.description, p.description.contains("bill.png") && p.description.contains("15/10/2026"))
    }

    @Test fun aDateWithATimeIsAOneHourEventInTheLocalZone() {
        val p = calendar(subject(FactType.DATE, "2026-09-30T18:45", "Appointment", title = "Apollo Clinic")).payload as ActionPayload.Calendar
        assertFalse(p.allDay)
        assertEquals(ZonedDateTime.of(2026, 9, 30, 18, 45, 0, 0, ist).toInstant().toEpochMilli(), p.startMillis)
        assertEquals(3600_000L, p.endMillis - p.startMillis)
    }

    @Test fun theDialogShowsTheExactValueAndWhatWillHappen() {
        val plan = calendar(subject(FactType.DATE, "2026-10-15", "Due date"))
        assertEquals("Title" to "Electricity Bill: Due date", plan.details[0])
        assertEquals("When" to "Thu 15 Oct 2026 (all day)", plan.details[1])
        assertTrue(plan.notes.last().contains("Nothing is saved until you save it"))
        assertEquals("Open calendar", plan.confirmLabel)
    }

    @Test fun aMissingYearUsesTheNextOccurrenceAndSaysSo() {
        val plan = calendar(subject(FactType.DATE, "--11-03T06:45", "Flight", title = "Your flight"), today = "2026-09-25")
        val p = plan.payload as ActionPayload.Calendar
        assertEquals(ZonedDateTime.of(2026, 11, 3, 6, 45, 0, 0, ist).toInstant().toEpochMilli(), p.startMillis)
        assertTrue(plan.notes.first(), plan.notes.first().contains("year was not in the text") && plan.notes.first().contains("2026"))
        // already past this year -> next year
        val later = calendar(subject(FactType.DATE, "--03-01", "Renewal"), today = "2026-09-25").payload as ActionPayload.Calendar
        assertEquals(ZonedDateTime.of(2027, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), later.startMillis)
    }

    @Test fun todayCountsAsUpcomingForAYearlessDate() {
        val p = calendar(subject(FactType.DATE, "--09-25"), today = "2026-09-25").payload as ActionPayload.Calendar
        assertEquals(ZonedDateTime.of(2026, 9, 25, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), p.startMillis)
    }

    @Test fun leapDayWaitsForALeapYear() {
        val p = calendar(subject(FactType.DATE, "--02-29"), today = "2026-09-25").payload as ActionPayload.Calendar
        assertEquals(ZonedDateTime.of(2028, 2, 29, 0, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli(), p.startMillis)
    }

    @Test fun aPastDateIsFlaggedButStillAllowed() {
        val plan = calendar(subject(FactType.DATE, "2026-09-12", "Paid on"), today = "2026-09-25")
        assertTrue(plan.notes.any { it.contains("already passed") })
        assertNotNull(plan.payload)
        assertFalse(calendar(subject(FactType.DATE, "2026-09-25", "Due"), today = "2026-09-25").notes.any { it.contains("already passed") })
    }

    @Test fun anUnreadableDateOffersNoCalendarAction() {
        assertNull(planner().plans(subject(FactType.DATE, "garbage")).firstOrNull { it.kind == ActionKind.CALENDAR })
    }

    @Test fun lowConfidenceIsCalledOutInTheDialog() {
        assertTrue(calendar(subject(FactType.DATE, "2026-10-15", low = true)).notes.any { it.contains("low confidence") })
        val call = planner().plans(subject(FactType.PHONE, "+919876543210", low = true)).first { it.kind == ActionKind.CALL }
        assertTrue(call.notes.first().contains("low confidence"))
    }

    @Test fun titleFallbacksAndLimit() {
        assertEquals("Due date", (calendar(subject(FactType.DATE, "2026-10-15", "Due date:", title = "")).payload as ActionPayload.Calendar).title)
        assertEquals("Electricity Bill", (calendar(subject(FactType.DATE, "2026-10-15", null)).payload as ActionPayload.Calendar).title)
        assertEquals("Date from bill.png", (calendar(subject(FactType.DATE, "2026-10-15", null, title = "")).payload as ActionPayload.Calendar).title)
        assertEquals(100, (calendar(subject(FactType.DATE, "2026-10-15", "x".repeat(300))).payload as ActionPayload.Calendar).title.length)
    }

    @Test fun callShowsAReadableNumberAndPassesTheRawOne() {
        val plan = planner().plans(subject(FactType.PHONE, "+919876543210", "Phone")).first { it.kind == ActionKind.CALL }
        assertEquals("Number" to "+91 98765 43210", plan.details.single())
        assertEquals(ActionPayload.Call("+919876543210"), plan.payload)
        assertTrue(plan.notes.last().contains("You still have to press call"))
    }

    @Test fun mapsShowsAndPassesTheAddress() {
        val address = "Road No 36, Jubilee Hills, Hyderabad 500033"
        val plan = planner().plans(subject(FactType.ADDRESS, address)).first { it.kind == ActionKind.MAPS }
        assertEquals("Address" to address, plan.details.single())
        assertEquals(ActionPayload.Maps(address), plan.payload)
    }

    @Test fun shareTextNamesTheValueItsItemAndItsSource() {
        val plan = planner().plans(subject(FactType.AMOUNT, "45000", "Amount paid", title = "Hostel Fee Receipt")).single()
        val share = plan.payload as ActionPayload.Share
        assertEquals("Hostel Fee Receipt - Amount paid: ₹45,000\nFrom: bill.png (read by Munin from the image; check the original)", share.text)
        assertEquals(plan.details.single().second, share.text) // the dialog shows exactly what will be shared
    }
}

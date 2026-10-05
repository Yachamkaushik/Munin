package com.munin.app.actions

import android.content.Intent
import android.provider.CalendarContract
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.munin.app.extract.FactType

/** The intents handed to the calendar, dialer, maps and share sheet, built from real plans. */
class IntentFactoryTest {
    private val planner = ActionPlanner({ LocalDate.parse("2026-09-25") }, ZoneId.of("Asia/Kolkata"))

    private fun intent(kind: FactType, value: String, action: ActionKind, label: String? = null, raw: String = value): Intent =
        IntentFactory.build(planner.plans(ActionSubject(kind, value, label, raw, "Electricity Bill", "bill.png", false)).first { it.kind == action }.payload)

    @Test fun calendarInsertCarriesTitleDatesAndAllDayFlag() {
        val i = intent(FactType.DATE, "2026-10-15", ActionKind.CALENDAR, "Due date", "15/10/2026")
        assertEquals(Intent.ACTION_INSERT, i.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, i.data)
        assertEquals("Electricity Bill: Due date", i.getStringExtra(CalendarContract.Events.TITLE))
        assertTrue(i.getStringExtra(CalendarContract.Events.DESCRIPTION)!!.contains("bill.png"))
        assertTrue(i.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
        assertEquals(24L * 3600 * 1000, i.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0) - i.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
    }

    @Test fun callUsesDialNeverCall() {
        val i = intent(FactType.PHONE, "+919876543210", ActionKind.CALL)
        assertEquals(Intent.ACTION_DIAL, i.action) // ACTION_CALL would dial without the user pressing anything
        assertEquals("tel:+919876543210", i.data.toString())
    }

    @Test fun mapsEncodesTheAddressInAGeoQuery() {
        val i = intent(FactType.ADDRESS, "Road No 36, Jubilee Hills & Co, Hyderabad 500033", ActionKind.MAPS)
        assertEquals(Intent.ACTION_VIEW, i.action)
        // geo: URIs are opaque, so compare the whole thing: the address is percent-encoded after "q=".
        assertEquals("geo:0,0?q=Road%20No%2036%2C%20Jubilee%20Hills%20%26%20Co%2C%20Hyderabad%20500033", i.data.toString())
    }

    @Test fun shareIsAChooserAroundPlainText() {
        val i = intent(FactType.AMOUNT, "1250", ActionKind.SHARE, "Amount due")
        assertEquals(Intent.ACTION_CHOOSER, i.action)
        @Suppress("DEPRECATION") val inner = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("text/plain", inner.type)
        assertTrue(inner.getStringExtra(Intent.EXTRA_TEXT)!!.startsWith("Electricity Bill - Amount due: ₹1,250"))
    }

    @Test fun nothingDialsOrSavesByItself() {
        for ((kind, value, action) in listOf(Triple(FactType.DATE, "2026-10-15", ActionKind.CALENDAR), Triple(FactType.PHONE, "+919876543210", ActionKind.CALL), Triple(FactType.ADDRESS, "x y", ActionKind.MAPS))) {
            assertFalse(intent(kind, value, action).action, intent(kind, value, action).action == Intent.ACTION_CALL)
        }
    }
}

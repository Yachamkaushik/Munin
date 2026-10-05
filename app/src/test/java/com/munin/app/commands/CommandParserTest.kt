package com.munin.app.commands

import com.munin.app.actions.ActionPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private fun p(q: String) = CommandParser.parse(q)

    @Test fun alarmWithMeridiem() {
        assertEquals(listOf(QuickCommand.Alarm(6, 30)), p("alarm 6:30 am"))
        assertEquals(listOf(QuickCommand.Alarm(18, 0)), p("set alarm for 6pm"))
        assertEquals(listOf(QuickCommand.Alarm(0, 15)), p("alarm 12.15 am"))
        assertEquals(listOf(QuickCommand.Alarm(12, 0)), p("alarm at 12 pm"))
        assertEquals(listOf(QuickCommand.Alarm(5, 0)), p("wake me up at 5 am alarm"))
    }

    @Test fun anAmbiguousTimeOffersBothReadingsInsteadOfGuessing() {
        assertEquals(listOf(QuickCommand.Alarm(7, 0), QuickCommand.Alarm(19, 0)), p("alarm 7"))
        assertEquals(listOf(QuickCommand.Alarm(0, 0)), p("alarm 00:00"))
        assertEquals(listOf(QuickCommand.Alarm(18, 45)), p("alarm 18:45"))
    }

    @Test fun timers() {
        assertEquals(listOf(QuickCommand.Timer(600)), p("timer 10 minutes"))
        assertEquals(listOf(QuickCommand.Timer(600)), p("10 min timer"))
        assertEquals(listOf(QuickCommand.Timer(5400)), p("timer 1 hour 30 min"))
        assertEquals(listOf(QuickCommand.Timer(45)), p("set a timer for 45 seconds"))
        assertEquals(listOf(QuickCommand.Timer(600)), p("timer 10m"))
    }

    @Test fun teluguAndHindiWords() {
        assertEquals(listOf(QuickCommand.Alarm(6, 0)), p("అలారం 6 ఉదయం"))
        assertEquals(listOf(QuickCommand.Alarm(18, 30)), p("अलार्म 6:30 शाम"))
        assertEquals(listOf(QuickCommand.Timer(600)), p("టైమర్ 10 నిమిషాలు"))
        assertEquals(listOf(QuickCommand.Timer(300)), p("टाइमर 5 मिनट"))
    }

    @Test fun ordinarySearchesAndIncompleteCommandsAreNotCommands() {
        for (q in listOf("alarm", "timer", "alarm clock app", "alarm 25:00", "alarm 7:75", "timer 10", "timer 0 minutes", "timer 99 hours",
            "alarm system manual 2023", "timer and alarm", "hostel fee", "", "alarm 13 pm", "alarm 7 am tomorrow")) assertTrue(q, p(q).isEmpty())
    }

    @Test fun plansShowTheTimeAndNeverClaimOcr() {
        val a = ActionPlanner().alarm(QuickCommand.Alarm(18, 5))
        assertEquals("6:05 PM (18:05)", a.details.single().second)
        assertTrue(!a.fromOcr)
        assertEquals("1 h 30 min", ActionPlanner().timer(QuickCommand.Timer(5400)).details.single().second)
    }
}

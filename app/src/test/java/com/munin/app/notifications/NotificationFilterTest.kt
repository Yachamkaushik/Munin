package com.munin.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {
    private fun keep(pkg: String = "com.whatsapp", ongoing: Boolean = false, summary: Boolean = false, category: String? = "msg", title: String = "Ravi", text: String = "Rent due on Friday") =
        NotificationFilter.shouldStore(pkg, "com.munin.app", ongoing, summary, category, title, text)

    @Test fun ordinaryMessagesAreKept() = assertTrue(keep())

    @Test fun ongoingSummaryOwnAndSystemKindsAreSkipped() {
        assertFalse(keep(ongoing = true))
        assertFalse(keep(summary = true))
        assertFalse(keep(pkg = "com.munin.app"))
        for (c in listOf("progress", "service", "transport", "call", "alarm")) assertFalse(c, keep(category = c))
        assertFalse(keep(title = " ", text = ""))
    }

    @Test fun oneTimeCodesAreNeverKept() {
        assertFalse(keep(title = "HDFC Bank", text = "123456 is your OTP for the transaction. Do not share."))
        assertFalse(keep(text = "Your verification code is 4829"))
        assertFalse(keep(text = "Use password 884201 to log in"))
        assertFalse(keep(text = "आपका ओटीपी 552211 है"))
        // a code word alone, or a number alone, is fine
        assertTrue(keep(text = "Please send the OTP when you arrive"))
        assertTrue(keep(text = "Order 12345678901 shipped"))
        assertTrue(keep(text = "Meeting at 5000 Main St"))
    }

    @Test fun textIsTidiedAndCapped() {
        assertEquals("a b c", NotificationFilter.clean("  a \n b\t c "))
        assertEquals("", NotificationFilter.clean(null))
        assertEquals(NotificationFilter.MAX_TEXT_CHARS, NotificationFilter.clean("x".repeat(5000)).length)
    }

    @Test fun termsDropJunkAndCap() {
        assertEquals(listOf("rent", "friday"), NotificationFilter.terms("  rent  a friday rent "))
        assertEquals(NotificationFilter.MAX_TERMS, NotificationFilter.terms("aa bb cc dd ee ff gg").size)
        assertTrue(NotificationFilter.terms("   ").isEmpty())
    }

    @Test fun likeWildcardsAreEscaped() {
        assertEquals("100\\%", NotificationFilter.escapeLike("100%"))
        assertEquals("a\\_b", NotificationFilter.escapeLike("a_b"))
        assertEquals("a\\\\b", NotificationFilter.escapeLike("a\\b"))
    }

    @Test fun retentionCutoffIsThirtyDays() = assertEquals(1_000_000_000L - 30L * 86_400_000L, NotificationFilter.cutoff(1_000_000_000L))
}

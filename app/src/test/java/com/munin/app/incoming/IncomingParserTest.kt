package com.munin.app.incoming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingParserTest {
    private fun p(action: String?, type: String? = "text/plain", pt: String? = null, st: String? = null, uri: String? = null) = IncomingParser.parse(action, type, pt, st, uri)

    @Test fun selectedTextBecomesTheSearch() = assertEquals(Incoming.Text("hostel fee receipt"), p(IncomingParser.ACTION_PROCESS_TEXT, pt = "  hostel \n fee   receipt "))

    @Test fun sharedTextAndSharedImage() {
        assertEquals(Incoming.Text("electricity bill"), p(IncomingParser.ACTION_SEND, st = "electricity bill"))
        assertEquals(Incoming.Image("content://x/1"), p(IncomingParser.ACTION_SEND, type = "image/png", uri = "content://x/1"))
    }

    @Test fun theTileAndWidgetActionOpensSearch() = assertEquals(Incoming.OpenSearch, p(IncomingParser.ACTION_OPEN_SEARCH, type = null))

    @Test fun otherThingsAreIgnored() {
        assertNull(p(IncomingParser.ACTION_SEND, type = "application/pdf", uri = "content://x/2")) // PDFs are not read yet
        assertNull(p(IncomingParser.ACTION_SEND, type = "image/png", uri = null))
        assertNull(p(IncomingParser.ACTION_PROCESS_TEXT, pt = "   "))
        assertNull(p("android.intent.action.VIEW", st = "hello"))
        assertNull(p(null))
    }

    @Test fun veryLongTextIsCutAtAWordBoundary() {
        val long = (1..200).joinToString(" ") { "word$it" }
        val t = (p(IncomingParser.ACTION_PROCESS_TEXT, pt = long) as Incoming.Text).text
        assertTrue(t.length <= IncomingParser.MAX_QUERY_CHARS)
        assertTrue(long.startsWith(t) && !t.endsWith(" "))
        assertTrue(long.substring(t.length).startsWith(" "))
    }

    @Test fun teluguAndHindiTextPassThrough() = assertEquals(Incoming.Text("హాస్టల్ ఫీజు"), p(IncomingParser.ACTION_PROCESS_TEXT, pt = "హాస్టల్  ఫీజు"))
}

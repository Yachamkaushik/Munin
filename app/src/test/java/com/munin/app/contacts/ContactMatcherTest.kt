package com.munin.app.contacts

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ContactMatcherTest {
    private val book = listOf(
        ContactEntry(1, "Amma", "+91 98480 11111"),
        ContactEntry(2, "Ammamma", "+91 98480 22222"),
        ContactEntry(3, "Nanna ❤", "+91 98480 33333"),
        ContactEntry(4, "Ravi Kumar", "+91 98480 44444"),
        ContactEntry(5, "Sneha Reddy", "+91 98480 55555"),
        ContactEntry(6, "Mom", "+91 98480 66666"),
    )
    private fun names(q: String) = ContactMatcher.search(q, book).map { it.name }

    @Test fun nicknameInTeluguHindiAndEnglishFindsTheSameContacts() {
        for (q in listOf("amma", "అమ్మ", "माँ", "mom", "call amma")) assertTrue(q, names(q).containsAll(listOf("Amma", "Mom")))
        for (q in listOf("nanna", "నాన్న", "पापा", "dad")) assertEquals(q, listOf("Nanna ❤"), names(q))
    }

    @Test fun aNicknameDoesNotFindAGrandmotherByPrefix() = assertTrue("Ammamma" !in names("amma"))

    @Test fun ordinaryNamesAreFuzzy() {
        assertEquals(listOf("Ravi Kumar"), names("ravi"))
        assertEquals(listOf("Sneha Reddy"), names("snehaa"))
        assertEquals(listOf("Ravi Kumar"), names("call ravi kumar"))
    }

    @Test fun shortOrLongInputsAndOrdinarySearchesFindNothing() {
        assertTrue(names("ra").isEmpty())
        assertTrue(names("hostel fee payment receipt for september").isEmpty())
        assertTrue(names("12345").isEmpty())
        assertTrue(names("electricity bill").isEmpty())
    }

    @Test fun theSameNumberIsNotListedTwice() {
        val dup = book + ContactEntry(1, "Amma", "9848011111")
        assertEquals(1, ContactMatcher.search("amma", dup).count { it.id == 1L })
    }

    @Test fun nicknameGroupsAreRecognised() {
        assertTrue(ContactMatcher.nicknameGroup("అమ్మ") != null)
        assertEquals(null, ContactMatcher.nicknameGroup("ravi"))
    }
}

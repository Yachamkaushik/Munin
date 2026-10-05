package com.munin.app.search

import androidx.test.core.app.ApplicationProvider
import com.munin.app.data.MuninDatabase
import com.munin.app.data.NotificationEntity
import com.munin.app.notifications.NotificationFilter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The notification table on a real Room/SQLite: search (every word, Telugu and Hindi too), no duplicates, retention. */
class NotificationStoreTest {
    private lateinit var db: MuninDatabase
    private fun n(app: String, title: String, text: String, at: Long) = NotificationEntity(packageName = "pkg.$app", appLabel = app, title = title, text = text, postedAt = at)

    @Before fun open() { db = MuninDatabase.create(ApplicationProvider.getApplicationContext(), name = null) }
    @After fun close() { db.close() }

    @Test fun everyWordMustMatchAndNewestComesFirst() = runBlocking {
        val d = db.notifications()
        d.insert(n("WhatsApp", "Ravi", "Rent due on Friday", 1_000)); d.insert(n("WhatsApp", "Amma", "Rent paid", 2_000)); d.insert(n("Gmail", "Landlord", "rent receipt", 3_000))
        assertEquals(listOf("Landlord", "Amma", "Ravi"), d.search(NotificationFilter.terms("rent")).map { it.title })
        assertEquals(listOf("Ravi"), d.search(NotificationFilter.terms("rent friday")).map { it.title })
        assertEquals(listOf("Landlord"), d.search(NotificationFilter.terms("gmail")).map { it.title }) // app names are searched too
        assertEquals(emptyList<String>(), d.search(NotificationFilter.terms("rent tuesday")).map { it.title })
    }

    @Test fun teluguHindiAndWildcardCharactersSearchLiterally() = runBlocking {
        val d = db.notifications()
        d.insert(n("Messages", "నాన్న", "ఇంటి అద్దె రేపు", 1)); d.insert(n("Messages", "भैया", "किराया कल", 2)); d.insert(n("Bank", "Offer", "Get 100% cashback", 3)); d.insert(n("Bank", "Offer", "Get 1000 cashback", 4))
        assertEquals(listOf("నాన్న"), d.search(NotificationFilter.terms("అద్దె")).map { it.title })
        assertEquals(listOf("भैया"), d.search(NotificationFilter.terms("किराया")).map { it.title })
        assertEquals(1, d.search(NotificationFilter.terms("100%")).size) // % is not a wildcard
    }

    @Test fun theSameNotificationIsStoredOnce() = runBlocking {
        val d = db.notifications()
        d.insert(n("A", "t", "x", 5)); d.insert(n("A", "t", "x", 5)); d.insert(n("A", "t", "x", 6))
        assertEquals(2, d.count())
    }

    @Test fun retentionAndSizeCapDeleteTheOldest() = runBlocking {
        val d = db.notifications()
        for (i in 1..10) d.insert(n("A", "t$i", "x", i * 1000L))
        d.deleteOlderThan(3_500); assertEquals(7, d.count())
        d.trimTo(3); assertEquals(listOf("t10", "t9", "t8"), d.search(listOf("t")).map { it.title })
        d.deleteAll(); assertEquals(0, d.count())
    }
}

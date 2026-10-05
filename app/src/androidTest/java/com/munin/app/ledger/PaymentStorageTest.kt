package com.munin.app.ledger

import androidx.test.core.app.ApplicationProvider
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemKind
import com.munin.app.data.MuninDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** How payment reads are stored, re-extracted and deleted. */
class PaymentStorageTest {
    private lateinit var db: MuninDatabase
    private val receipt = "Paytm UPI\nPaid Successfully\nPaid to\nSunrise Pharmacy\n₹ 1,275.50\n22 Sep 2026, 11:02 AM\nUPI Ref No: 609876543210"

    @Before fun setUp() { db = MuninDatabase.create(ApplicationProvider.getApplicationContext(), name = null) }
    @After fun tearDown() = db.close()

    private suspend fun item(): Long {
        db.items().insertIgnore(ItemEntity(uri = "u", kind = ItemKind.IMAGE, displayName = "n", sizeBytes = 1, modifiedAt = 1, addedAt = 0, width = 1000, height = 1000, rotation = 0))
        return db.items().allExisting().single().id
    }

    @Test fun aPaymentIsStoredInPaiseWithItsFields() = runBlocking {
        val id = item(); db.replaceFacts(id, receipt)
        val p = db.payments().byItem(id)!!
        assertEquals(127550L, p.amountPaise); assertEquals("Sunrise Pharmacy", p.payee); assertEquals("2026-09-22", p.paidDate)
        assertEquals("11:02", p.paidTime); assertEquals("609876543210", p.reference); assertNull(p.problem); assertNull(p.userDecision)
    }

    @Test fun theUsersDecisionSurvivesReExtraction() = runBlocking {
        val id = item(); db.replaceFacts(id, receipt)
        db.payments().setDecision(db.payments().byItem(id)!!.id, "INCLUDE")
        db.replaceFacts(id, receipt, version = 99) // e.g. the rules changed and the item is read again from its saved text
        assertEquals("INCLUDE", db.payments().byItem(id)!!.userDecision)
        assertEquals(1, db.payments().count())
    }

    @Test fun textThatIsNotAPaymentLeavesNoRowAndClearsAnOldOne() = runBlocking {
        val id = item(); db.replaceFacts(id, receipt); assertNotNull(db.payments().byItem(id))
        db.replaceFacts(id, "Electricity Bill\nAmount due: 1,250\nDue date: 15/10/2026")
        assertNull(db.payments().byItem(id))
    }

    @Test fun deletingTheItemDeletesItsPayment() = runBlocking {
        val id = item(); db.replaceFacts(id, receipt)
        db.deleteItems(listOf(id))
        assertEquals(0, db.payments().count())
    }

    @Test fun storedRowsFeedTheLedgerMathExactly() = runBlocking {
        val id = item(); db.replaceFacts(id, receipt)
        val summary = LedgerCalc.summarize(db.payments().allNow().map { it.toRow() })
        assertEquals(127550L, summary.totalPaise) // the ₹ sign was read (confidence 0.9), so the amount is counted
        assertEquals("₹1,275.50", Money.format(summary.totalPaise))
    }
}

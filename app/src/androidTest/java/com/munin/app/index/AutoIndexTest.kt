package com.munin.app.index

import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The instant-indexing watch on a device: off by default, armed on a photo-library trigger when switched on, gone when switched off. */
class AutoIndexTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun states() = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(AutoIndex.WATCH_NAME).get().map { it.state }

    @After fun reset() { AutoIndex.setOn(ctx, false) }

    @Test fun switchingOnArmsAWatchAndOffRemovesIt() {
        AutoIndex.setOn(ctx, false)
        assertFalse(AutoIndex.isOn(ctx))
        AutoIndex.setOn(ctx, true)
        assertTrue(AutoIndex.isOn(ctx))
        assertTrue(states().any { it == WorkInfo.State.ENQUEUED })
        AutoIndex.setOn(ctx, false)
        assertTrue(states().all { it == WorkInfo.State.CANCELLED })
    }

    @Test fun theWatchListensToTheImageLibraryOnly() {
        val triggers = AutoIndex.request().workSpec.constraints.contentUriTriggers.map { it.uri }
        assertEquals(listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), triggers)
    }
}

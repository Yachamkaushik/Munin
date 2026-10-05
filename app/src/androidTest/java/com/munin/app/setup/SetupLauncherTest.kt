package com.munin.app.setup

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/** The launcher on a real system: a screen that does not exist must fall back to Munin's app info page rather than fail. */
class SetupLauncherTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun aMissingVendorScreenFallsBackToAppInfo() {
        val bogus = SetupStep("x", "x", "x", "x", listOf(IntentSpec(pkg = "com.example.nonexistent", cls = "com.example.nonexistent.Nope")), vendorOnly = true)
        assertEquals(SetupLauncher.Result.FELL_BACK, SetupLauncher.open(ctx, bogus))
    }

    @Test fun aRealStandardScreenOpens() {
        val step = BackgroundSetup.steps("google", "google").first { it.id == "app_info" }
        assertEquals(SetupLauncher.Result.OPENED, SetupLauncher.open(ctx, step))
    }
}

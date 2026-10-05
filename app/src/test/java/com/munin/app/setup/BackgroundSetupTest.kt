package com.munin.app.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSetupTest {
    @Test fun vivoAndIqooAreRecognisedByManufacturerOrBrand() {
        assertTrue(BackgroundSetup.isVivoFamily("vivo", "vivo"))
        assertTrue(BackgroundSetup.isVivoFamily("vivo", "iQOO"))
        assertTrue(BackgroundSetup.isVivoFamily("BBK", "IQOO"))
        assertFalse(BackgroundSetup.isVivoFamily("Google", "google"))
        assertFalse(BackgroundSetup.isVivoFamily(null, null))
    }

    @Test fun otherPhonesGetOnlyStandardScreens() {
        val ids = BackgroundSetup.steps("samsung", "samsung").map { it.id }
        assertEquals(listOf("battery", "app_info"), ids)
        assertTrue(BackgroundSetup.steps("samsung", "samsung").none { it.vendorOnly })
        assertNull(BackgroundSetup.recentsTip("samsung", "samsung"))
    }

    @Test fun iqooGetsAutoStartAndPowerScreensToo() {
        val steps = BackgroundSetup.steps("vivo", "iQOO")
        assertEquals(listOf("battery", "autostart", "highpower", "app_info"), steps.map { it.id })
        assertTrue(steps.filter { it.vendorOnly }.all { s -> s.intents.all { it.pkg != null && it.cls != null } })
        assertTrue(BackgroundSetup.recentsTip("vivo", "iQOO") != null)
    }

    @Test fun everyStepHasAWayToOpenAndTheVendorOnesAreNeverTheOnlyOption() {
        for (s in BackgroundSetup.steps("vivo", "vivo")) assertTrue(s.id, s.intents.isNotEmpty())
        // the launcher falls back to the app info page, so a wrong vendor class name can never leave the user stuck
        assertTrue(BackgroundSetup.steps("vivo", "vivo").any { it.id == "app_info" })
    }

    @Test fun standardActionsAreRealSettingsActions() {
        assertEquals("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", BackgroundSetup.ACTION_BATTERY_LIST)
        assertEquals("android.settings.APPLICATION_DETAILS_SETTINGS", BackgroundSetup.ACTION_APP_DETAILS)
    }
}

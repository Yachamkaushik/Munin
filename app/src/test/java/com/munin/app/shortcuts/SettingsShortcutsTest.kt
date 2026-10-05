package com.munin.app.shortcuts

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class SettingsShortcutsTest {
    private fun first(q: String) = SettingsShortcuts.search(q).firstOrNull()?.label

    @Test fun wifiInEverySpellingOpensWifi() {
        for (q in listOf("wifi", "Wi-Fi", "wi fi", "open wifi settings", "వైఫై", "वाईफाई")) assertEquals(q, "Wi-Fi", first(q))
    }

    @Test fun otherScreens() {
        assertEquals("Bluetooth", first("bluetooth"))
        assertEquals("Bluetooth", first("బ్లూటూత్"))
        assertEquals("Battery saver", first("battery"))
        assertEquals("Battery saver", first("बैटरी"))
        assertEquals("Display", first("brightnes"))
    }

    @Test fun ordinarySearchesAreNotHijacked() {
        for (q in listOf("battery bill from september", "wifi password for the router at home", "hostel fee", "ab", "")) assertTrue(q, SettingsShortcuts.search(q).isEmpty())
    }

    @Test fun everyShortcutIsAStandardSettingsAction() = assertTrue(SettingsShortcuts.ALL.all { it.action.startsWith("android.settings.") })
}

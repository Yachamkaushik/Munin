package com.munin.app.shortcuts

import com.munin.app.apps.AppMatcher

/** A system settings screen. [action] is the standard Android settings action string, so this stays testable without Android. */
data class SettingsShortcut(val label: String, val action: String, val names: List<String>)

/**
 * "wifi" opens Wi-Fi settings. Only whole short inputs count ("wifi", "open bluetooth settings", "వైఫై"), so searching for "battery bill" is not hijacked.
 * Opening a settings screen changes nothing by itself, so it needs no confirmation.
 */
object SettingsShortcuts {
    private const val MIN_SCORE = 0.85
    private val NOISE = setOf("open", "go", "to", "settings", "setting", "setup", "turn", "on", "off", "my", "the", "సెట్టింగ్స్", "సెట్టింగ్", "ఓపెన్", "सेटिंग", "सेटिंग्स", "खोलो", "खोलें", "ओपन")

    val ALL = listOf(
        SettingsShortcut("Wi-Fi", "android.settings.WIFI_SETTINGS", listOf("wifi", "wi-fi", "wi fi", "wlan", "వైఫై", "వై-ఫై", "वाईफाई", "वाई-फाई", "वाईफ़ाई")),
        SettingsShortcut("Bluetooth", "android.settings.BLUETOOTH_SETTINGS", listOf("bluetooth", "bt", "బ్లూటూత్", "ब्लूटूथ")),
        SettingsShortcut("Battery saver", "android.settings.BATTERY_SAVER_SETTINGS", listOf("battery", "battery saver", "బ్యాటరీ", "बैटरी")),
        SettingsShortcut("Display", "android.settings.DISPLAY_SETTINGS", listOf("display", "brightness", "screen", "డిస్ప్లే", "బ్రైట్‌నెస్", "डिस्प्ले", "ब्राइटनेस")),
        SettingsShortcut("Sound", "android.settings.SOUND_SETTINGS", listOf("sound", "volume", "ringtone", "సౌండ్", "వాల్యూమ్", "साउंड", "वॉल्यूम")),
        SettingsShortcut("Location", "android.settings.LOCATION_SOURCE_SETTINGS", listOf("location", "gps", "లొకేషన్", "लोकेशन")),
        SettingsShortcut("Airplane mode", "android.settings.AIRPLANE_MODE_SETTINGS", listOf("airplane", "airplane mode", "flight mode", "ఎయిర్‌ప్లేన్", "एयरप्लेन", "फ्लाइट मोड")),
        SettingsShortcut("Mobile data usage", "android.settings.DATA_USAGE_SETTINGS", listOf("data usage", "mobile data", "data", "డేటా", "डेटा")),
        SettingsShortcut("NFC", "android.settings.NFC_SETTINGS", listOf("nfc")),
        SettingsShortcut("Storage", "android.settings.INTERNAL_STORAGE_SETTINGS", listOf("storage", "స్టోరేజ్", "स्टोरेज")),
        SettingsShortcut("Apps", "android.settings.APPLICATION_SETTINGS", listOf("manage apps", "all apps", "app settings")),
        SettingsShortcut("Default apps", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", listOf("default apps")),
        SettingsShortcut("Language", "android.settings.LOCALE_SETTINGS", listOf("language", "languages", "భాష", "भाषा")),
        SettingsShortcut("Date and time", "android.settings.DATE_SETTINGS", listOf("date and time", "date time", "time zone", "timezone")),
        SettingsShortcut("Accessibility", "android.settings.ACCESSIBILITY_SETTINGS", listOf("accessibility")),
        SettingsShortcut("Security", "android.settings.SECURITY_SETTINGS", listOf("security", "సెక్యూరిటీ", "सिक्योरिटी")),
        SettingsShortcut("VPN", "android.settings.VPN_SETTINGS", listOf("vpn")),
        SettingsShortcut("Developer options", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", listOf("developer options", "developer")),
    )

    fun search(query: String): List<SettingsShortcut> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() && it !in NOISE }
        if (words.isEmpty() || words.size > 3 || query.length > 40) return emptyList()
        val q = words.joinToString(" ")
        if (q.length < 2) return emptyList()
        return ALL.map { s -> s to s.names.maxOf { AppMatcher.nameScore(q, it).let { sc -> if (q.length < 4 && sc < 1.0) 0.0 else sc } } }
            .filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }.map { it.first }.take(2)
    }
}

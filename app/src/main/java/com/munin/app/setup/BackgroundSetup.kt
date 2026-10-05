package com.munin.app.setup

/** One screen to try opening: an explicit component ([pkg]/[cls]) or a standard settings [action]. */
data class IntentSpec(val action: String? = null, val pkg: String? = null, val cls: String? = null, val needsPackageUri: Boolean = false)

/**
 * A thing the user can switch so Munin's background work is not held back. [intents] are tried in order until one opens. Munin can read the
 * current state of battery optimisation only; for the others the screen says it cannot see the setting instead of guessing.
 */
data class SetupStep(val id: String, val title: String, val why: String, val button: String, val intents: List<IntentSpec>, val vendorOnly: Boolean = false)

/**
 * Where phone makers hide the switches that stop apps waking up. Standard Android screens are used everywhere; vivo/iQOO (Funtouch OS) screens
 * are listed from what other apps use and have NOT been checked on a real iQOO by Munin's author, so each one falls back to Munin's app info page.
 */
object BackgroundSetup {
    const val ACTION_BATTERY_LIST = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val ACTION_APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    fun isVivoFamily(manufacturer: String?, brand: String?): Boolean =
        listOf(manufacturer, brand).any { v -> v != null && listOf("vivo", "iqoo").any { v.contains(it, ignoreCase = true) } }

    private val appInfo = SetupStep(
        "app_info", "Munin's app info page", "Battery, background activity and permissions for Munin all start here. Choose Battery, then allow background activity or set it to unrestricted if you see that option.",
        "Open app info", listOf(IntentSpec(action = ACTION_APP_DETAILS, needsPackageUri = true)),
    )

    private val batteryList = SetupStep(
        "battery", "Battery optimisation", "Android may hold back Munin's background work to save battery, which delays indexing new screenshots. Find Munin in the list and choose Don't optimise (or Allow).",
        "Open battery optimisation", listOf(IntentSpec(action = ACTION_BATTERY_LIST)),
    )

    private val autoStart = SetupStep(
        "autostart", "Auto-start (Funtouch OS)", "On vivo and iQOO phones an app can be blocked from starting itself. Allow Munin so the system can wake it when a new screenshot appears.",
        "Open auto-start settings",
        listOf(
            IntentSpec(pkg = "com.vivo.permissionmanager", cls = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            IntentSpec(pkg = "com.iqoo.secure", cls = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            IntentSpec(pkg = "com.iqoo.secure", cls = "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
        vendorOnly = true,
    )

    private val highPower = SetupStep(
        "highpower", "Background power use (Funtouch OS)", "Funtouch OS can stop apps it thinks use too much power in the background. Allow Munin to run in the background here.",
        "Open background power settings",
        listOf(
            IntentSpec(pkg = "com.vivo.abe", cls = "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
            IntentSpec(pkg = "com.iqoo.powersaving", cls = "com.iqoo.powersaving.PowerSavingManagerActivity"),
        ),
        vendorOnly = true,
    )

    private val assistant = SetupStep(
        "assistant", "Open Munin with the assist gesture", "Optional. Choose Munin as the phone's digital assistant and the assist gesture (long-press power or home, depending on the phone) opens Munin's search. " +
            "This replaces your current assistant, and Munin is only a search door: it does not listen or answer by voice. You can switch back in the same place.",
        "Choose digital assistant", listOf(IntentSpec(action = "android.settings.VOICE_INPUT_SETTINGS"), IntentSpec(action = "android.settings.MANAGE_DEFAULT_APPS_SETTINGS")),
    )

    /** What to show for this phone: standard steps always, vendor steps only on vivo/iQOO. */
    fun steps(manufacturer: String?, brand: String?): List<SetupStep> {
        val vendor = isVivoFamily(manufacturer, brand)
        return buildList {
            add(batteryList)
            add(assistant)
            if (vendor) { add(autoStart); add(highPower) }
            add(appInfo)
        }
    }

    /** Text tip that no screen can do for the user: keeping Munin from being cleared with the other recent apps. */
    fun recentsTip(manufacturer: String?, brand: String?): String? =
        if (isVivoFamily(manufacturer, brand)) "In the recent-apps view, pull Munin's card down (or use its lock option) so \"clear all\" does not close it. Menus differ between Funtouch OS versions, so look for a lock icon on the card." else null
}

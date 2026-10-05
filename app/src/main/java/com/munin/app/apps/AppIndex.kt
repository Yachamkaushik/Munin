package com.munin.app.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The apps that have a launcher icon, read from the system's package manager (no permission: Android 11+ needs the narrow `<queries>`
 * entry for the launcher intent in the manifest, not the broad all-apps permission). Nothing here leaves the phone.
 */
class AppIndex(private val context: Context) {
    @Volatile private var apps: List<AppEntry> = emptyList()
    @Volatile private var loadedAt = 0L

    /** Reloads if the list is older than [maxAgeMs]; call off the main thread (it reads every app's label). */
    fun refresh(maxAgeMs: Long = 5 * 60_000L) {
        if (apps.isNotEmpty() && System.currentTimeMillis() - loadedAt < maxAgeMs) return
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(launcher, 0).mapNotNull { r ->
            val ai = r.activityInfo ?: return@mapNotNull null
            if (ai.packageName == context.packageName) return@mapNotNull null // Munin does not offer itself
            AppEntry(r.loadLabel(pm).toString(), ai.packageName, ComponentName(ai.packageName, ai.name).flattenToString())
        }.distinctBy { it.component }
        loadedAt = System.currentTimeMillis()
    }

    val count get() = apps.size
    fun all(): List<AppEntry> = apps
    fun search(query: String): List<AppMatch> = AppMatcher.search(query, apps)

    /** Starts the app. Returns false if it could not be started (for example it was uninstalled a moment ago). */
    fun launch(app: AppEntry): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName.unflattenFromString(app.component))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }.isSuccess
}

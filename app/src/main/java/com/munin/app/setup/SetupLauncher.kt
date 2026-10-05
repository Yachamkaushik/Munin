package com.munin.app.setup

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Opens a [SetupStep]'s screen, trying each candidate in turn and falling back to Munin's own app info page. */
object SetupLauncher {
    enum class Result { OPENED, FELL_BACK, FAILED }

    private fun toIntent(context: Context, s: IntentSpec): Intent {
        val i = if (s.action != null) Intent(s.action) else Intent().setComponent(ComponentName(s.pkg!!, s.cls!!))
        if (s.needsPackageUri) i.data = Uri.fromParts("package", context.packageName, null)
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun tryStart(context: Context, i: Intent) = try { context.startActivity(i); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    fun open(context: Context, step: SetupStep): Result {
        for (spec in step.intents) if (tryStart(context, toIntent(context, spec))) return Result.OPENED
        val info = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (tryStart(context, info)) Result.FELL_BACK else Result.FAILED
    }

    /** True when Android is not restricting Munin for battery. The only setting here that Munin is allowed to read. */
    fun ignoringBatteryOptimisations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
}

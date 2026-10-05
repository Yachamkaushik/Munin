package com.munin.app.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.munin.app.setup.BackgroundSetup
import com.munin.app.setup.SetupLauncher
import com.munin.app.ui.theme.BackBar
import com.munin.app.ui.theme.Footnote
import com.munin.app.ui.theme.GroupDivider
import com.munin.app.ui.theme.IosSwitch
import com.munin.app.ui.theme.LargeTitle
import com.munin.app.ui.theme.ListRow
import com.munin.app.ui.theme.Munin
import com.munin.app.ui.theme.Section

/** The optional edge handle. Needs "Display over other apps"; shows a notification while on; off until switched on. */
@Composable
private fun EdgeHandleSection(refresh: Int) {
    val context = LocalContext.current
    var on by remember(refresh) { mutableStateOf(com.munin.app.edge.EdgeHandle.wanted(context) && com.munin.app.edge.EdgeHandle.canDraw(context)) }
    val canDraw = remember(refresh, on) { com.munin.app.edge.EdgeHandle.canDraw(context) }
    Section(
        "Edge handle (optional)",
        footer = "A thin bar on the screen edge, over other apps. Tap it to open Munin's search; drag it up or down to move it. While it is on, Munin shows a notification with a Turn off button. " +
            "It does nothing in the background otherwise. It needs the phone's \"Display over other apps\" permission.",
    ) {
        ListRow(
            "Show the edge handle", subtitle = if (canDraw) "Display over other apps: allowed." else "Display over other apps: not allowed yet.",
            trailing = {
                IosSwitch(on, { want ->
                    com.munin.app.edge.EdgeHandle.setWanted(context, want)
                    if (want && !com.munin.app.edge.EdgeHandle.canDraw(context)) {
                        // Ask the phone's settings; the handle starts when the user comes back with the permission allowed.
                        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.fromParts("package", context.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                    on = want && com.munin.app.edge.EdgeHandle.canDraw(context)
                    com.munin.app.edge.EdgeHandle.sync(context)
                })
            },
        )
    }
}

@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ } // coming back from a settings screen
    val steps = remember { BackgroundSetup.steps(Build.MANUFACTURER, Build.BRAND) }
    val tip = remember { BackgroundSetup.recentsTip(Build.MANUFACTURER, Build.BRAND) }
    val isAssistant = remember(refresh) { com.munin.app.assist.AssistRole.isHeld(context) }
    val unrestricted = remember(refresh) { SetupLauncher.ignoringBatteryOptimisations(context) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column {
                BackBar("Index", onBack)
                LargeTitle("Background setup")
                Footnote(
                    "Instant indexing relies on the phone waking Munin when a new screenshot appears. Phones often block that to save battery. These buttons open your phone's own settings; " +
                        "Munin cannot change them for you." +
                        if (BackgroundSetup.isVivoFamily(Build.MANUFACTURER, Build.BRAND)) " This looks like a vivo or iQOO phone, so the Funtouch OS screens are listed too. They have not been checked on a real iQOO: if a button cannot open the exact screen, Munin opens its app info page instead." else "",
                )
            }
        }
        for (step in steps) item(key = step.id) {
            val status = when (step.id) {
                "battery" -> if (unrestricted) "Now: not restricted for battery." else "Now: Android may restrict Munin to save battery."
                "assistant" -> if (isAssistant) "Now: Munin is your digital assistant." else "Now: Munin is not your digital assistant."
                else -> "Munin cannot see this setting. Check it yourself."
            }
            Section(step.title, footer = step.why) {
                ListRow(status, titleMaxLines = 3)
                GroupDivider()
                ListRow(step.button, titleColor = Munin.colors.tint, chevron = true, onClick = {
                    when (SetupLauncher.open(context, step)) {
                        SetupLauncher.Result.OPENED -> Unit
                        SetupLauncher.Result.FELL_BACK -> Toast.makeText(context, "That screen is not on this phone. Opened Munin's app info instead.", Toast.LENGTH_LONG).show()
                        SetupLauncher.Result.FAILED -> Toast.makeText(context, "Could not open any settings screen for this.", Toast.LENGTH_LONG).show()
                    }
                })
            }
        }
        item { EdgeHandleSection(refresh) }
        tip?.let { item { Section("Keep Munin from being cleared") { ListRow(it, titleMaxLines = 8) } } }
        item { androidx.compose.foundation.layout.Box(Modifier.padding(bottom = 12.dp)) }
    }
}

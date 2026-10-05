package com.munin.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munin.app.data.ItemStatus
import com.munin.app.data.RecentItem
import com.munin.app.index.MediaAccess
import com.munin.app.index.MediaAccessState
import com.munin.app.ui.theme.ButtonStyle
import com.munin.app.ui.theme.Footnote
import com.munin.app.ui.theme.GroupDivider
import com.munin.app.ui.theme.IosButton
import com.munin.app.ui.theme.IosCard
import com.munin.app.ui.theme.IosSwitch
import com.munin.app.ui.theme.LargeTitle
import com.munin.app.ui.theme.ListRow
import com.munin.app.ui.theme.Munin
import com.munin.app.ui.theme.ProgressBar
import com.munin.app.ui.theme.Section
import kotlinx.coroutines.launch

@Composable
fun IndexScreen(vm: IndexViewModel = viewModel(), onOpenSetup: () -> Unit = {}) {
    val ui by vm.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshAccess()
        vm.startIndexing()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }
    // Resume: anything still pending (from an earlier run, or after a restart) is picked up automatically.
    LaunchedEffect(ui.access) { if (ui.access != MediaAccessState.NONE && ui.pending > 0) vm.startIndexing() }

    fun requestAccess() {
        val perms = MediaAccess.permissionsToRequest().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { LargeTitle("Index", "Everything stays on this phone. The app has no internet permission.") }
        if (ui.access != MediaAccessState.FULL) item { AccessCard(ui.access, onGrant = ::requestAccess) }
        item { ProgressCard(ui, onStart = { if (ui.access == MediaAccessState.NONE) requestAccess() else vm.startIndexing() }, onClear = vm::clearIndex) }
        item { AutoSection(ui.autoOn, enabled = ui.access != MediaAccessState.NONE, onChange = vm::setAuto, onOpenSetup = onOpenSetup) }
        item { NotificationHistorySection() }
        item { TeluguSection(ui.teluguOn, vm::setTelugu) }
        item {
            Footnote(
                "Reads English and Hindi text in images. Telugu text inside images is only read if you switch on the experimental option above " +
                    "(searching in Telugu works against English and Hindi text either way). PDFs are not indexed yet.",
                Modifier.padding(horizontal = 16.dp),
            )
        }
        if (ui.recent.isNotEmpty()) item {
            Section("Recently processed") { ui.recent.forEachIndexed { i, it -> if (i > 0) GroupDivider(84.dp); RecentRow(it) } }
        }
    }
}

@Composable
private fun AutoSection(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, onOpenSetup: () -> Unit) {
    Section(
        "Automatic",
        footer = if (enabled) "When a new image appears, Munin reads it a few seconds later. Nothing runs in the background while nothing changes: the system wakes Munin only when " +
            "your photo library changes. The phone's own battery settings may delay it (see the phone's battery optimisation for Munin)."
        else "Allow photo access first.",
    ) {
        ListRow("Index new photos and screenshots", trailing = { IosSwitch(on, onChange, enabled) })
        if (enabled) { GroupDivider(); ListRow("Background setup", subtitle = "Help it run on time", chevron = true, onClick = onOpenSetup) }
    }
}

/** Optional notification history. Two switches must both be on (the phone's Notification access and this one); either can be turned off at any time. */
@Composable
private fun NotificationHistorySection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = (context.applicationContext as com.munin.app.MuninApp).database
    var on by remember { mutableStateOf(com.munin.app.notifications.NotificationSettings.enabled(context)) }
    var access by remember { mutableStateOf(com.munin.app.notifications.NotificationSettings.accessGranted(context)) }
    var saved by remember { mutableIntStateOf(0) }
    fun reload() { access = com.munin.app.notifications.NotificationSettings.accessGranted(context); scope.launch { saved = db.notifications().count() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload() }
    val status = when {
        !on -> "Off. Nothing is being saved."
        !access -> "Waiting for Notification access. Allow it below, or nothing will be saved."
        else -> "On. $saved saved."
    }
    Section(
        "Notifications",
        footer = status + " Keeps the title and text of notifications that arrive from now on, on this phone only, for ${com.munin.app.notifications.NotificationFilter.RETENTION_DAYS} days, so you can search them. " +
            "Ongoing notifications and messages that look like one-time codes are skipped; other private messages are kept, so only switch this on if you are comfortable with that. " +
            "Needs the phone's Notification access for Munin.",
    ) {
        ListRow("Search my notifications", subtitle = "Optional", trailing = { IosSwitch(on, { want -> com.munin.app.notifications.NotificationSettings.setEnabled(context, want); on = want; reload() }) })
        if (!access) {
            GroupDivider()
            ListRow("Open notification access", titleColor = Munin.colors.tint, chevron = true, onClick = {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
        if (saved > 0) {
            GroupDivider()
            ListRow("Delete saved notifications", titleColor = Munin.colors.red, onClick = { scope.launch { db.notifications().deleteAll(); reload() } })
        }
    }
}

@Composable
private fun TeluguSection(on: Boolean, onChange: (Boolean) -> Unit) {
    Section(
        "Language",
        footer = "Uses a second, slower reader (Tesseract) when the first one is unsure. It is far less accurate than English or Hindi reading and can " +
            "get words wrong. It applies to photos indexed from now on: choose Clear index and scan again to re-read existing ones.",
    ) {
        ListRow("Read Telugu text in images", subtitle = "Experimental", trailing = { IosSwitch(on, onChange) })
    }
}

@Composable
private fun AccessCard(access: MediaAccessState, onGrant: () -> Unit) {
    when (access) {
        MediaAccessState.FULL -> Unit
        MediaAccessState.PARTIAL -> IosCard {
            Text("Partial photo access", style = MaterialTheme.typography.titleMedium, color = Munin.colors.label)
            Footnote("You shared only some photos, so Munin can only index those. Search will not find anything else.")
            IosButton("Choose more photos", onGrant)
        }
        MediaAccessState.NONE -> IosCard {
            Text("Allow photo access", style = MaterialTheme.typography.titleMedium, color = Munin.colors.label)
            Footnote("Munin reads the text in your screenshots and photos on this phone to make them searchable. Nothing leaves the device.")
            IosButton("Allow access to photos", onGrant, style = ButtonStyle.Filled)
        }
    }
}

@Composable
private fun ProgressCard(ui: IndexUiState, onStart: () -> Unit, onClear: () -> Unit) {
    IosCard {
        if (ui.total > 0) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${ui.done} of ${ui.total}", style = MaterialTheme.typography.headlineSmall, color = Munin.colors.label)
                Text(if (ui.running) "Indexing…" else "Up to date", style = MaterialTheme.typography.bodyMedium, color = Munin.colors.secondaryLabel)
            }
            ProgressBar(ui.done / ui.total.toFloat())
            if (ui.running && ui.nextName != null) Footnote("Now: ${ui.nextName}")
            Footnote("${ui.indexed} indexed · ${ui.noText} no text found · ${ui.duplicates} duplicates · ${ui.failed} failed")
            if (ui.medianOcrMs != null) Footnote("Median per item: text recognition ${ui.medianOcrMs} ms, embedding ${ui.medianEmbedMs ?: 0} ms")
        } else {
            Text("Nothing indexed yet.", style = MaterialTheme.typography.titleMedium, color = Munin.colors.label)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IosButton(if (ui.running) "Indexing…" else if (ui.total == 0) "Scan and index" else "Scan for new photos", onStart, style = ButtonStyle.Filled, enabled = !ui.running)
            if (ui.total > 0) IosButton("Clear index", onClear, enabled = !ui.running, destructive = true)
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Thumbnail(item.uri, 56, Modifier.clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.label)
            val timing = if (item.ocrMs != null) " · OCR ${item.ocrMs} ms" else ""
            Text(
                when (item.status) {
                    ItemStatus.INDEXED -> "Indexed$timing"
                    ItemStatus.NO_TEXT -> "No text found$timing"
                    ItemStatus.DUPLICATE -> "Duplicate of an indexed item"
                    else -> "Failed: ${item.error ?: "unknown error"}"
                },
                style = MaterialTheme.typography.bodySmall, color = if (item.status == ItemStatus.FAILED) Munin.colors.red else Munin.colors.secondaryLabel, fontWeight = FontWeight.Medium,
            )
            item.snippet?.let { Text(it.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel) }
        }
    }
}

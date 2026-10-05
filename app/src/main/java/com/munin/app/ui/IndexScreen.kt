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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munin.app.data.ItemStatus
import com.munin.app.data.RecentItem
import com.munin.app.index.MediaAccess
import com.munin.app.index.MediaAccessState

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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Munin", style = MaterialTheme.typography.headlineMedium)
                Text("Everything stays on this phone. The app has no internet permission.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { AccessCard(ui.access, onGrant = ::requestAccess) }
        item { ProgressCard(ui, onStart = { if (ui.access == MediaAccessState.NONE) requestAccess() else vm.startIndexing() }, onClear = vm::clearIndex) }
        item { AutoCard(ui.autoOn, enabled = ui.access != MediaAccessState.NONE, onChange = vm::setAuto, onOpenSetup = onOpenSetup) }
        item { NotificationHistoryCard() }
        item { TeluguCard(ui.teluguOn, vm::setTelugu) }
        item {
            Text(
                "Reads English and Hindi text in images. Telugu text inside images is only read if you switch on the experimental option above " +
                    "(searching in Telugu works against English and Hindi text either way). PDFs are not indexed yet.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ui.recent.isNotEmpty()) item { Text("Recently processed", style = MaterialTheme.typography.titleMedium) }
        items(ui.recent, key = { it.id }) { RecentRow(it) }
    }
}

@Composable
private fun AutoCard(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, onOpenSetup: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Index new photos and screenshots automatically", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (enabled) "When a new image appears, Munin reads it a few seconds later. Nothing runs in the background while nothing changes: the system wakes Munin only when " +
                        "your photo library changes. The phone's own battery settings may delay it (see the phone's battery optimisation for Munin)."
                    else "Allow photo access first.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (enabled) androidx.compose.material3.TextButton(onClick = onOpenSetup) { Text("Background setup: help it run on time") }
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = onChange, enabled = enabled)
        }
    }
}

/** Optional notification history. Two switches must both be on (the phone's Notification access and this one); either can be turned off at any time. */
@Composable
private fun NotificationHistoryCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val db = (context.applicationContext as com.munin.app.MuninApp).database
    var on by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.munin.app.notifications.NotificationSettings.enabled(context)) }
    var access by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(com.munin.app.notifications.NotificationSettings.accessGranted(context)) }
    var saved by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    fun reload() { access = com.munin.app.notifications.NotificationSettings.accessGranted(context); scope.launch { saved = db.notifications().count() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Search my notifications (optional)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Keeps the title and text of notifications that arrive from now on, on this phone only, for ${com.munin.app.notifications.NotificationFilter.RETENTION_DAYS} days, so you can search them. " +
                            "Ongoing notifications and messages that look like one-time codes are skipped; other private messages are kept, so only switch this on if you are comfortable with that. " +
                            "Needs the phone's Notification access for Munin.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                androidx.compose.material3.Switch(checked = on, onCheckedChange = { want ->
                    com.munin.app.notifications.NotificationSettings.setEnabled(context, want); on = want; reload()
                })
            }
            Text(
                when {
                    !on -> "Off. Nothing is being saved."
                    !access -> "Waiting for Notification access. Allow it below, or nothing will be saved."
                    else -> "On. $saved saved."
                },
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!access) OutlinedButton(onClick = {
                    runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("Open notification access") }
                if (saved > 0) OutlinedButton(onClick = { scope.launch { db.notifications().deleteAll(); reload() } }) { Text("Delete saved notifications") }
            }
        }
    }
}

@Composable
private fun TeluguCard(on: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Read Telugu text in images (experimental)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Uses a second, slower reader (Tesseract) when the first one is unsure. It is far less accurate than English or Hindi reading and can " +
                        "get words wrong. It applies to photos indexed from now on: choose Clear index and scan again to re-read existing ones.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            androidx.compose.material3.Switch(checked = on, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun AccessCard(access: MediaAccessState, onGrant: () -> Unit) {
    when (access) {
        MediaAccessState.FULL -> Unit
        MediaAccessState.PARTIAL -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Partial photo access", style = MaterialTheme.typography.titleSmall)
                Text("You shared only some photos, so Munin can only index those. Search will not find anything else.")
                OutlinedButton(onClick = onGrant) { Text("Choose more photos") }
            }
        }
        MediaAccessState.NONE -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Allow photo access", style = MaterialTheme.typography.titleSmall)
                Text("Munin reads the text in your screenshots and photos on this phone to make them searchable. Nothing leaves the device.")
                Button(onClick = onGrant) { Text("Allow access to photos") }
            }
        }
    }
}

@Composable
private fun ProgressCard(ui: IndexUiState, onStart: () -> Unit, onClear: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Index", style = MaterialTheme.typography.titleSmall)
            if (ui.total > 0) {
                LinearProgressIndicator(progress = { ui.done / ui.total.toFloat() }, Modifier.fillMaxWidth())
                Text("${ui.done} of ${ui.total} processed" + if (ui.running && ui.nextName != null) " · now: ${ui.nextName}" else "")
                Text(
                    "${ui.indexed} indexed · ${ui.noText} no text found · ${ui.duplicates} duplicates · ${ui.failed} failed",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ui.medianOcrMs != null) {
                    Text("Median per item: text recognition ${ui.medianOcrMs} ms, embedding ${ui.medianEmbedMs ?: 0} ms", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Nothing indexed yet.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !ui.running) { Text(if (ui.running) "Indexing…" else if (ui.total == 0) "Scan and index" else "Scan for new photos") }
                if (ui.total > 0) OutlinedButton(onClick = onClear, enabled = !ui.running) { Text("Clear index") }
            }
        }
    }
}

@Composable
private fun RecentRow(item: RecentItem) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Thumbnail(item.uri, 56)
        Column(Modifier.weight(1f)) {
            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            val timing = if (item.ocrMs != null) " · OCR ${item.ocrMs} ms" else ""
            Text(
                when (item.status) {
                    ItemStatus.INDEXED -> "Indexed$timing"
                    ItemStatus.NO_TEXT -> "No text found$timing"
                    ItemStatus.DUPLICATE -> "Duplicate of an indexed item"
                    else -> "Failed: ${item.error ?: "unknown error"}"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            item.snippet?.let { Text(it.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

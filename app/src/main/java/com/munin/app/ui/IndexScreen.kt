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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
fun IndexScreen(vm: IndexViewModel = viewModel()) {
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
        item {
            Text(
                "Reads English and Hindi text in images. Telugu text inside images is not recognized yet (searching in Telugu will " +
                    "still work against English and Hindi text). PDFs are not indexed yet.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (ui.recent.isNotEmpty()) item { Text("Recently processed", style = MaterialTheme.typography.titleMedium) }
        items(ui.recent, key = { it.id }) { RecentRow(it) }
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

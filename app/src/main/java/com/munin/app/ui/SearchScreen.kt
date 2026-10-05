package com.munin.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munin.app.search.SearchMode
import com.munin.app.search.SearchResult
import com.munin.app.search.SearchTimings
import com.munin.app.search.Snippet

@Composable
fun SearchScreen(vm: SearchViewModel = viewModel(), indexVersion: Int = 0) {
    val ui by vm.state.collectAsState()
    LaunchedEffect(indexVersion) { vm.refresh() }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Munin", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
        OutlinedTextField(
            value = ui.query, onValueChange = vm::onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Describe what you are looking for") },
            placeholder = { Text("Telugu, Hindi, English or a mix") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((mode, label) in listOf(SearchMode.MERGED to "Merged", SearchMode.MEANING to "Meaning only", SearchMode.KEYWORDS to "Keywords only")) {
                FilterChip(selected = ui.mode == mode, onClick = { vm.onMode(mode) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = ui.showDebug, onCheckedChange = vm::onDebug)
            Text("Debug details", style = MaterialTheme.typography.bodySmall)
        }
        val r = ui.response
        if (ui.showDebug && r != null) Text(debugLine(r.mode, r.timings), style = MaterialTheme.typography.labelSmall)

        when {
            ui.error != null -> Text(ui.error!!, color = MaterialTheme.colorScheme.error)
            !ui.modelReady -> Text("Loading the on-device language model…")
            ui.indexedChunks == 0 -> Text("Nothing is indexed yet. Open the Index tab and scan your photos first.")
            ui.query.isBlank() -> Text("Search by what the text says, not by file name.", style = MaterialTheme.typography.bodyMedium)
            r != null && r.results.isEmpty() -> Text("No matches for “${r.query}”.")
            r != null -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(r.results, key = { it.itemId }) { ResultRow(it, ui.showDebug) }
            }
        }
    }
}

private fun debugLine(mode: SearchMode, t: SearchTimings) =
    "${mode.name.lowercase()} · ${t.chunksSearched} vectors · embed %.0f ms · meaning %.0f ms · keywords %.0f ms · fuse %.1f ms · total %.0f ms"
        .format(t.embedMs, t.meaningMs, t.keywordMs, t.fuseMs, t.totalMs)

@Composable
private fun ResultRow(r: SearchResult, debug: Boolean) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable {
            // Opens the file in the gallery; the app never copies or edits it.
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(r.uri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { context.startActivity(view) }
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumbnail(r.uri, 88)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.displayName, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(highlighted(r.snippet), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (debug) {
                val meaning = r.meaningRank?.let { "meaning #$it (%.2f)".format(r.meaningScore) } ?: "meaning –"
                val keywords = r.keywordRank?.let { "keywords #$it (%.1f)".format(r.keywordScore) } ?: "keywords –"
                Text("$meaning · $keywords", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun highlighted(s: Snippet): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (h in s.highlights) {
        if (h.first > at) append(s.text.substring(at, h.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text.substring(h.first, h.last + 1)) }
        at = h.last + 1
    }
    if (at < s.text.length) append(s.text.substring(at))
}

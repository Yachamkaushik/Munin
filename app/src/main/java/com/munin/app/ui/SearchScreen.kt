package com.munin.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munin.app.actions.toSubject
import com.munin.app.answer.Answer
import com.munin.app.answer.AnswerOutcome
import com.munin.app.search.SearchMode
import com.munin.app.search.SearchResult
import com.munin.app.search.SearchTimings
import com.munin.app.search.Snippet

@Composable
fun SearchScreen(onOpenItem: (Long) -> Unit, onOpenLedger: (java.time.YearMonth?) -> Unit, vm: SearchViewModel = viewModel(), indexVersion: Int = 0) {
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
                ui.spending?.let { sp -> item { SpendingCard(sp, onOpenLedger) } }
                when (val a = ui.answer) {
                    is AnswerOutcome.Found -> item { AnswerCard(a.answer, onOpenItem) }
                    // It was a question but we will not guess: say so, then fall back to the list.
                    is AnswerOutcome.Declined -> item {
                        Text(
                            "No direct answer: ${a.reason}. Showing matching items instead.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Unit
                }
                items(r.results, key = { it.itemId }) { ResultRow(it, ui.showDebug, onOpenItem) }
            }
        }
    }
}

@Composable
private fun SpendingCard(sp: SpendingAnswer, onOpenLedger: (java.time.YearMonth?) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sp.count == 0) {
                Text("No payment screenshots read ${sp.scope}.", style = MaterialTheme.typography.titleMedium)
            } else {
                Text(sp.display, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Spent ${sp.scope}, from ${sp.count} payment screenshot${if (sp.count == 1) "" else "s"}.", style = MaterialTheme.typography.bodyMedium)
            }
            Text("This is spending from your screenshots, not your total spending.", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            if (sp.needsCheck > 0) Text("${sp.needsCheck} more payment${if (sp.needsCheck == 1) " has" else "s have"} an amount that was not clearly read and ${if (sp.needsCheck == 1) "is" else "are"} not included.", style = MaterialTheme.typography.labelSmall)
            if (sp.unreadable > 0) Text("${sp.unreadable} payment screenshot${if (sp.unreadable == 1) "" else "s"} could not be read and ${if (sp.unreadable == 1) "is" else "are"} not included.", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onOpenLedger(sp.month) }) { Text("Open ledger") }
        }
    }
}

@Composable
private fun AnswerCard(a: Answer, onOpenItem: (Long) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onOpenItem(a.source.itemId) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.display, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(a.label, a.raw.replace('\n', ' ').takeIf { it.isNotBlank() && it != a.display && it != a.display.replace(", ", " ") }).joinToString(": ").ifEmpty { a.kind.name.lowercase() },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("From ${a.source.displayName}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    a.caveat?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium) }
                    if (a.alternatives.isNotEmpty()) {
                        Text(
                            "Also in this item: " + a.alternatives.joinToString(", ") { alt -> alt.label?.let { "${alt.display} ($it)" } ?: alt.display },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Thumbnail(a.source.uri, 72)
            }
            ActionButtons(a.toSubject())
            Text("Read from the image by OCR, so check it against the source (tap the card to see it).", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun debugLine(mode: SearchMode, t: SearchTimings) =
    "${mode.name.lowercase()} · ${t.chunksSearched} vectors · embed %.0f ms · meaning %.0f ms · keywords %.0f ms · fuse %.1f ms · total %.0f ms"
        .format(t.embedMs, t.meaningMs, t.keywordMs, t.fuseMs, t.totalMs)

@Composable
private fun ResultRow(r: SearchResult, debug: Boolean, onOpen: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(r.itemId) },
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

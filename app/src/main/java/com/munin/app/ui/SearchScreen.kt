package com.munin.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.munin.app.actions.ActionPlan
import com.munin.app.calc.CalcOutcome
import com.munin.app.actions.ActionPlanner
import com.munin.app.actions.toSubject
import com.munin.app.answer.Answer
import com.munin.app.answer.AnswerOutcome
import com.munin.app.search.SearchMode
import com.munin.app.voice.PackStatuses
import com.munin.app.voice.VoiceLang
import com.munin.app.voice.VoiceState
import com.munin.app.search.SearchResult
import com.munin.app.search.SearchTimings
import com.munin.app.search.Snippet
import com.munin.app.search.WhyThis

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
        // What the router made of the input, in plain words, so a surprising result is never a mystery.
        ui.understood?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        VoiceBar(ui, vm)
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

        if (ui.query.isBlank()) {
            Text("Search by what the text says, not by file name.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val calc = ui.calc
                when {
                    calc != null -> {
                        item { GroupHeader("Calculator") }
                        item { CalculatorCard(calc, vm::saveRate) }
                        item { TextButton(onClick = vm::searchFilesInstead) { Text("Search my files for “${ui.query.trim()}” instead") } }
                    }
                    ui.calcNote != null -> item { Text(ui.calcNote!!, style = MaterialTheme.typography.bodyMedium) }
                    ui.error != null -> item { Text(ui.error!!, color = MaterialTheme.colorScheme.error) }
                    !ui.modelReady -> item { Text("Loading the on-device language model…") }
                    ui.indexedChunks == 0 -> item { Text("Nothing is indexed yet. Open the Index tab and scan your photos first.") }
                    r != null -> {
                        val spending = ui.spending
                        val answer = ui.answer
                        if (spending != null || answer is AnswerOutcome.Found || answer is AnswerOutcome.Declined) item { GroupHeader("Answer") }
                        spending?.let { sp -> item { SpendingCard(sp, onOpenLedger) } }
                        when (answer) {
                            is AnswerOutcome.Found -> item { AnswerCard(answer.answer, onOpenItem) }
                            // It was a question but we will not guess: say so, then fall back to the list.
                            is AnswerOutcome.Declined -> item {
                                Text(
                                    "No direct answer: ${answer.reason}. Showing matching files instead.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> Unit
                        }
                        item { GroupHeader(if (r.results.isEmpty()) "Files" else "Files (${r.results.size})") }
                        if (r.results.isEmpty()) item { Text("No matches for “${r.query}” in your files.") }
                        items(r.results, key = { it.itemId }) { ResultRow(it, ui.showDebug, onOpenItem) }
                    }
                    else -> Unit
                }
                // The only thing in the app that leaves the phone, so it sits apart and says so.
                item { GroupHeader("Web") }
                item { WebSearchRow(ui.query) }
            }
        }
    }
}

@Composable
private fun CalculatorCard(c: CalcOutcome, onSaveRate: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (c) {
                is CalcOutcome.Value -> {
                    Text(c.primary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    for (line in c.secondary) Text(line, style = MaterialTheme.typography.bodyMedium)
                    Text(c.reading, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { clipboard.setText(AnnotatedString(c.copyText)) }) { Text("Copy result") }
                }
                is CalcOutcome.Failed -> Text(c.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                is CalcOutcome.RateProposal -> {
                    Text("1 ${c.from} = ${com.munin.app.calc.Numbers.format(c.rate)} ${c.to}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Munin cannot check this rate. If you save it, it is used only for your own conversions, and each result shows how old it is.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onSaveRate) { Text("Save this rate") }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

/** A clearly labelled hand-off to the browser. Tapping it asks first and shows the exact text that would leave the phone. */
@Composable
private fun WebSearchRow(query: String) {
    var plan by remember { mutableStateOf<ActionPlan?>(null) }
    Card(Modifier.fillMaxWidth().clickable { plan = ActionPlanner().webSearch(query.trim()) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Search the web for “${query.trim()}”", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("Leaves your phone: opens your browser.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
    plan?.let { ActionConfirmDialog(it) { plan = null } }
}

/**
 * Speak button, language choice and an honest status line. Voice goes through the phone's speech service, so the line says
 * whether the offline pack is installed rather than promising privacy it cannot guarantee. Typing is always fully offline.
 */
@Composable
private fun VoiceBar(ui: SearchUiState, vm: SearchViewModel) {
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startVoice() else vm.voicePermissionDenied() }
    val busy = ui.voice is VoiceState.Starting || ui.voice is VoiceState.Listening || ui.voice is VoiceState.Processing
    val available = ui.voiceSupport?.available != false

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (ui.voice) {
                is VoiceState.Listening -> Button(onClick = vm::stopVoice) { Text("Stop") }
                is VoiceState.Starting, is VoiceState.Processing -> Button(onClick = {}, enabled = false) { Text("Please wait") }
                else -> Button(enabled = available, onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoice()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text("Speak") }
            }
            if (busy) TextButton(onClick = vm::cancelVoice) { Text("Cancel") }
            for (lang in VoiceLang.entries) FilterChip(selected = ui.voiceLang == lang, enabled = !busy, onClick = { vm.onVoiceLang(lang) }, label = { Text(lang.label) })
        }
        val status = when (val v = ui.voice) {
            is VoiceState.Starting -> "Starting the microphone…"
            is VoiceState.Listening -> "Listening… speak now, then tap Stop."
            is VoiceState.Processing -> "Working out what you said…"
            is VoiceState.Failed -> listOfNotNull(v.error.message, PackStatuses.failureHint(v.error, ui.voiceSupport, ui.voiceLang)).joinToString(" ")
            VoiceState.Idle -> ui.voiceSupport?.let { PackStatuses.explain(it, ui.voiceLang) } ?: "Checking what voice input this phone supports…"
        }
        Text(
            status, style = MaterialTheme.typography.labelSmall,
            color = if (ui.voice is VoiceState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (ui.voice is VoiceState.Failed) TextButton(onClick = vm::dismissVoiceError) { Text("Dismiss") }
        Text(
            "Typed searches never leave the phone. Pick the language you will speak; mixed-language speech may be heard imperfectly, and you can edit the text before searching again.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            Text(WhyThis.explain(r), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

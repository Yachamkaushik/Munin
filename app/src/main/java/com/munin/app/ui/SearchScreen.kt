package com.munin.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import com.munin.app.ui.theme.ShortcutTile
import com.munin.app.ui.theme.EnterOnce
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munin.app.actions.ActionPlan
import com.munin.app.actions.ActionPlanner
import com.munin.app.actions.toSubject
import com.munin.app.answer.Answer
import com.munin.app.answer.AnswerOutcome
import com.munin.app.calc.CalcOutcome
import com.munin.app.search.SearchMode
import com.munin.app.search.SearchResult
import com.munin.app.search.SearchTimings
import com.munin.app.search.Snippet
import com.munin.app.search.WhyThis
import com.munin.app.ui.theme.ButtonStyle
import com.munin.app.ui.theme.Footnote
import com.munin.app.ui.theme.GroupDivider
import com.munin.app.ui.theme.Icon
import com.munin.app.ui.theme.IconTile
import com.munin.app.ui.theme.IosButton
import com.munin.app.ui.theme.IosCard
import com.munin.app.ui.theme.IosIcons
import com.munin.app.ui.theme.IosSwitch
import com.munin.app.ui.theme.LargeTitle
import com.munin.app.ui.theme.ListRow
import com.munin.app.ui.theme.Munin
import com.munin.app.ui.theme.SearchField
import com.munin.app.ui.theme.Section
import com.munin.app.ui.theme.Segmented
import com.munin.app.ui.theme.pressable
import com.munin.app.voice.PackStatuses
import com.munin.app.voice.VoiceLang
import com.munin.app.voice.VoiceState

@Composable
fun SearchScreen(onOpenItem: (Long) -> Unit, onOpenLedger: (java.time.YearMonth?) -> Unit, vm: SearchViewModel = viewModel(), indexVersion: Int = 0, incoming: com.munin.app.incoming.Incoming? = null, onIncomingTaken: () -> Unit = {}) {
    val ui by vm.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(indexVersion) { vm.refresh() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(incoming) {
        when (incoming) {
            is com.munin.app.incoming.Incoming.Text -> vm.searchFor(incoming.text)
            is com.munin.app.incoming.Incoming.Image -> vm.readSharedImage(incoming.uri)
            com.munin.app.incoming.Incoming.OpenSearch -> { runCatching { searchFocus.requestFocus() }; keyboard?.show() } // field focused and keyboard up, ready to type
            null -> return@LaunchedEffect
        }
        onIncomingTaken()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshApps() } // apps may have been installed or removed while away

    val r = ui.response
    val home = ui.query.isBlank()
    val toast = { text: String -> android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LargeTitle("Munin", "Find anything you saved, in any language")
                SearchField(
                    value = ui.query, onValueChange = vm::onQuery, placeholder = "Describe what you are looking for",
                    modifier = Modifier.focusRequester(searchFocus), trailing = { MicButton(ui, vm) },
                )
                // What the router made of the input, in plain words, so a surprising result is never a mystery.
                ui.understood?.let { Footnote(it, Modifier.padding(horizontal = 4.dp)) }
                AnimatedVisibility(!home) {
                    Segmented(
                        listOf("Merged", "Meaning", "Keywords"), listOf(SearchMode.MERGED, SearchMode.MEANING, SearchMode.KEYWORDS).indexOf(ui.mode),
                        onSelect = { vm.onMode(listOf(SearchMode.MERGED, SearchMode.MEANING, SearchMode.KEYWORDS)[it]) },
                    )
                }
                if (ui.showDebug && r != null && !home) Footnote(debugLine(r.mode, r.timings), Modifier.padding(horizontal = 4.dp))
            }
        }
        if (ui.voice !is VoiceState.Idle) item { VoiceStatus(ui, vm) }
        ui.shared?.let { sh -> item { SharedImageCard(sh, onSearch = { vm.searchFor(it) }, onDismiss = vm::dismissShared) } }

        if (home) {
            if (ui.reminders.isNotEmpty()) item { RemindersSection(ui.reminders, vm::reminderHandled) }
            item { HomeSections(ui, vm) }
            return@LazyColumn
        }

        if (ui.commands.isNotEmpty()) item { Section("Quick command") { ui.commands.forEachIndexed { i, c -> if (i > 0) GroupDivider(58.dp); CommandRow(c) } } }
        if (ui.settings.isNotEmpty()) item {
            Section("Settings") {
                ui.settings.forEachIndexed { i, s ->
                    if (i > 0) GroupDivider(58.dp)
                    ListRow(s.label, subtitle = "Open this settings screen", leading = { IconTile(IosIcons.Gear) }, chevron = true,
                        onClick = { if (!vm.openSettings(s)) toast("This phone has no ${s.label} screen to open.") })
                }
            }
        }
        if (ui.contacts.isNotEmpty()) item { Section("Contacts") { ui.contacts.forEachIndexed { i, c -> if (i > 0) GroupDivider(58.dp); ContactRow(c) } } }
        else if (ContactsOffer.applies(ui)) item { ContactsOfferCard(ui, vm) }
        if (ui.apps.isNotEmpty()) item {
            Section("Apps") {
                ui.apps.forEachIndexed { i, m -> if (i > 0) GroupDivider(68.dp); AppRow(m.app) { app -> if (!vm.openApp(app)) toast("Could not open ${app.label}.") } }
            }
        }
        if (ui.notifications.isNotEmpty()) item { Section("Saved notifications (${ui.notifications.size})") { ui.notifications.forEachIndexed { i, n -> if (i > 0) GroupDivider(58.dp); NotificationRow(n) } } }

        val calc = ui.calc
        when {
            calc != null -> {
                item { CalculatorCard(calc, vm::saveRate) }
                item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { IosButton("Search my files for “${ui.query.trim()}” instead", vm::searchFilesInstead, style = ButtonStyle.Plain) } }
            }
            ui.calcNote != null -> item { Footnote(ui.calcNote!!) }
            ui.error != null -> item { Text(ui.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            !ui.modelReady -> item { Footnote("Loading the on-device language model…") }
            ui.indexedChunks == 0 -> item { Footnote("Nothing is indexed yet. Open the Index tab and scan your photos first.") }
            r != null -> {
                val spending = ui.spending
                val answer = ui.answer
                spending?.let { sp -> item { SpendingCard(sp, onOpenLedger) } }
                when (answer) {
                    is AnswerOutcome.Found -> item { AnswerCard(answer.answer, onOpenItem) }
                    // It was a question but we will not guess: say so, then fall back to the list.
                    is AnswerOutcome.Declined -> item { Footnote("No direct answer: ${answer.reason}. Showing matching files instead.") }
                    else -> Unit
                }
                if (r.results.isEmpty()) item { Footnote("No matches for “${r.query}” in your files.") }
                else item {
                    Section("Files (${r.results.size})") {
                        r.results.forEachIndexed { i, res -> if (i > 0) GroupDivider(92.dp); ResultRow(res, ui.showDebug, onOpenItem) }
                    }
                }
            }
            else -> Unit
        }
        // The only thing in the app that leaves the phone, so it sits apart and says so.
        item { WebSearchSection(ui.query) }
        item { Box(Modifier.padding(bottom = 12.dp)) }
    }
}

/** What shows before anything is typed: a library summary, colourful one-tap shortcuts, voice language, and the technical-details switch. */
@Composable
private fun HomeSections(ui: SearchUiState, vm: SearchViewModel) {
    val c = Munin.colors
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        EnterOnce(0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(IosIcons.Lock, c.secondaryLabel, 13.dp)
                Text(
                    (if (ui.indexedDocs == 1) "1 document indexed" else "${ui.indexedDocs} documents indexed") + " · Typed searches stay on this phone",
                    style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TRY", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall, color = c.secondaryLabel)
            val tiles = listOf(
                Triple("Receipts", "hostel fee receipt", IosIcons.Receipt),
                Triple("Bills due", "when is the electricity bill due", IosIcons.Calendar),
                Triple("Spending", "how much did I spend", IosIcons.Ledger),
                Triple("Calculator", "20% of 4500", IosIcons.Calculator),
                Triple("Wi-Fi", "wifi", IosIcons.Globe),
                Triple("Alarm", "alarm 6:30 am", IosIcons.Clock),
            )
            tiles.chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { col, t ->
                        EnterOnce(80 + (row * 2 + col) * 70, Modifier.weight(1f)) {
                            ShortcutTile(t.first, t.second, t.third, onClick = { vm.onQuery(t.second) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Footnote("Munin reads text in your screenshots and photos on this phone, so you can search by what it says, not by file name.", Modifier.padding(horizontal = 16.dp))
        }
        Section("Voice", footer = ui.voiceSupport?.let { PackStatuses.explain(it, ui.voiceLang) } ?: "Checking what voice input this phone supports…") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(VoiceLang.entries.map { it.label }, VoiceLang.entries.indexOf(ui.voiceLang), onSelect = { vm.onVoiceLang(VoiceLang.entries[it]) })
            }
        }
        Footnote("Typed searches never leave the phone. Pick the language you will speak; mixed-language speech may be heard imperfectly, and you can edit the text before searching again.", Modifier.padding(horizontal = 16.dp))
        Section {
            ListRow("Technical details", subtitle = "Timings and ranks under results", leading = { IconTile(IosIcons.Gear) }, trailing = { IosSwitch(ui.showDebug, vm::onDebug) })
        }
    }
}

/** The mic inside the search field. Starts listening, becomes a red stop button while listening, and asks for the microphone only when first used. */
@Composable
private fun MicButton(ui: SearchUiState, vm: SearchViewModel) {
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startVoice() else vm.voicePermissionDenied() }
    val available = ui.voiceSupport?.available != false
    val listening = ui.voice is VoiceState.Listening
    val busy = ui.voice is VoiceState.Starting || ui.voice is VoiceState.Processing
    if (!available && !listening) return
    Icon(
        if (listening) IosIcons.Stop else IosIcons.Mic, if (listening) Munin.colors.red else Munin.colors.tint, 22.dp,
        Modifier.clip(RoundedCornerShape(50)).pressable(if (busy) null else {
            {
                when {
                    listening -> vm.stopVoice()
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.startVoice()
                    else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }).padding(4.dp),
    )
}

/** An honest status line while voice is working or has failed. Voice goes through the phone's speech service, so it says whether the offline pack is installed. */
@Composable
private fun VoiceStatus(ui: SearchUiState, vm: SearchViewModel) {
    val busy = ui.voice is VoiceState.Starting || ui.voice is VoiceState.Listening || ui.voice is VoiceState.Processing
    val failed = ui.voice is VoiceState.Failed
    val status = when (val v = ui.voice) {
        is VoiceState.Starting -> "Starting the microphone…"
        is VoiceState.Listening -> "Listening… speak now, then tap the stop button."
        is VoiceState.Processing -> "Working out what you said…"
        is VoiceState.Failed -> listOfNotNull(v.error.message, PackStatuses.failureHint(v.error, ui.voiceSupport, ui.voiceLang)).joinToString(" ")
        VoiceState.Idle -> ""
    }
    IosCard {
        Text(status, style = MaterialTheme.typography.bodyMedium, color = if (failed) Munin.colors.red else Munin.colors.label)
        if (busy) IosButton("Cancel", vm::cancelVoice, style = ButtonStyle.Plain)
        if (failed) IosButton("Dismiss", vm::dismissVoiceError, style = ButtonStyle.Plain)
    }
}

@Composable
private fun CalculatorCard(c: CalcOutcome, onSaveRate: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    IosCard {
        when (c) {
            is CalcOutcome.Value -> {
                Text(c.primary, style = MaterialTheme.typography.headlineLarge, color = Munin.colors.label)
                for (line in c.secondary) Text(line, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.secondaryLabel)
                Footnote(c.reading)
                IosButton("Copy result", { clipboard.setText(AnnotatedString(c.copyText)) })
            }
            is CalcOutcome.Failed -> Text(c.message, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.red)
            is CalcOutcome.RateProposal -> {
                Text("1 ${c.from} = ${com.munin.app.calc.Numbers.format(c.rate)} ${c.to}", style = MaterialTheme.typography.headlineSmall, color = Munin.colors.label)
                Footnote("Munin cannot check this rate. If you save it, it is used only for your own conversions, and each result shows how old it is.")
                IosButton("Save this rate", onSaveRate, style = ButtonStyle.Filled)
            }
        }
    }
}

@Composable
private fun AppRow(app: com.munin.app.apps.AppEntry, onOpen: (com.munin.app.apps.AppEntry) -> Unit) {
    val context = LocalContext.current
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, app.packageName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val d = context.packageManager.getApplicationIcon(app.packageName)
                val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(bmp))
                bmp.asImageBitmap()
            }.getOrNull()
        }
    }
    ListRow(
        app.label, subtitle = "Open", chevron = true, onClick = { onOpen(app) },
        leading = { Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } } },
    )
}

/** An alarm or timer. Tapping asks first; only the dialog's confirm button opens the clock app, and the clock app still waits for your save. */
@Composable
private fun CommandRow(c: com.munin.app.commands.QuickCommand) {
    var plan by remember { mutableStateOf<ActionPlan?>(null) }
    val planner = remember { ActionPlanner() }
    val p = when (c) { is com.munin.app.commands.QuickCommand.Alarm -> planner.alarm(c); is com.munin.app.commands.QuickCommand.Timer -> planner.timer(c) }
    ListRow(p.dialogTitle.removeSuffix("?"), subtitle = "Tap to review, then it opens in your clock app", leading = { IconTile(IosIcons.Clock) }, chevron = true, onClick = { plan = p })
    plan?.let { ActionConfirmDialog(it) { plan = null } }
}

/** An image shared to Munin. Its text is read on the phone; nothing is saved, and searching with it is the user's choice. */
@Composable
private fun SharedImageCard(sh: SharedImage, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    IosCard {
        Text("Image shared with Munin", style = MaterialTheme.typography.titleMedium, color = Munin.colors.label)
        when {
            sh.failed -> Footnote("Munin could not open or read this image.")
            sh.text == null -> Footnote("Reading the text in this image…")
            sh.text.isEmpty() -> Footnote("No text found in this image.")
            else -> {
                Text(sh.text, style = MaterialTheme.typography.bodyMedium, color = Munin.colors.label, maxLines = 6)
                Footnote("Read on this phone by OCR, so it can contain mistakes. It is not saved to Munin's index.")
                IosButton("Search my files for this text", { onSearch(sh.text) }, style = ButtonStyle.Filled)
            }
        }
        IosButton("Dismiss", onDismiss, style = ButtonStyle.Plain)
    }
}

/** Deadlines found in your images. Each one is only a suggestion: it goes to your calendar only after you confirm, and you save it there. */
@Composable
private fun RemindersSection(cards: List<ReminderCard>, onHandled: (String) -> Unit) {
    var open by remember { mutableStateOf<ReminderCard?>(null) }
    Section("Coming up", footer = "Dates that look like deadlines in your images. Read by OCR, so check them. Nothing is added unless you choose to.") {
        cards.forEachIndexed { i, c ->
            if (i > 0) GroupDivider()
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.label, fontWeight = FontWeight.Medium)
                    Text(c.whenText, style = MaterialTheme.typography.bodyMedium, color = Munin.colors.secondaryLabel)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IosButton("Add to calendar", { open = c })
                    IosButton("Not now", { onHandled(c.key) }, style = ButtonStyle.Plain)
                }
            }
        }
    }
    open?.let { c -> c.plan?.let { ActionConfirmDialog(it, onClose = { open = null }, onConfirmed = { onHandled(c.key) }) } }
}

/** A notification the user chose to keep. Read-only: it is a record of what was shown, not a link into the other app. */
@Composable
private fun NotificationRow(n: com.munin.app.data.NotificationEntity) {
    val whenText = remember(n.postedAt) { java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm").format(java.time.Instant.ofEpochMilli(n.postedAt).atZone(java.time.ZoneId.systemDefault())) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(IosIcons.Bell)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${n.appLabel} · $whenText", style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel)
            if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.label, fontWeight = FontWeight.Medium)
            if (n.text.isNotBlank()) Text(n.text, style = MaterialTheme.typography.bodyMedium, color = Munin.colors.label, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A saved contact. Tapping asks first, then opens the dialer with the number filled in; the call itself still needs your tap there. */
@Composable
private fun ContactRow(c: com.munin.app.contacts.ContactEntry) {
    var plan by remember { mutableStateOf<ActionPlan?>(null) }
    ListRow(c.name, subtitle = "${c.number}  ·  tap to call", leading = { IconTile(IosIcons.Person) }, chevron = true, onClick = { plan = ActionPlanner().callContact(c.name, c.number) })
    plan?.let { ActionConfirmDialog(it) { plan = null } }
}

/** When to offer the contacts permission: only when the input looks like a person (a family nickname, or "call ..."), never for ordinary searches. */
private object ContactsOffer {
    fun applies(ui: SearchUiState): Boolean {
        if (ui.contactsGranted) return false
        val q = ui.query.trim()
        if (q.isEmpty() || q.length > 40) return false
        val words = q.split(Regex("\\s+"))
        val callWord = words.size > 1 && words.first().lowercase() in com.munin.app.contacts.ContactMatcher.NOISE
        return callWord || com.munin.app.contacts.ContactMatcher.nicknameGroup(q) != null
    }
}

@Composable
private fun ContactsOfferCard(ui: SearchUiState, vm: SearchViewModel) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.contactsAnswered(it) }
    IosCard {
        Text("Find people in your contacts?", style = MaterialTheme.typography.titleMedium, color = Munin.colors.label)
        if (ui.contactsDenied) {
            Footnote("Contacts access was not allowed, so Munin cannot look up names. You can allow it in the phone's Settings, under Apps, Munin, Permissions.")
        } else {
            Footnote("Munin would read your contacts on this phone only, to match names like amma or nanna. They are not copied anywhere or sent anywhere.")
            IosButton("Allow contacts", { ask.launch(android.Manifest.permission.READ_CONTACTS) }, style = ButtonStyle.Filled)
        }
    }
}

/** A clearly labelled hand-off to the browser. Tapping it asks first and shows the exact text that would leave the phone. */
@Composable
private fun WebSearchSection(query: String) {
    var plan by remember { mutableStateOf<ActionPlan?>(null) }
    Section("Web") {
        ListRow(
            "Search the web for “${query.trim()}”", subtitle = "Leaves your phone: opens your browser.", subtitleColor = Munin.colors.red,
            leading = { IconTile(IosIcons.Globe) }, chevron = true, onClick = { plan = ActionPlanner().webSearch(query.trim()) },
        )
    }
    plan?.let { ActionConfirmDialog(it) { plan = null } }
}

@Composable
private fun SpendingCard(sp: SpendingAnswer, onOpenLedger: (java.time.YearMonth?) -> Unit) {
    IosCard {
        if (sp.count == 0) {
            Text("No payment screenshots read ${sp.scope}.", style = MaterialTheme.typography.titleMedium, color = Munin.colors.label)
        } else {
            Text(sp.display, style = MaterialTheme.typography.headlineLarge, color = Munin.colors.label)
            Text("Spent ${sp.scope}, from ${sp.count} payment screenshot${if (sp.count == 1) "" else "s"}.", style = MaterialTheme.typography.bodyLarge, color = Munin.colors.secondaryLabel)
        }
        Text("This is spending from your screenshots, not your total spending.", style = MaterialTheme.typography.labelMedium, color = Munin.colors.label)
        if (sp.needsCheck > 0) Footnote("${sp.needsCheck} more payment${if (sp.needsCheck == 1) " has" else "s have"} an amount that was not clearly read and ${if (sp.needsCheck == 1) "is" else "are"} not included.")
        if (sp.unreadable > 0) Footnote("${sp.unreadable} payment screenshot${if (sp.unreadable == 1) "" else "s"} could not be read and ${if (sp.unreadable == 1) "is" else "are"} not included.")
        IosButton("Open ledger", { onOpenLedger(sp.month) })
    }
}

@Composable
private fun AnswerCard(a: Answer, onOpenItem: (Long) -> Unit) {
    IosCard(onClick = { onOpenItem(a.source.itemId) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(a.display, style = MaterialTheme.typography.headlineLarge, color = Munin.colors.label)
                Text(
                    listOfNotNull(a.label, a.raw.replace('\n', ' ').takeIf { it.isNotBlank() && it != a.display && it != a.display.replace(", ", " ") }).joinToString(": ").ifEmpty { a.kind.name.lowercase() },
                    style = MaterialTheme.typography.bodyLarge, color = Munin.colors.secondaryLabel,
                )
                Text("From ${a.source.displayName}", style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                a.caveat?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Munin.colors.orange) }
                if (a.alternatives.isNotEmpty()) {
                    Footnote("Also in this item: " + a.alternatives.joinToString(", ") { alt -> alt.label?.let { "${alt.display} ($it)" } ?: alt.display })
                }
            }
            Thumbnail(a.source.uri, 72, Modifier.clip(RoundedCornerShape(12.dp)))
        }
        ActionButtons(a.toSubject())
        Footnote("Read from the image by OCR, so check it against the source (tap the card to see it).", color = Munin.colors.tertiaryLabel)
    }
}

private fun debugLine(mode: SearchMode, t: SearchTimings) =
    "${mode.name.lowercase()} · ${t.chunksSearched} vectors · embed %.0f ms · meaning %.0f ms · keywords %.0f ms · fuse %.1f ms · total %.0f ms"
        .format(t.embedMs, t.meaningMs, t.keywordMs, t.fuseMs, t.totalMs)

@Composable
private fun ResultRow(r: SearchResult, debug: Boolean, onOpen: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressable(onClick = { onOpen(r.itemId) }).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumbnail(r.uri, 64, Modifier.clip(RoundedCornerShape(12.dp)).border(0.5.dp, Munin.colors.separator, RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(r.displayName, style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(highlighted(r.snippet, Munin.colors.tint.copy(alpha = 0.18f)), style = MaterialTheme.typography.bodyLarge, color = Munin.colors.label, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(WhyThis.explain(r), style = MaterialTheme.typography.bodySmall, color = Munin.colors.tertiaryLabel)
            if (debug) {
                val meaning = r.meaningRank?.let { "meaning #$it (%.2f)".format(r.meaningScore) } ?: "meaning –"
                val keywords = r.keywordRank?.let { "keywords #$it (%.1f)".format(r.keywordScore) } ?: "keywords –"
                Text("$meaning · $keywords", style = MaterialTheme.typography.labelSmall, color = Munin.colors.tertiaryLabel)
            }
        }
    }
}

private fun highlighted(s: Snippet, mark: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (h in s.highlights) {
        if (h.first > at) append(s.text.substring(at, h.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = mark)) { append(s.text.substring(h.first, h.last + 1)) }
        at = h.last + 1
    }
    if (at < s.text.length) append(s.text.substring(at))
}

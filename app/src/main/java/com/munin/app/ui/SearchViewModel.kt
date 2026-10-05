package com.munin.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.munin.app.MuninApp
import com.munin.app.answer.AnswerEngine
import com.munin.app.answer.AnswerOutcome
import com.munin.app.ledger.LedgerCalc
import com.munin.app.ledger.Money
import com.munin.app.ledger.SpendingQuery
import com.munin.app.ledger.toRow
import java.time.LocalDate
import java.time.YearMonth
import com.munin.app.calc.CalcOutcome
import com.munin.app.calc.Calculator
import com.munin.app.calc.PrefsRateBook
import com.munin.app.router.QueryRouter
import com.munin.app.search.SearchEngine
import com.munin.app.voice.AndroidSpeechBackend
import com.munin.app.voice.MainScheduler
import com.munin.app.voice.VoiceError
import com.munin.app.voice.VoiceLang
import com.munin.app.voice.VoiceSession
import com.munin.app.voice.VoiceState
import com.munin.app.voice.VoiceSupport
import com.munin.app.search.SearchMode
import com.munin.app.search.SearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ledger's answer to "how much did I spend ...": an exact sum of counted payment screenshots, never an estimate. */
data class SpendingAnswer(
    val scope: String,
    val month: YearMonth?,
    val totalPaise: Long,
    val count: Int,
    val needsCheck: Int,
    val unreadable: Int,
) {
    val display get() = Money.format(totalPaise)
}

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.MERGED,
    val modelReady: Boolean = false,
    val indexedChunks: Int = 0,
    val response: SearchResponse? = null,
    /** Whether the query was a value question, and what we answered; null until a search has run. */
    val answer: AnswerOutcome? = null,
    val spending: SpendingAnswer? = null,
    /** The router's plain-words reading of the input, shown under the search box; null for an empty box. */
    val understood: String? = null,
    /** A calculation the input turned out to be (worked out on the phone); when set, files are not searched. */
    val calc: CalcOutcome? = null,
    /** The user chose to search files for an input that also parses as a calculation. */
    val calcIgnored: Boolean = false,
    val calcNote: String? = null,
    /** Installed apps whose names match the input, offered above the file results. */
    val apps: List<com.munin.app.apps.AppMatch> = emptyList(),
    /** Saved contacts matching the input (only when the user has allowed contacts). */
    val contacts: List<com.munin.app.contacts.ContactEntry> = emptyList(),
    val settings: List<com.munin.app.shortcuts.SettingsShortcut> = emptyList(),
    val contactsGranted: Boolean = false,
    /** The user said no to the contacts permission this session; Munin will not ask again until the app restarts. */
    val contactsDenied: Boolean = false,
    val error: String? = null,
    val showDebug: Boolean = true,
    val voice: VoiceState = VoiceState.Idle,
    val voiceLang: VoiceLang = VoiceLang.ENGLISH,
    /** What the phone's speech service supports; null until the system has answered. */
    val voiceSupport: VoiceSupport? = null,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val muninApp = app as MuninApp
    private val engine by lazy { SearchEngine(muninApp.database, muninApp.embedder) }
    private val answers by lazy { AnswerEngine(muninApp.database) }
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var job: Job? = null

    private val speech by lazy { AndroidSpeechBackend(app) }
    // Partial text only fills the box while talking; the search runs once, on the final text.
    private val voice by lazy {
        VoiceSession(speech, MainScheduler()) { text, final ->
            _state.update { it.copy(query = text).routed() }
            if (final) run(debounce = false)
        }
    }

    init {
        viewModelScope.launch { voice.state.collect { v -> _state.update { it.copy(voice = v) } } }
        refreshVoiceSupport()
        refreshApps()
        // Loading the model takes seconds the first time; do it now so the first search is not the slow one.
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { muninApp.embedder }
                .onSuccess { _state.update { it.copy(modelReady = true) } }
                .onFailure { e -> _state.update { it.copy(error = "Could not load the embedding model: ${e.message}") } }
            refreshCounts()
        }
        viewModelScope.launch(Dispatchers.IO) { muninApp.database.backfillFacts() }
    }

    /** Reads the installed apps off the main thread, then re-reads the current input against them. */
    fun refreshApps() { viewModelScope.launch(Dispatchers.IO) { muninApp.appIndex.refresh(); muninApp.contactIndex.refresh(); _state.update { it.routed() } } }

    /** Called with the system's answer to the contacts permission prompt. */
    fun contactsAnswered(granted: Boolean) {
        if (granted) refreshApps() else _state.update { it.copy(contactsDenied = true) }
    }

    /** Opens a settings screen the user tapped. */
    fun openSettings(s: com.munin.app.shortcuts.SettingsShortcut): Boolean = runCatching {
        muninApp.startActivity(android.content.Intent(s.action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    /** Opens an app the user tapped. */
    fun openApp(app: com.munin.app.apps.AppEntry): Boolean = muninApp.appIndex.launch(app)

    fun startVoice() = voice.start(_state.value.voiceLang)
    fun stopVoice() = voice.stop()
    fun cancelVoice() = voice.cancel()
    fun voicePermissionDenied() = voice.fail(VoiceError.NO_PERMISSION)
    fun dismissVoiceError() = voice.dismissError()
    fun onVoiceLang(l: VoiceLang) { _state.update { it.copy(voiceLang = l) }; refreshVoiceSupport() }
    fun refreshVoiceSupport() = speech.checkSupport { s -> _state.update { it.copy(voiceSupport = s) } }

    override fun onCleared() { voice.destroy() }

    private val calculator by lazy { Calculator(rates = PrefsRateBook(muninApp)) }

    private fun SearchUiState.routed(): SearchUiState {
        val d = QueryRouter.route(query, mode, calculator, allowCalculator = !calcIgnored, apps = muninApp.appIndex.search(query),
            contacts = muninApp.contactIndex.search(query), settings = com.munin.app.shortcuts.SettingsShortcuts.search(query))
        return copy(understood = d.understood, calc = d.calc, apps = d.apps, contacts = d.contacts, settings = d.settings, contactsGranted = muninApp.contactIndex.granted())
    }

    fun onQuery(q: String) { _state.update { it.copy(query = q, calcIgnored = false, calcNote = null).routed() }; run(debounce = true) }

    /** "Search my files for this instead": the input stays, but is no longer read as a calculation. */
    fun searchFilesInstead() { _state.update { it.copy(calcIgnored = true).routed() }; run(debounce = false) }

    /** Saves a typed exchange rate. Only called from the Save button, never automatically. */
    fun saveRate() {
        val p = _state.value.calc as? CalcOutcome.RateProposal ?: return
        calculator.save(p)
        _state.update { it.copy(calc = null, calcIgnored = true, calcNote = "Saved: 1 ${p.from} = ${com.munin.app.calc.Numbers.format(p.rate)} ${p.to}. Now you can type, for example, 100 ${p.from.lowercase()} in ${p.to.lowercase()}.") }
    }
    fun onMode(m: SearchMode) { _state.update { it.copy(mode = m).routed() }; run(debounce = false) }
    fun onDebug(on: Boolean) { _state.update { it.copy(showDebug = on) } }

    /** Re-runs the current query, e.g. after indexing added items. */
    fun refresh() = run(debounce = false)

    private fun run(debounce: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            val s = _state.value
            if (s.query.isBlank()) { _state.update { it.copy(response = null, answer = null, spending = null, error = null) }; return@launch }
            // A calculation is answered on the spot and never searched for in files.
            if (s.calc != null) { _state.update { it.copy(response = null, answer = null, spending = null, error = null) }; return@launch }
            if (!s.modelReady) return@launch
            try {
                val (r, a) = withContext(Dispatchers.Default) {
                    val r = engine.search(s.query, s.mode)
                    // Answers come from the merged ranking only; the single-leg modes are for comparing retrieval.
                    r to if (s.mode == SearchMode.MERGED) answers.answer(s.query, r) else AnswerOutcome.NotAQuestion
                }
                val spending = withContext(Dispatchers.Default) { spendingAnswer(s.query) }
                _state.update { it.copy(response = r, answer = a, spending = spending, error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Search failed: ${e.message}") }
            }
            refreshCounts()
        }
    }

    private suspend fun spendingAnswer(query: String): SpendingAnswer? {
        val q = SpendingQuery.parse(query) ?: return null
        val summary = LedgerCalc.summarize(muninApp.database.payments().allNow().map { it.toRow() })
        val month = q.resolve(LocalDate.now(), summary.months.map { it.month })
        val rows = summary.countedIn(month)
        return SpendingAnswer(month?.let { "in ${it.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} ${it.year}" } ?: "in all the payments read", month, rows.sumOf { it.amountPaise!! }, rows.size,
            // flagged payments are counted for the month asked about; unreadable ones have no date, so they cannot belong to a month
            summary.needsCheck.count { month == null || it.month == month }, summary.unreadable.size)
    }

    private suspend fun refreshCounts() {
        val n = withContext(Dispatchers.IO) { muninApp.database.embeddings().count() }
        _state.update { it.copy(indexedChunks = n) }
    }

    private companion object {
        const val DEBOUNCE_MS = 200L
    }
}

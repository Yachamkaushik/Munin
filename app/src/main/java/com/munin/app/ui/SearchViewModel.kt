package com.munin.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.munin.app.MuninApp
import com.munin.app.search.SearchEngine
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

data class SearchUiState(
    val query: String = "",
    val mode: SearchMode = SearchMode.MERGED,
    val modelReady: Boolean = false,
    val indexedChunks: Int = 0,
    val response: SearchResponse? = null,
    val error: String? = null,
    val showDebug: Boolean = true,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val muninApp = app as MuninApp
    private val engine by lazy { SearchEngine(muninApp.database, muninApp.embedder) }
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var job: Job? = null

    init {
        // Loading the model takes seconds the first time; do it now so the first search is not the slow one.
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { muninApp.embedder }
                .onSuccess { _state.update { it.copy(modelReady = true) } }
                .onFailure { e -> _state.update { it.copy(error = "Could not load the embedding model: ${e.message}") } }
            refreshCounts()
        }
    }

    fun onQuery(q: String) { _state.update { it.copy(query = q) }; run(debounce = true) }
    fun onMode(m: SearchMode) { _state.update { it.copy(mode = m) }; run(debounce = false) }
    fun onDebug(on: Boolean) { _state.update { it.copy(showDebug = on) } }

    /** Re-runs the current query, e.g. after indexing added items. */
    fun refresh() = run(debounce = false)

    private fun run(debounce: Boolean) {
        job?.cancel()
        job = viewModelScope.launch {
            if (debounce) delay(DEBOUNCE_MS)
            val s = _state.value
            if (s.query.isBlank()) { _state.update { it.copy(response = null, error = null) }; return@launch }
            if (!s.modelReady) return@launch
            try {
                val r = withContext(Dispatchers.Default) { engine.search(s.query, s.mode) }
                _state.update { it.copy(response = r, error = null) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Search failed: ${e.message}") }
            }
            refreshCounts()
        }
    }

    private suspend fun refreshCounts() {
        val n = withContext(Dispatchers.IO) { muninApp.database.embeddings().count() }
        _state.update { it.copy(indexedChunks = n) }
    }

    private companion object {
        const val DEBOUNCE_MS = 200L
    }
}

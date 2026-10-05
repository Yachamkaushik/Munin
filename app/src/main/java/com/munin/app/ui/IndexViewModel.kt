package com.munin.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.munin.app.MuninApp
import com.munin.app.data.ItemStatus
import com.munin.app.data.RecentItem
import com.munin.app.index.IndexScheduler
import com.munin.app.index.MediaAccess
import com.munin.app.index.MediaAccessState
import com.munin.app.index.OcrSettings
import com.munin.app.index.TeluguPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IndexUiState(
    val access: MediaAccessState = MediaAccessState.NONE,
    val running: Boolean = false,
    val indexed: Int = 0,
    val noText: Int = 0,
    val duplicates: Int = 0,
    val failed: Int = 0,
    val pending: Int = 0,
    val medianOcrMs: Long? = null,
    val medianEmbedMs: Long? = null,
    val nextName: String? = null,
    val recent: List<RecentItem> = emptyList(),
    val teluguOn: Boolean = false,
    val autoOn: Boolean = false,
) {
    val total get() = indexed + noText + duplicates + failed + pending
    val done get() = total - pending
}

private class Extras(val ocr: List<Long>, val embed: List<Long>, val next: String?, val telugu: Boolean, val auto: Boolean)

private fun median(sorted: List<Long>): Long? = if (sorted.isEmpty()) null else sorted[sorted.size / 2]

class IndexViewModel(app: Application) : AndroidViewModel(app) {
    private val muninApp = app as MuninApp
    private val db get() = muninApp.database
    private val access = MutableStateFlow(MediaAccess.state(app))
    private val teluguOn = MutableStateFlow(OcrSettings.policy(app) != TeluguPolicy.OFF)
    private val autoOn = MutableStateFlow(com.munin.app.index.AutoIndex.isOn(app))

    val state: StateFlow<IndexUiState> = combine(
        access,
        IndexScheduler.isRunning(app),
        db.items().statusCounts(),
        combine(db.items().ocrTimes(), db.items().embedTimes(), db.items().nextPendingName(), combine(teluguOn, autoOn) { t, a -> t to a }) { o, e, n, ta -> Extras(o, e, n, ta.first, ta.second) },
        db.items().recent(30),
    ) { access, running, counts, extras, recent ->
        fun n(s: String) = counts.firstOrNull { it.status == s }?.count ?: 0
        IndexUiState(
            access, running, n(ItemStatus.INDEXED), n(ItemStatus.NO_TEXT), n(ItemStatus.DUPLICATE), n(ItemStatus.FAILED), n(ItemStatus.PENDING),
            median(extras.ocr), median(extras.embed), extras.next, recent, extras.telugu, extras.auto,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IndexUiState())

    /** Applies to items indexed from now on; use Clear index and scan again to re-read existing ones. */
    fun setTelugu(enabled: Boolean) { OcrSettings.setTeluguEnabled(getApplication(), enabled); teluguOn.value = enabled }

    /** Instant indexing of new photos. Only possible with photo access; the switch is disabled without it. */
    fun setAuto(enabled: Boolean) {
        val on = enabled && MediaAccess.state(getApplication()) != MediaAccessState.NONE
        com.munin.app.index.AutoIndex.setOn(getApplication(), on); autoOn.value = on
        if (on) IndexScheduler.enqueue(getApplication()) // catch up on anything taken while it was off
    }

    fun refreshAccess() { access.value = MediaAccess.state(getApplication()) }

    fun startIndexing() {
        refreshAccess()
        if (access.value != MediaAccessState.NONE) IndexScheduler.enqueue(getApplication())
    }

    fun clearIndex() {
        viewModelScope.launch {
            IndexScheduler.cancel(getApplication())
            db.clearIndex()
        }
    }
}

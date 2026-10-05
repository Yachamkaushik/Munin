package com.munin.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.munin.app.MuninApp
import com.munin.app.ledger.LedgerCalc
import com.munin.app.ledger.LedgerSummary
import com.munin.app.ledger.toRow
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LedgerUi(val summary: LedgerSummary? = null, val month: YearMonth? = null)

class LedgerViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as MuninApp).database
    private val picked = MutableStateFlow<YearMonth?>(null)

    val state: StateFlow<LedgerUi> = combine(
        db.payments().all().map { rows -> LedgerCalc.summarize(rows.map { it.toRow() }) }.flowOn(Dispatchers.Default),
        picked,
    ) { summary, pick ->
        // The picked month if it has data, otherwise the latest month with data.
        val months = summary.months.map { it.month }
        LedgerUi(summary, if (pick != null && pick in months) pick else months.lastOrNull())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUi())

    fun select(month: YearMonth?) { picked.value = month }

    /** "INCLUDE", "EXCLUDE", or null to go back to the rules. */
    fun decide(paymentId: Long, decision: String?) { viewModelScope.launch(Dispatchers.IO) { db.payments().setDecision(paymentId, decision) } }
}

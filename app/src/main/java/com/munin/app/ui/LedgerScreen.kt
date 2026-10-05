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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munin.app.extract.PaymentDirection
import com.munin.app.extract.PaymentOutcome
import com.munin.app.ledger.PaymentRow
import java.time.YearMonth

/**
 * Spending read from payment screenshots. Totals are exact sums (in paise) of the "Counted" list only; every other
 * payment screenshot is listed below it with the reason it is not in the total.
 */
@Composable
fun LedgerScreen(initialMonth: YearMonth?, onOpenItem: (Long) -> Unit, vm: LedgerViewModel = viewModel()) {
    val ui by vm.state.collectAsState()
    LaunchedEffect(initialMonth) { if (initialMonth != null) vm.select(initialMonth) }
    val s = ui.summary

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Ledger", style = MaterialTheme.typography.headlineMedium)
                Text("Spending from your screenshots", style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "This adds up only the UPI payment screenshots Munin could read on this phone. It is not your total spending: cash, " +
                        "cards, payments without a screenshot and screenshots it could not read are left out, and each is listed below. " +
                        "The layouts have been tested on synthetic samples only.",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (s == null || (s.counted.isEmpty() && s.needsCheck.isEmpty() && s.duplicates.isEmpty() && s.notSpending.isEmpty() && s.unreadable.isEmpty() && s.excluded.isEmpty())) {
            item { Text("No payment screenshots found yet. Index your photos on the Index tab; UPI payment screenshots are recognised automatically.") }
            return@LazyColumn
        }

        val month = ui.month
        if (s.months.isNotEmpty()) {
            item { MonthChart(s.months, month, vm::select) }
            val total = s.months.firstOrNull { it.month == month }
            if (total != null && month != null) {
                item {
                    Column {
                        Text("${month.label()}: ${com.munin.app.ledger.Money.format(total.totalPaise)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("from ${total.count} payment screenshot${if (total.count == 1) "" else "s"}; all months together: ${com.munin.app.ledger.Money.format(s.totalPaise)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { SectionTitle("Counted" + if (month != null) " in ${month.label()}" else "") }
            items(s.countedIn(month), key = { "c${it.id}" }) { PaymentRowView(it, onOpenItem) { TextButton({ vm.decide(it.id, "EXCLUDE") }) { Text("Exclude") } } }

            val payees = s.payees(month)
            if (payees.isNotEmpty()) {
                item { SectionTitle("Top payees") }
                items(payees.take(5), key = { "p${it.name}" }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${it.name} (${it.count})", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(com.munin.app.ledger.Money.format(it.totalPaise), fontWeight = FontWeight.Medium)
                    }
                }
            }
        } else {
            item { Text("Nothing is counted yet. See \"Needs a check\" below.", style = MaterialTheme.typography.bodyMedium) }
        }

        if (s.needsCheck.isNotEmpty()) {
            item { SectionTitle("Needs a check (${s.needsCheck.size})", "The amount may be wrong (for example the ₹ sign was not read). These are not in any total until you include them.") }
            items(s.needsCheck, key = { "n${it.id}" }) { r ->
                PaymentRowView(r, onOpenItem) {
                    Row { TextButton({ vm.decide(r.id, "INCLUDE") }) { Text("Include") }; TextButton({ vm.decide(r.id, "EXCLUDE") }) { Text("Exclude") } }
                }
            }
        }
        if (s.duplicates.isNotEmpty()) {
            item { SectionTitle("Duplicates, counted once (${s.duplicates.size})", "The same payment screenshotted again, matched by its reference number.") }
            items(s.duplicates, key = { "d${it.row.id}" }) { d ->
                PaymentRowView(d.row, onOpenItem, note = "Same as ${d.of.displayName}" + if (d.amountsDiffer) ". The amounts differ, so check which is right." else "") {}
            }
        }
        val failed = s.notSpending.filter { it.outcome != PaymentOutcome.SUCCESS }
        val received = s.notSpending.filter { it.outcome == PaymentOutcome.SUCCESS && it.direction == PaymentDirection.RECEIVED }
        if (failed.isNotEmpty()) {
            item { SectionTitle("Failed or pending (${failed.size})", "Money did not leave, or has not yet.") }
            items(failed, key = { "f${it.id}" }) { PaymentRowView(it, onOpenItem, note = it.outcome.name.lowercase().replaceFirstChar(Char::uppercase)) {} }
        }
        if (received.isNotEmpty()) {
            item { SectionTitle("Money received (${received.size})", "Not spending.") }
            items(received, key = { "r${it.id}" }) { PaymentRowView(it, onOpenItem) {} }
        }
        if (s.unreadable.isNotEmpty()) {
            item { SectionTitle("Could not be read (${s.unreadable.size})", "Recognised as payments but missing something needed, so they are not counted.") }
            items(s.unreadable, key = { "u${it.id}" }) { PaymentRowView(it, onOpenItem, note = it.problem ?: "unreadable") {} }
        }
        if (s.excluded.isNotEmpty()) {
            item { SectionTitle("Excluded by you (${s.excluded.size})") }
            items(s.excluded, key = { "x${it.id}" }) { r -> PaymentRowView(r, onOpenItem) { TextButton({ vm.decide(r.id, null) }) { Text("Undo") } } }
        }
        item { Text("", Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String, hint: String? = null) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PaymentRowView(r: PaymentRow, onOpen: (Long) -> Unit, note: String? = null, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(r.itemId) }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(r.uri, 48)
        Column(Modifier.weight(1f)) {
            Text(r.payeeName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                listOfNotNull(r.paidDate, r.paidTime, r.reference?.let { "ref $it" }).joinToString(" · ").ifEmpty { r.displayName },
                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            note?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(r.amountPaise?.let { com.munin.app.ledger.Money.format(it) } ?: "–", fontWeight = FontWeight.Bold)
            actions()
        }
    }
}

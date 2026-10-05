package com.munin.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.munin.app.extract.PaymentDirection
import com.munin.app.extract.PaymentOutcome
import com.munin.app.ledger.PaymentRow
import com.munin.app.ui.theme.ButtonStyle
import com.munin.app.ui.theme.Footnote
import com.munin.app.ui.theme.GroupDivider
import com.munin.app.ui.theme.IosButton
import com.munin.app.ui.theme.IosCard
import com.munin.app.ui.theme.LargeTitle
import com.munin.app.ui.theme.Munin
import com.munin.app.ui.theme.Section
import com.munin.app.ui.theme.pressable
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

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { LargeTitle("Ledger", "Spending from your screenshots") }
        item {
            Footnote(
                "This adds up only the UPI payment screenshots Munin could read on this phone. It is not your total spending: cash, " +
                    "cards, payments without a screenshot and screenshots it could not read are left out, and each is listed below. " +
                    "The layouts have been tested on synthetic samples only.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
        if (s == null || (s.counted.isEmpty() && s.needsCheck.isEmpty() && s.duplicates.isEmpty() && s.notSpending.isEmpty() && s.unreadable.isEmpty() && s.excluded.isEmpty())) {
            item { Footnote("No payment screenshots found yet. Index your photos on the Index tab; UPI payment screenshots are recognised automatically.") }
            return@LazyColumn
        }

        val month = ui.month
        if (s.months.isNotEmpty()) {
            val total = s.months.firstOrNull { it.month == month }
            item {
                IosCard {
                    if (total != null && month != null) {
                        Text(month.label(), style = MaterialTheme.typography.bodyMedium, color = Munin.colors.secondaryLabel)
                        Text(com.munin.app.ledger.Money.format(total.totalPaise), style = MaterialTheme.typography.headlineLarge, color = Munin.colors.label)
                        Footnote("from ${total.count} payment screenshot${if (total.count == 1) "" else "s"}; all months together: ${com.munin.app.ledger.Money.format(s.totalPaise)}")
                    }
                    MonthChart(s.months, month, vm::select)
                }
            }
            item {
                Section("Counted" + if (month != null) " in ${month.label()}" else "") {
                    val rows = s.countedIn(month)
                    rows.forEachIndexed { i, r -> if (i > 0) GroupDivider(72.dp); PaymentRowView(r, onOpenItem) { IosButton("Exclude", { vm.decide(r.id, "EXCLUDE") }, style = ButtonStyle.Plain) } }
                }
            }
            val payees = s.payees(month)
            if (payees.isNotEmpty()) item {
                Section("Top payees") {
                    payees.take(5).forEachIndexed { i, p ->
                        if (i > 0) GroupDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${p.name} (${p.count})", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.label)
                            Text(com.munin.app.ledger.Money.format(p.totalPaise), style = MaterialTheme.typography.bodyLarge, color = Munin.colors.secondaryLabel, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        } else {
            item { Footnote("Nothing is counted yet. See \"Needs a check\" below.") }
        }

        if (s.needsCheck.isNotEmpty()) item {
            Section("Needs a check (${s.needsCheck.size})", "The amount may be wrong (for example the ₹ sign was not read). These are not in any total until you include them.") {
                s.needsCheck.forEachIndexed { i, r ->
                    if (i > 0) GroupDivider(72.dp)
                    PaymentRowView(r, onOpenItem) {
                        Row { IosButton("Include", { vm.decide(r.id, "INCLUDE") }, style = ButtonStyle.Plain); IosButton("Exclude", { vm.decide(r.id, "EXCLUDE") }, style = ButtonStyle.Plain) }
                    }
                }
            }
        }
        if (s.duplicates.isNotEmpty()) item {
            Section("Duplicates, counted once (${s.duplicates.size})", "The same payment screenshotted again, matched by its reference number.") {
                s.duplicates.forEachIndexed { i, d ->
                    if (i > 0) GroupDivider(72.dp)
                    PaymentRowView(d.row, onOpenItem, note = "Same as ${d.of.displayName}" + if (d.amountsDiffer) ". The amounts differ, so check which is right." else "") {}
                }
            }
        }
        val failed = s.notSpending.filter { it.outcome != PaymentOutcome.SUCCESS }
        val received = s.notSpending.filter { it.outcome == PaymentOutcome.SUCCESS && it.direction == PaymentDirection.RECEIVED }
        if (failed.isNotEmpty()) item {
            Section("Failed or pending (${failed.size})", "Money did not leave, or has not yet.") {
                failed.forEachIndexed { i, r -> if (i > 0) GroupDivider(72.dp); PaymentRowView(r, onOpenItem, note = r.outcome.name.lowercase().replaceFirstChar(Char::uppercase)) {} }
            }
        }
        if (received.isNotEmpty()) item {
            Section("Money received (${received.size})", "Not spending.") { received.forEachIndexed { i, r -> if (i > 0) GroupDivider(72.dp); PaymentRowView(r, onOpenItem) {} } }
        }
        if (s.unreadable.isNotEmpty()) item {
            Section("Could not be read (${s.unreadable.size})", "Recognised as payments but missing something needed, so they are not counted.") {
                s.unreadable.forEachIndexed { i, r -> if (i > 0) GroupDivider(72.dp); PaymentRowView(r, onOpenItem, note = r.problem ?: "unreadable") {} }
            }
        }
        if (s.excluded.isNotEmpty()) item {
            Section("Excluded by you (${s.excluded.size})") {
                s.excluded.forEachIndexed { i, r -> if (i > 0) GroupDivider(72.dp); PaymentRowView(r, onOpenItem) { IosButton("Undo", { vm.decide(r.id, null) }, style = ButtonStyle.Plain) } }
            }
        }
        item { Column(Modifier.padding(bottom = 12.dp)) {} }
    }
}

@Composable
private fun PaymentRowView(r: PaymentRow, onOpen: (Long) -> Unit, note: String? = null, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(onClick = { onOpen(r.itemId) }).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(r.uri, 44, Modifier.clip(RoundedCornerShape(9.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(r.payeeName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.label)
            Text(
                listOfNotNull(r.paidDate, r.paidTime, r.reference?.let { "ref $it" }).joinToString(" · ").ifEmpty { r.displayName },
                style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Munin.colors.orange, fontWeight = FontWeight.Medium) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(r.amountPaise?.let { com.munin.app.ledger.Money.format(it) } ?: "–", style = MaterialTheme.typography.bodyLarge, color = Munin.colors.label, fontWeight = FontWeight.SemiBold)
            actions()
        }
    }
}

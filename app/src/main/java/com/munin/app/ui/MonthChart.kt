package com.munin.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munin.app.ledger.MonthTotal
import com.munin.app.ledger.Money
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Monthly totals as a single-series bar chart: one hue, thin rounded bars on a baseline, the selected bar at full
 * strength and the rest softened, and a value label only on the selected bar. Months between the first and last
 * month with data that have no payments are shown as empty slots (no payments *read*, not "zero spent"). The list
 * under the chart is the table version of the same numbers.
 */
@Composable
fun MonthChart(months: List<MonthTotal>, selected: YearMonth?, onSelect: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    if (months.isEmpty()) return
    val byMonth = months.associateBy { it.month }
    val slots = generateSequence(months.first().month) { it.plusMonths(1) }.takeWhile { it <= months.last().month }.toList().takeLast(12)
    val max = months.maxOf { it.totalPaise }.coerceAtLeast(1)
    val barArea = 112.dp

    Row(modifier.fillMaxWidth().heightIn(min = 170.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        for (m in slots) {
            val t = byMonth[m]
            val isSel = m == selected
            val description = if (t == null) "${m.label()}: no payments read" else "${m.label()}: ${Money.format(t.totalPaise)} from ${t.count} payments" + if (isSel) ", selected" else ""
            Column(
                Modifier.weight(1f).clickable(enabled = t != null) { onSelect(m) }.semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    if (isSel && t != null) Money.format(t.totalPaise) else "", style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                )
                Box(Modifier.height(barArea), contentAlignment = Alignment.BottomCenter) {
                    if (t != null) {
                        val h = (barArea * (t.totalPaise.toFloat() / max)).coerceAtLeast(4.dp)
                        Box(
                            Modifier.width(32.dp).height(h).alpha(if (isSel) 1f else 0.4f)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Text(
                    m.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + if (m.monthValue == 1 || m == slots.first()) " ${m.year % 100}" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
}

fun YearMonth.label(): String = month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " $year"

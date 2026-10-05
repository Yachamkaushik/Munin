package com.munin.app.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import com.munin.app.ui.theme.IosButton
import com.munin.app.ui.theme.Munin
import com.munin.app.ui.theme.pressable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munin.app.actions.ActionKind
import com.munin.app.actions.ActionPlan
import com.munin.app.actions.ActionPlanner
import com.munin.app.actions.ActionSubject
import com.munin.app.actions.IntentFactory

/**
 * One button per action a fact offers. Tapping a button never does anything but open a dialog that shows the value
 * and what will happen; only the dialog's confirm button hands anything to another app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionButtons(subject: ActionSubject, modifier: Modifier = Modifier) {
    val planner = remember { ActionPlanner() }
    val kinds = remember(subject) { planner.plans(subject).map { it.kind to it.buttonLabel } }
    var pending by remember { mutableStateOf<ActionPlan?>(null) }

    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((kind, label) in kinds) {
            // Plans are rebuilt on tap so "today" (for years that were not read) is never stale.
            IosButton(label, onClick = { pending = planner.plans(subject).firstOrNull { it.kind == kind } })
        }
    }

    pending?.let { plan -> ActionConfirmDialog(plan) { pending = null } }
}

/** Shows what an action will do and, only on confirm, hands it to another app. Cancel and dismiss do nothing. Laid out like an iOS alert. */
@Composable
fun ActionConfirmDialog(plan: ActionPlan, onConfirmed: () -> Unit = {}, onClose: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp)).background(Munin.colors.card)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 22.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(plan.dialogTitle, style = MaterialTheme.typography.titleMedium, color = Munin.colors.label, textAlign = TextAlign.Center)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Munin.colors.fill).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((k, v) in plan.details) {
                        Column {
                            Text(k, style = MaterialTheme.typography.labelSmall, color = Munin.colors.secondaryLabel)
                            Text(v, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.label, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                for (n in plan.notes) Text(n, style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel, textAlign = TextAlign.Center)
                if (plan.fromOcr) Text("Values are read from the image by OCR.", style = MaterialTheme.typography.labelSmall, color = Munin.colors.tertiaryLabel, textAlign = TextAlign.Center)
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Munin.colors.separator))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                AlertButton("Cancel", Modifier.weight(1f), bold = false, onClick = onClose)
                Box(Modifier.width(0.5.dp).fillMaxHeight().background(Munin.colors.separator))
                AlertButton(plan.confirmLabel, Modifier.weight(1f), bold = true) {
                    onClose()
                    onConfirmed()
                    try {
                        context.startActivity(IntentFactory.build(plan.payload))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "No app on this phone can do that (${plan.buttonLabel.lowercase()}).", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertButton(text: String, modifier: Modifier, bold: Boolean, onClick: () -> Unit) {
    Box(modifier.pressable(onClick).padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Munin.colors.tint, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

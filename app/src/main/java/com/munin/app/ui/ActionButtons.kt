package com.munin.app.ui

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    val context = LocalContext.current
    val planner = remember { ActionPlanner() }
    val kinds = remember(subject) { planner.plans(subject).map { it.kind to it.buttonLabel } }
    var pending by remember { mutableStateOf<ActionPlan?>(null) }

    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((kind, label) in kinds) {
            // Plans are rebuilt on tap so "today" (for years that were not read) is never stale.
            OutlinedButton(onClick = { pending = planner.plans(subject).firstOrNull { it.kind == kind } }) { Text(label) }
        }
    }

    pending?.let { plan -> ActionConfirmDialog(plan) { pending = null } }
}

/** Shows what an action will do and, only on confirm, hands it to another app. Cancel and dismiss do nothing. */
@Composable
fun ActionConfirmDialog(plan: ActionPlan, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(plan.dialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((k, v) in plan.details) {
                    Column {
                        Text(k, style = MaterialTheme.typography.labelSmall)
                        Text(v, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                }
                for (n in plan.notes) Text(n, style = MaterialTheme.typography.bodySmall)
                if (plan.fromOcr) Text("Values are read from the image by OCR.", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onClose()
                try {
                    context.startActivity(IntentFactory.build(plan.payload))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, "No app on this phone can do that (${plan.buttonLabel.lowercase()}).", Toast.LENGTH_LONG).show()
                }
            }) { Text(plan.confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

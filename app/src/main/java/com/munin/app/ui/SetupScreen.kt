package com.munin.app.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.munin.app.setup.BackgroundSetup
import com.munin.app.setup.SetupLauncher

/**
 * Helps new screenshots get indexed on time. Munin starts nothing by itself in the background; the phone has to be allowed to wake it. These are
 * links to the phone's own settings: Munin cannot change them, and (except battery optimisation) cannot even see their state.
 */
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ } // coming back from a settings screen
    val steps = remember { BackgroundSetup.steps(Build.MANUFACTURER, Build.BRAND) }
    val tip = remember { BackgroundSetup.recentsTip(Build.MANUFACTURER, Build.BRAND) }
    val unrestricted = remember(refresh) { SetupLauncher.ignoringBatteryOptimisations(context) }

    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Background setup", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Instant indexing relies on the phone waking Munin when a new screenshot appears. Phones often block that to save battery. These buttons open your phone's own settings; " +
                        "Munin cannot change them for you.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (BackgroundSetup.isVivoFamily(Build.MANUFACTURER, Build.BRAND)) {
                    Text("This looks like a vivo or iQOO phone, so the Funtouch OS screens are listed too. They have not been checked on a real iQOO: if a button cannot open the exact screen, Munin opens its app info page instead.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(steps, key = { it.id }) { step ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(step.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (step.id == "battery") {
                        Text(if (unrestricted) "Now: not restricted for battery." else "Now: Android may restrict Munin to save battery.", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    } else {
                        Text("Munin cannot see this setting. Check it yourself.", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(step.why, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        when (SetupLauncher.open(context, step)) {
                            SetupLauncher.Result.OPENED -> Unit
                            SetupLauncher.Result.FELL_BACK -> Toast.makeText(context, "That screen is not on this phone. Opened Munin's app info instead.", Toast.LENGTH_LONG).show()
                            SetupLauncher.Result.FAILED -> Toast.makeText(context, "Could not open any settings screen for this.", Toast.LENGTH_LONG).show()
                        }
                    }) { Text(step.button) }
                }
            }
        }
        tip?.let { item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Keep Munin from being cleared", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); Text(it, style = MaterialTheme.typography.bodySmall) } } } }
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") } }
    }
}

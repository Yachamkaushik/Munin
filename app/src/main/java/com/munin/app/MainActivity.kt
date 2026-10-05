package com.munin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import com.munin.app.incoming.Incoming
import com.munin.app.incoming.IncomingParser
import com.munin.app.ui.IndexScreen
import com.munin.app.ui.ItemDetailScreen
import com.munin.app.ui.LedgerScreen
import com.munin.app.ui.SearchScreen
import com.munin.app.ui.SetupScreen

class MainActivity : ComponentActivity() {
    /** What another app handed us (selected text, shared text or image); null once the search screen has taken it. */
    private var incoming by mutableStateOf<Incoming?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming = readIncoming(intent)
    }

    @Suppress("DEPRECATION")
    private fun readIncoming(i: android.content.Intent): Incoming? = IncomingParser.parse(
        i.action, i.type,
        i.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString(),
        i.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString(),
        (i.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM))?.toString(),
    )

    override fun onResume() {
        super.onResume()
        com.munin.app.edge.EdgeHandle.sync(this) // in the foreground, so a switched-on handle can be (re)started
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming = readIncoming(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var tab by rememberSaveable { mutableStateOf(0) }
                    var indexVersion by rememberSaveable { mutableIntStateOf(0) }
                    var ledgerMonth by remember { mutableStateOf<java.time.YearMonth?>(null) }
                    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
                    var showSetup by rememberSaveable { mutableStateOf(false) }
                    // Something arrived from another app: show the search tab, where the search screen picks it up.
                    LaunchedEffect(incoming) { if (incoming != null) { tab = 0; detailId = null } }
                    BackHandler(enabled = detailId != null || showSetup) { if (showSetup) showSetup = false else detailId = null }
                    Scaffold(bottomBar = {
                        NavigationBar {
                            NavigationBarItem(selected = tab == 0, onClick = { tab = 0; indexVersion++ }, icon = {}, label = { Text("Search") })
                            NavigationBarItem(selected = tab == 1, onClick = { tab = 1; showSetup = false }, icon = {}, label = { Text("Index") })
                            NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = {}, label = { Text("Ledger") })
                        }
                    }) { padding ->
                        Surface(Modifier.padding(padding)) {
                            val open = detailId
                            when {
                                open != null -> ItemDetailScreen(open, onBack = { detailId = null })
                                tab == 0 -> SearchScreen(onOpenItem = { detailId = it }, onOpenLedger = { ledgerMonth = it; tab = 2 }, indexVersion = indexVersion, incoming = incoming, onIncomingTaken = { incoming = null })
                                tab == 2 -> LedgerScreen(initialMonth = ledgerMonth, onOpenItem = { detailId = it })
                                showSetup && tab == 1 -> SetupScreen(onBack = { showSetup = false })
                                else -> IndexScreen(onOpenSetup = { showSetup = true })
                            }
                        }
                    }
                }
            }
        }
    }
}

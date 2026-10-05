package com.munin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.munin.app.ui.theme.Icon
import com.munin.app.ui.theme.IosIcons
import com.munin.app.ui.theme.Munin
import com.munin.app.ui.theme.MuninTheme
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
        enableEdgeToEdge()
        setContent {
            MuninTheme {
                var tab by rememberSaveable { mutableStateOf(0) }
                var indexVersion by rememberSaveable { mutableIntStateOf(0) }
                var ledgerMonth by remember { mutableStateOf<java.time.YearMonth?>(null) }
                var detailId by rememberSaveable { mutableStateOf<Long?>(null) }
                var showSetup by rememberSaveable { mutableStateOf(false) }
                // Something arrived from another app: show the search tab, where the search screen picks it up.
                LaunchedEffect(incoming) { if (incoming != null) { tab = 0; detailId = null } }
                BackHandler(enabled = detailId != null || showSetup) { if (showSetup) showSetup = false else detailId = null }
                Scaffold(
                    containerColor = Munin.colors.groupedBackground,
                    bottomBar = {
                        TabBar(
                            selected = tab,
                            onSelect = { i -> if (i == 0) indexVersion++; if (i == 1) showSetup = false; tab = i; detailId = null },
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize()) {
                      Box(Modifier.fillMaxSize().padding(padding)) {
                        val open = detailId
                        when {
                            open != null -> ItemDetailScreen(open, onBack = { detailId = null })
                            tab == 0 -> SearchScreen(onOpenItem = { detailId = it }, onOpenLedger = { ledgerMonth = it; tab = 2 }, indexVersion = indexVersion, incoming = incoming, onIncomingTaken = { incoming = null })
                            tab == 2 -> LedgerScreen(initialMonth = ledgerMonth, onOpenItem = { detailId = it })
                            showSetup && tab == 1 -> SetupScreen(onBack = { showSetup = false })
                            else -> IndexScreen(onOpenSetup = { showSetup = true })
                        }
                      }
                      // A soft cover behind the status bar so scrolled content does not run under the clock and icons.
                      Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Brush.verticalGradient(listOf(Munin.colors.groupedBackground.copy(alpha = 0.55f), Color.Transparent))))
                    }
                }
            }
        }
    }
}

/** The bottom tab bar, iOS style: a translucent bar with a hairline above it and a thin-line icon and small label per tab; the chosen tab is in the accent colour. */
@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("Search" to IosIcons.Search, "Index" to IosIcons.Photos, "Ledger" to IosIcons.Ledger)
    Column(Modifier.background(Munin.colors.bar)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Munin.colors.separator))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 8.dp, bottom = 4.dp)) {
            tabs.forEachIndexed { i, (label, icon) ->
                val on = i == selected
                val color by animateColorAsState(if (on) Munin.colors.tint else Munin.colors.secondaryLabel, label = "tab-color")
                val lift by animateFloatAsState(if (on) 1.06f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "tab-lift")
                Column(
                    Modifier.weight(1f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onSelect(i) }.padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(Modifier.scale(lift)) { Icon(icon, color, 26.dp) }
                    Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 10.sp)
                }
            }
        }
    }
}

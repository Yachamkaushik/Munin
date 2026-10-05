package com.munin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.munin.app.ml.E5Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Step1Screen() } } }
    }
}

/** Step 1 diagnostic: tokenizer + embedding check. Not the search UI (that arrives in step 3). */
@Composable
private fun Step1Screen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("హాస్టల్ ఫీజు రసీదు") }
    var output by remember { mutableStateOf("Model not loaded yet.") }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Munin · step 1 diagnostic", style = MaterialTheme.typography.titleLarge)
        Text("Tokenizer and embedding check. No search yet. Runs fully offline.")
        OutlinedTextField(text, { text = it }, label = { Text("Text (embedded as a query)") })
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                output = withContext(Dispatchers.Default) {
                    runCatching {
                        val t0 = System.nanoTime()
                        E5Embedder.load(context).use { e ->
                            val t1 = System.nanoTime()
                            val ids = e.tokenizer.encodeForModel("query: $text")
                            val vec = e.embedIds(ids)
                            val t2 = System.nanoTime()
                            "normalized: ${e.tokenizer.normalize("query: $text")}\n" +
                                "token ids (${ids.size}): ${ids.joinToString(" ")}\n" +
                                "vector[0..4]: ${vec.take(5).joinToString { "%.5f".format(it) }}\n" +
                                "load ${(t1 - t0) / 1_000_000} ms, tokenize+embed ${(t2 - t1) / 1_000_000} ms"
                        }
                    }.getOrElse { "Failed: $it" }
                }
                busy = false
            }
        }) { Text(if (busy) "Working…" else "Tokenize and embed") }
        Text(output)
    }
}

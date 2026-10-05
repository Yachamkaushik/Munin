package com.munin.app.ui

import android.content.Intent
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.munin.app.MuninApp
import com.munin.app.actions.LOW_CONFIDENCE_BELOW
import com.munin.app.actions.toSubject
import com.munin.app.answer.AnswerFormat
import com.munin.app.data.FactEntity
import com.munin.app.data.ItemEntity
import com.munin.app.extract.FactExtractor
import com.munin.app.extract.FactType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class ItemDetail(val item: ItemEntity, val text: String, val facts: List<FactEntity>) {
    val title get() = FactExtractor.lines(text).firstOrNull().orEmpty()
}

/** The source: the image, the text read from it, and every fact found, each with the actions it supports. */
@Composable
fun ItemDetailScreen(itemId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = (context.applicationContext as MuninApp).database
    val detail by produceState<ItemDetail?>(null, itemId) {
        value = withContext(Dispatchers.IO) {
            db.items().byId(itemId)?.let { ItemDetail(it, db.facts().chunkTexts(itemId).joinToString("\n"), db.facts().allForItem(itemId)) }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("← Back") }
        val d = detail
        if (d == null) { Text("Loading…"); return@Column }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(d.item.displayName, style = MaterialTheme.typography.titleMedium)
                    SourceImage(d.item.uri)
                    OutlinedButton(onClick = {
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.item.uri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(view) }
                    }) { Text("Open in gallery") }
                }
            }
            item { Text("Found in this item", style = MaterialTheme.typography.titleMedium) }
            if (d.facts.isEmpty()) item { Text("No amounts, dates, phone numbers or addresses were found in the text that was read.", style = MaterialTheme.typography.bodyMedium) }
            items(d.facts, key = { it.id }) { FactCard(it, d) }
            item { Text("Text read from the image", style = MaterialTheme.typography.titleMedium) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            d.text.ifBlank { "No text was found in this image." },
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { Text("Telugu text in images is not read, and the rest is read by OCR, so it can contain mistakes.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 24.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(facts: List<FactEntity>, key: (FactEntity) -> Long, content: @Composable (FactEntity) -> Unit) =
    items(facts.size, key = { key(facts[it]) }) { content(facts[it]) }

@Composable
private fun FactCard(f: FactEntity, d: ItemDetail) {
    val type = FactType.valueOf(f.name)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when (type) { FactType.AMOUNT -> "Amount"; FactType.DATE -> "Date"; FactType.PHONE -> "Phone number"; FactType.ADDRESS -> "Address" },
                style = MaterialTheme.typography.labelMedium,
            )
            Text(AnswerFormat.display(type, f.value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(f.label, "read as \"${f.raw.replace('\n', ' ')}\"".takeIf { f.raw.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (f.confidence < LOW_CONFIDENCE_BELOW) Text("Low confidence. Check it against the image.", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            if (type == FactType.DATE && f.value.startsWith("--")) Text("The year was not in the text.", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
            ActionButtons(f.toSubject(d.title, d.item.displayName), Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SourceImage(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(1080, 1080), null).asImageBitmap() }.getOrNull() }
    }
    bitmap?.let { Image(it, "Source image", Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit) }
}

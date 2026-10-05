package com.munin.app.ui

import android.content.Intent
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.munin.app.ui.theme.BackBar
import com.munin.app.ui.theme.ButtonStyle
import com.munin.app.ui.theme.Footnote
import com.munin.app.ui.theme.GroupDivider
import com.munin.app.ui.theme.IosButton
import com.munin.app.ui.theme.IosCard
import com.munin.app.ui.theme.Munin
import com.munin.app.ui.theme.Section
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
        BackBar("Back", onBack)
        val d = detail
        if (d == null) { Footnote("Loading…"); return@Column }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(d.item.displayName, style = MaterialTheme.typography.headlineSmall, color = Munin.colors.label, maxLines = 2)
                    SourceImage(d.item.uri)
                    IosButton("Open in gallery", {
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.item.uri), "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(view) }
                    })
                }
            }
            if (d.facts.isEmpty()) item { Footnote("No amounts, dates, phone numbers or addresses were found in the text that was read.") }
            else item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("FOUND IN THIS ITEM", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel)
                    for (f in d.facts) FactCard(f, d)
                }
            }
            item {
                Section("Text read from the image") {
                    SelectionContainer {
                        Text(
                            d.text.ifBlank { "No text was found in this image." },
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = Munin.colors.label,
                        )
                    }
                }
            }
            item { Footnote("Telugu text in images is not read, and the rest is read by OCR, so it can contain mistakes.", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) }
        }
    }
}

@Composable
private fun FactCard(f: FactEntity, d: ItemDetail) {
    val type = FactType.valueOf(f.name)
    IosCard {
        Text(
            when (type) { FactType.AMOUNT -> "Amount"; FactType.DATE -> "Date"; FactType.PHONE -> "Phone number"; FactType.ADDRESS -> "Address" },
            style = MaterialTheme.typography.bodySmall, color = Munin.colors.secondaryLabel,
        )
        Text(AnswerFormat.display(type, f.value), style = MaterialTheme.typography.headlineSmall, color = Munin.colors.label)
        Footnote(listOfNotNull(f.label, "read as \"${f.raw.replace('\n', ' ')}\"".takeIf { f.raw.isNotBlank() }).joinToString(" · "))
        if (f.confidence < LOW_CONFIDENCE_BELOW) Text("Low confidence. Check it against the image.", style = MaterialTheme.typography.labelMedium, color = Munin.colors.orange)
        if (type == FactType.DATE && f.value.startsWith("--")) Text("The year was not in the text.", style = MaterialTheme.typography.labelMedium, color = Munin.colors.orange)
        ActionButtons(f.toSubject(d.title, d.item.displayName), Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SourceImage(uri: String) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(1080, 1080), null).asImageBitmap() }.getOrNull() }
    }
    bitmap?.let { Image(it, "Source image", Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit) }
}

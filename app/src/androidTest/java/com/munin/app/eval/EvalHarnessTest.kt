package com.munin.app.eval

import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.munin.app.answer.AnswerEngine
import com.munin.app.answer.AnswerOptions
import com.munin.app.answer.AnswerOutcome
import com.munin.app.data.ItemEntity
import com.munin.app.data.ItemKind
import com.munin.app.data.MuninDatabase
import com.munin.app.index.ContentSource
import com.munin.app.index.Indexer
import com.munin.app.index.LayeredOcrEngine
import com.munin.app.index.MlKitOcrEngine
import com.munin.app.index.TeluguPolicy
import com.munin.app.index.TesseractOcrEngine
import com.munin.app.index.OcrEngine
import com.munin.app.index.OcrResult
import com.munin.app.ledger.LedgerCalc
import com.munin.app.ledger.toRow
import com.munin.app.ml.E5Embedder
import com.munin.app.search.SearchEngine
import com.munin.app.search.SearchMode
import com.munin.app.search.SearchOptions
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evaluation harness. Indexes the synthetic corpus through the real pipeline and records raw results to
 * `<app internal files>/eval/report-<mode>.json`; tools/eval/report.py turns those into metrics.
 *
 * Sets (argument `eval_set`): `dev` (default; the set the variants are chosen on) or `heldout` (new documents and queries, measured once).
 * Modes (instrumentation argument `eval_modes`, comma separated, default "oracle"):
 *  - image_t1 / image_t2: like image, with the Telugu second reader (Tesseract) used when ML Kit is doubtful / on every image.
 *  - oracle: each document's true text is indexed, so retrieval and extraction are measured without OCR errors.
 *  - image:  the rendered PNGs (copied into <app internal files>/eval/images by tools/eval/run_eval.sh) go through real ML Kit OCR.
 */
class EvalHarnessTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun json(name: String) = assets.open(name).bufferedReader().readText()

    private val set = InstrumentationRegistry.getArguments().getString("eval_set") ?: "dev"
    private val prefix = mapOf("dev" to "", "heldout" to "heldout_", "fresh" to "fresh_").getOrElse(set) { error("unknown eval_set '$set' (dev, heldout or fresh)") }

    /** Candidate fixes, declared before the held-out set was measured. B0 is today's behaviour. */
    private val retrievalVariants = linkedMapOf(
        "B0" to SearchOptions(),
        "S1" to SearchOptions(stopwords = true),
        "C1" to SearchOptions(minKeywordCoverage = 0.5f),
        "S2" to SearchOptions(stopwords = true, minKeywordCoverage = 0.5f),
        "S3" to SearchOptions(stopwords = true, minKeywordCoverage = 0.5f, keywordWeight = 0.5),
    )
    private val answerGates = linkedMapOf(
        "A0" to AnswerOptions(),
        "A1" to AnswerOptions(grounding = true),
        "A2" to AnswerOptions(grounding = true, topicFunctionWords = true),
        "A3" to AnswerOptions(grounding = true, topicFunctionWords = true, topicGenericWords = true),
    )

    @Test fun runEvaluation() {
        val modes = (InstrumentationRegistry.getArguments().getString("eval_modes") ?: "oracle").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val manifest = JSONObject(json(prefix + "manifest.json"))
        // a silent fall-back to another set once produced a "fresh" report that was really the dev set
        require(manifest.optString("set", "dev") == set) { "manifest is for set '${manifest.optString("set", "dev")}', not '$set'" }
        val queries = JSONObject(json(prefix + "queries.json")).getJSONArray("queries")
        val embedder = E5Embedder.load(target)
        try {
            for (mode in modes) runMode(mode, manifest, queries, embedder)
        } finally { embedder.close() }
    }

    private fun runMode(mode: String, manifest: JSONObject, queries: JSONArray, embedder: E5Embedder) = runBlocking {
        val docs = manifest.getJSONArray("docs")
        val db = MuninDatabase.create(ApplicationProvider.getApplicationContext(), name = null)
        val imageDir = File(target.filesDir, "eval/images") // copied in with run-as by tools/eval/run_eval.sh (external storage is not reliably readable)
        val texts = HashMap<String, String>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            texts["doc:${d.getString("id")}"] = lines(d).joinToString("\n")
        }

        val ocr: OcrEngine; val content: ContentSource
        var recorder: RecordingOcr? = null; var tesseract: TesseractOcrEngine? = null
        if (mode == "oracle") {
            ocr = object : OcrEngine { override suspend fun recognize(uri: String, rotationDegrees: Int) = OcrResult(texts.getValue(uri), "oracle") }
            content = ContentSource { ByteArrayInputStream(texts.getValue(it).toByteArray()) }
        } else {
            require(imageDir.isDirectory && (imageDir.list()?.size ?: 0) >= docs.length()) { "no images in $imageDir: run tools/eval/run_eval.sh" }
            val policy = when (mode) {
                "image" -> TeluguPolicy.OFF
                "image_t1" -> TeluguPolicy.WHEN_DOUBTFUL
                "image_t2" -> TeluguPolicy.ALWAYS
                else -> error("unknown eval mode '$mode'")
            }
            tesseract = TesseractOcrEngine(target)
            ocr = RecordingOcr(LayeredOcrEngine(MlKitOcrEngine(target), tesseract) { policy }).also { recorder = it; it.warmUp() }
            content = ContentSource { FileInputStream(File(Uri.parse(it).path!!)) }
        }

        // ---- index ----
        val uriOf = HashMap<String, String>()
        for (i in 0 until docs.length()) {
            val id = docs.getJSONObject(i).getString("id")
            val uri = if (mode == "oracle") "doc:$id" else Uri.fromFile(File(imageDir, "$id.png")).toString()
            uriOf[id] = uri
            db.items().insertIgnore(ItemEntity(uri = uri, kind = ItemKind.IMAGE, displayName = id, sizeBytes = 1, modifiedAt = 1, addedAt = (docs.length() - i).toLong(), width = 900, height = 1600, rotation = 0))
        }
        val indexer = Indexer(db, content, ocr, embedder)
        val t0 = System.nanoTime()
        while (indexer.processNext()) Unit
        val indexSeconds = (System.nanoTime() - t0) / 1e9
        val idOfItem = db.items().allExisting().associate { it.id to it.uri }.mapValues { (_, uri) -> uriOf.entries.first { it.value == uri }.key }
        val itemOfDoc = idOfItem.entries.associate { it.value to it.key }

        if (mode != "oracle") {
            // The OCR'd text must be the manifest's document: a mix-up of image folders once produced a whole "fresh" run on dev images.
            var matched = 0; var checked = 0
            for (i in 0 until docs.length()) {
                val d = docs.getJSONObject(i); if (d.getString("lang") != "en") continue
                val itemId = itemOfDoc[d.getString("id")] ?: continue
                val first = lines(d).first().lowercase().filter { it.isLetterOrDigit() }.take(5)
                checked++; if (first in db.facts().chunkTexts(itemId).joinToString(" ").lowercase().filter { it.isLetterOrDigit() }) matched++
            }
            require(checked > 0 && matched >= checked * 0.7) { "OCR text does not match the manifest ($matched of $checked English documents): wrong image folder for set '$set'?" }
        }

        // what the OCR actually produced for each document, and which reader produced it (for character error rates)
        val ocrOut = JSONObject()
        if (mode != "oracle") for ((docId, itemId) in itemOfDoc) ocrOut.put(docId, JSONObject().put("engine", recorder?.engines?.get(uriOf.getValue(docId)) ?: "?").put("text", db.facts().chunkTexts(itemId).joinToString("\n")))

        val report = JSONObject().put("set", set).put("mode", mode).put("n_docs", docs.length()).put("index_seconds", indexSeconds)
        report.put("indexing", indexingStats(db, idOfItem)).put("ocr", ocrOut)

        // ---- queries ----
        // the detailed baseline section is always the original behaviour, whatever the app now ships
        val search = SearchEngine(db, embedder, SearchOptions.BASELINE)
        val answers = AnswerEngine(db, AnswerOptions.BASELINE)
        search.search("warm up")
        val out = JSONArray()
        for (qi in 0 until queries.length()) {
            val q = queries.getJSONObject(qi)
            val text = q.getString("text")
            val rec = JSONObject().put("id", q.getString("id")).put("style", q.getString("style")).put("kind", q.getString("kind")).put("intent", q.getString("intent"))
                .put("target_lang", q.opt("target_lang") ?: JSONObject.NULL).put("relevant", q.getJSONArray("relevant")).put("text", text)
            val perMode = JSONObject()
            var merged: com.munin.app.search.SearchResponse? = null
            for (m in listOf(SearchMode.KEYWORDS, SearchMode.MEANING, SearchMode.MERGED)) {
                val r = search.search(text, m, limit = 20)
                if (m == SearchMode.MERGED) merged = r
                perMode.put(m.name, JSONObject().put("ranking", JSONArray(r.results.map { idOfItem[it.itemId] ?: "?" })).put("ms", r.timings.totalMs))
            }
            rec.put("results", perMode)
            if (q.getString("kind") != "find") {
                val o = answers.answer(text, merged!!)
                val a = JSONObject()
                when (o) {
                    is AnswerOutcome.Found -> a.put("outcome", "found").put("value", o.answer.value).put("type", o.answer.kind.name).put("source", idOfItem[o.answer.source.itemId]).put("confidence", o.answer.confidence.toDouble())
                    is AnswerOutcome.Declined -> a.put("outcome", "declined").put("reason", o.reason)
                    AnswerOutcome.NotAQuestion -> a.put("outcome", "not_a_question")
                }
                rec.put("answer", a).put("expected", q.opt("answer") ?: JSONObject.NULL)
            }
            out.put(rec)
        }
        report.put("queries", out)

        // ---- variants: the same queries against the same index with each candidate fix ----
        val variantReports = JSONObject()
        for ((vname, opts) in retrievalVariants) {
            val engine = SearchEngine(db, embedder, opts)
            val rows = JSONArray()
            for (qi in 0 until queries.length()) {
                val q = queries.getJSONObject(qi); val text = q.getString("text"); val rel = q.getJSONArray("relevant")
                fun rankOf(r: com.munin.app.search.SearchResponse): Any {
                    r.results.forEachIndexed { i, x -> val id = idOfItem[x.itemId]; for (k in 0 until rel.length()) if (rel.getString(k) == id) return i + 1 }
                    return JSONObject.NULL
                }
                val kw = engine.search(text, SearchMode.KEYWORDS, limit = 20)
                val merged = engine.search(text, SearchMode.MERGED, limit = 20)
                val rec = JSONObject().put("id", q.getString("id")).put("rank", JSONObject().put("KEYWORDS", rankOf(kw)).put("MERGED", rankOf(merged))).put("merged_top3", JSONArray(merged.results.take(3).map { idOfItem[it.itemId] ?: "?" }))
                    .put("merged_ms", merged.timings.totalMs)
                if (q.getString("kind") != "find") {
                    val ans = JSONObject()
                    for ((gname, gate) in answerGates) {
                        val o = AnswerEngine(db, gate).answer(text, merged)
                        ans.put(gname, when (o) {
                            is AnswerOutcome.Found -> JSONObject().put("outcome", "found").put("value", o.answer.value).put("type", o.answer.kind.name).put("source", idOfItem[o.answer.source.itemId])
                            is AnswerOutcome.Declined -> JSONObject().put("outcome", "declined")
                            AnswerOutcome.NotAQuestion -> JSONObject().put("outcome", "not_a_question")
                        })
                    }
                    rec.put("ans", ans)
                }
                rows.put(rec)
            }
            variantReports.put(vname, rows)
        }
        report.put("variants", variantReports)

        // ---- field extraction against the manifest's ground truth ----
        val extraction = JSONArray()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i); val facts = d.getJSONObject("facts")
            if (facts.length() == 0) continue
            val itemId = itemOfDoc[d.getString("id")] ?: continue
            val found = db.facts().allForItem(itemId)
            for (type in facts.keys()) {
                val truth = facts.getString(type)
                val values = found.filter { it.name == type }.map { it.value.substringBefore('T') }
                extraction.put(JSONObject().put("doc", d.getString("id")).put("type", type).put("lang", d.getString("lang")).put("truth", truth).put("present", values.any { it == truth || (type == "ADDRESS" && it.isNotEmpty()) }).put("n_found", values.size))
            }
        }
        report.put("extraction", extraction)

        // ---- payments and ledger ----
        val payments = db.payments().allNow()
        val rows = payments.map { it.toRow() }
        val pay = JSONArray()
        for (p in payments) pay.put(JSONObject().put("doc", idOfItem[p.payment.itemId]).put("outcome", p.payment.outcome).put("direction", p.payment.direction).put("paise", p.payment.amountPaise ?: JSONObject.NULL)
            .put("conf", p.payment.amountConfidence.toDouble()).put("payee", p.payment.payee ?: JSONObject.NULL).put("date", p.payment.paidDate ?: JSONObject.NULL).put("time", p.payment.paidTime ?: JSONObject.NULL)
            .put("ref", p.payment.reference ?: JSONObject.NULL).put("problem", p.payment.problem ?: JSONObject.NULL))
        val s = LedgerCalc.summarize(rows)
        fun ids(l: List<com.munin.app.ledger.PaymentRow>) = JSONArray(l.map { idOfItem[it.itemId] })
        report.put("payments", pay).put(
            "ledger", JSONObject().put("counted", ids(s.counted)).put("needs_check", ids(s.needsCheck)).put("duplicates", JSONArray(s.duplicates.map { idOfItem[it.row.itemId] }))
                .put("not_spending", ids(s.notSpending)).put("unreadable", ids(s.unreadable)).put("excluded", ids(s.excluded))
                .put("month_paise", JSONObject().also { o -> s.months.forEach { o.put(it.month.toString(), it.totalPaise) } }).put("total_paise", s.totalPaise),
        )

        val dir = File(target.filesDir, "eval").also { it.mkdirs() } // internal storage: pulled with run-as by tools/eval/run_eval.sh
        File(dir, "report-$set-$mode.json").writeText(report.toString(1))
        Log.i("MuninEval", "mode=$mode wrote ${File(dir, "report-$set-$mode.json")} (indexed in %.0f s)".format(indexSeconds))
        assertTrue("more than 5% of documents failed to index: ${report.getJSONObject("indexing")}", report.getJSONObject("indexing").getInt("failed") <= docs.length() / 20)
        tesseract?.close()
        db.close()
    }

    /** Remembers which reader produced each image's text. */
    private class RecordingOcr(private val inner: OcrEngine) : OcrEngine {
        val engines = HashMap<String, String>()
        override suspend fun warmUp() = inner.warmUp()
        override suspend fun recognize(uri: String, rotationDegrees: Int) = inner.recognize(uri, rotationDegrees).also { engines[uri] = it.engine }
    }

    private fun lines(d: JSONObject): List<String> = (0 until d.getJSONArray("lines").length()).map { i ->
        val l = d.getJSONArray("lines").get(i)
        if (l is JSONArray) l.getString(0) else l.toString()
    }

    private suspend fun indexingStats(db: MuninDatabase, idOfItem: Map<Long, String>): JSONObject {
        val c = db.openHelper.readableDatabase.query("SELECT status, COUNT(*), AVG(ocrMs), AVG(embedMs) FROM items GROUP BY status")
        val o = JSONObject().put("indexed", 0).put("no_text", 0).put("failed", 0).put("duplicate", 0).put("pending", 0)
        c.use { while (it.moveToNext()) o.put(it.getString(0).lowercase(), it.getInt(1)) }
        val ocr = ArrayList<Long>(); val emb = ArrayList<Long>()
        db.openHelper.readableDatabase.query("SELECT ocrMs, embedMs FROM items WHERE status = 'INDEXED'").use { while (it.moveToNext()) { ocr += it.getLong(0); emb += it.getLong(1) } }
        fun med(l: List<Long>) = if (l.isEmpty()) JSONObject.NULL else l.sorted()[l.size / 2]
        return o.put("ocr_ms_median", med(ocr)).put("embed_ms_median", med(emb))
    }
}

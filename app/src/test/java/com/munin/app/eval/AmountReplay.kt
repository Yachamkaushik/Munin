package com.munin.app.eval

import com.munin.app.extract.ExtractedFact
import com.munin.app.extract.FactExtractor
import com.munin.app.extract.FactType
import java.io.File
import org.json.JSONObject

/**
 * Replays recorded OCR text (tools/eval/results) and the true text (tools/eval/data) through the amount extraction, on the JVM, with no device.
 * Used by [AmountSanityEval] and to look at why an amount came out wrong. The recorded OCR is what the phone's reader really produced.
 */
object AmountReplay {
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/eval").isDirectory }

    class Doc(val set: String, val id: String, val lang: String, val truth: String, val oracleText: String, val ocrText: String?)

    private fun prefix(set: String) = mapOf("dev" to "", "heldout" to "heldout_", "fresh" to "fresh_").getOrDefault(set, set + "_")

    /** Every document of [set] that has a true amount. [ocrText] is null when no real-OCR report exists for the set. */
    fun docs(set: String): List<Doc> {
        val manifest = JSONObject(File(root, "tools/eval/data/${prefix(set)}manifest.json").readText())
        val ocrFile = File(root, "tools/eval/results/report-$set-image.json")
        val ocr = if (ocrFile.exists()) JSONObject(ocrFile.readText()).getJSONObject("ocr") else null
        val out = ArrayList<Doc>()
        val arr = manifest.getJSONArray("docs")
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val facts = d.optJSONObject("facts") ?: continue
            val truth = facts.optString("AMOUNT", "").takeIf { it.isNotEmpty() } ?: continue
            val lines = d.getJSONArray("lines")
            val text = (0 until lines.length()).joinToString("\n") { j -> lines.get(j).let { if (it is org.json.JSONArray) it.getString(0) else it.toString() } }
            out += Doc(set, d.getString("id"), d.getString("lang"), truth, text, ocr?.optJSONObject(d.getString("id"))?.getString("text"))
        }
        return out
    }

    fun amounts(text: String): List<ExtractedFact> = extract(text, com.munin.app.extract.AmountSanity.DEFAULT)

    fun extract(text: String, mode: com.munin.app.extract.AmountSanity.Mode): List<ExtractedFact> = FactExtractor.extract(text, mode).filter { it.type == FactType.AMOUNT }
}

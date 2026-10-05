package com.munin.app.ml

import org.json.JSONArray
import org.json.JSONObject

/** Python-generated reference data (tools/reference), shared by the JVM and instrumented tests. */
class ReferenceRecord(val id: String, val prefix: String, val inputText: String, val ids: IntArray, val vector: FloatArray)

class StressRecord(val text: String, val ids: IntArray)

object ReferenceData {
    fun records(json: String): List<ReferenceRecord> {
        val arr = JSONObject(json).getJSONArray("records")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ReferenceRecord(
                o.getString("id"), o.getString("prefix"), o.getString("input_text"),
                ints(o.getJSONArray("ids")),
                o.getJSONArray("vector").let { v -> FloatArray(v.length()) { i -> v.getDouble(i).toFloat() } },
            )
        }
    }

    fun stress(json: String): List<StressRecord> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            StressRecord(o.getString("text"), ints(o.getJSONArray("ids")))
        }
    }

    private fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
}

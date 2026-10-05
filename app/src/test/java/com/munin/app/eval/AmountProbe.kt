package com.munin.app.eval

import org.junit.Test

/** Prints how the current extractor does on recorded OCR text. Run: ./gradlew :app:testDebugUnitTest --tests '*AmountProbe*' -i */
class AmountProbe {
    @Test fun probe() {
        for (set in listOf("dev", "heldout", "fresh", "ocrtest")) {
            var ok = 0; var wrong = 0; var none = 0; var tot = 0
            for (d in AmountReplay.docs(set)) {
                val txt = d.ocrText ?: continue
                tot++
                val got = AmountReplay.amounts(txt)
                when {
                    got.any { it.value == d.truth } -> ok++
                    got.isEmpty() -> { none++; if (d.lang != "te") println("PROBE none $set/${d.id} [${d.lang}] truth=${d.truth} text=" + txt.lines().filter { l -> l.any(Char::isDigit) }.joinToString(" | ")) }
                    else -> { wrong++; println("PROBE wrong $set/${d.id} [${d.lang}] truth=${d.truth} got=" + got.joinToString { "${it.value} ('${it.raw}', ${it.confidence})" }) }
                }
            }
            println("PROBE SUMMARY $set: docs=$tot correct=$ok wrong=$wrong none=$none")
        }
    }
}

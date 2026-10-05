package com.munin.app.eval

import com.munin.app.extract.AmountSanity
import com.munin.app.extract.AmountSanity.Mode
import com.munin.app.extract.ExtractedFact
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * Measures the AmountSanity modes as pre-registered in tools/eval/amount.md. Selection data: dev, heldout, fresh and ocrtest. The final set (ocrtest2) is read
 * only by [finalMeasurement], which is run once, after the mode is chosen.
 * Run: ./gradlew :app:testDebugUnitTest --tests '*AmountSanityEval.selection' -i
 */
class AmountSanityEval {
    private val CONFIDENT = 0.6f

    private enum class Outcome { CORRECT_CONFIDENT, CORRECT_FLAGGED, WRONG_CONFIDENT, WRONG_FLAGGED, NONE }

    private class Row(val doc: AmountReplay.Doc, val outcome: Outcome, val offered: Boolean, val top: ExtractedFact?)

    private fun top(facts: List<ExtractedFact>): ExtractedFact? = facts.sortedWith(compareByDescending<ExtractedFact> { it.confidence }.thenBy { it.line }).firstOrNull()

    private fun judge(d: AmountReplay.Doc, text: String, mode: Mode): Row {
        val facts = AmountReplay.extract(text, mode)
        val t = top(facts)
        val outcome = when {
            t == null -> Outcome.NONE
            t.value == d.truth -> if (t.confidence >= CONFIDENT) Outcome.CORRECT_CONFIDENT else Outcome.CORRECT_FLAGGED
            else -> if (t.confidence >= CONFIDENT) Outcome.WRONG_CONFIDENT else Outcome.WRONG_FLAGGED
        }
        return Row(d, outcome, facts.any { it.value == d.truth }, t)
    }

    private class Tally(val rows: List<Row>) {
        fun n(o: Outcome) = rows.count { it.outcome == o }
        val offered get() = rows.count { it.offered }
        val correct get() = n(Outcome.CORRECT_CONFIDENT) + n(Outcome.CORRECT_FLAGGED)
    }

    private fun docs(sets: List<String>, real: Boolean) = sets.flatMap { s -> AmountReplay.docs(s).filter { !real || it.ocrText != null } }
    private fun rows(sets: List<String>, real: Boolean, mode: Mode) = docs(sets, real).map { judge(it, if (real) it.ocrText!! else it.oracleText, mode) }

    private fun line(label: String, t: Tally, base: Tally?): String {
        val regress = if (base == null) "" else "  regressed=" + t.rows.indices.count { base.rows[it].outcome == Outcome.CORRECT_CONFIDENT && t.rows[it].outcome != Outcome.CORRECT_CONFIDENT } +
            "  falseflag=" + t.rows.indices.count { base.rows[it].outcome == Outcome.CORRECT_CONFIDENT && t.rows[it].outcome == Outcome.CORRECT_FLAGGED }
        return "%-12s docs=%d  correct-confident=%d correct-flagged=%d  WRONG-CONFIDENT=%d wrong-flagged=%d none=%d  truth-offered=%d%s".format(
            label, t.rows.size, t.n(Outcome.CORRECT_CONFIDENT), t.n(Outcome.CORRECT_FLAGGED), t.n(Outcome.WRONG_CONFIDENT), t.n(Outcome.WRONG_FLAGGED), t.n(Outcome.NONE), t.offered, regress)
    }

    private fun report(title: String, sets: List<String>, selectOn: Boolean): Mode {
        println("AMOUNT === $title: sets=$sets")
        val summary = JSONObject()
        val results = HashMap<Mode, Triple<Tally, Tally, Boolean>>()
        val baseReal = Tally(rows(sets, true, Mode.OFF)); val baseOracle = Tally(rows(sets, false, Mode.OFF))
        for (mode in Mode.entries) {
            val real = if (mode == Mode.OFF) baseReal else Tally(rows(sets, true, mode))
            val oracle = if (mode == Mode.OFF) baseOracle else Tally(rows(sets, false, mode))
            println("AMOUNT " + line("$mode real", real, baseReal))
            println("AMOUNT " + line("$mode oracle", oracle, baseOracle))
            val correctBase = baseReal.n(Outcome.CORRECT_CONFIDENT)
            val regress = real.rows.indices.count { baseReal.rows[it].outcome == Outcome.CORRECT_CONFIDENT && real.rows[it].outcome != Outcome.CORRECT_CONFIDENT } +
                oracle.rows.indices.count { baseOracle.rows[it].outcome == Outcome.CORRECT_CONFIDENT && oracle.rows[it].outcome != Outcome.CORRECT_CONFIDENT }
            val falseFlags = real.rows.indices.count { baseReal.rows[it].outcome == Outcome.CORRECT_CONFIDENT && real.rows[it].outcome == Outcome.CORRECT_FLAGGED }
            val allowed = regress == 0 || mode == Mode.OFF
            results[mode] = Triple(real, oracle, allowed && falseFlags <= correctBase * 0.01)
            summary.put(mode.name, JSONObject().put("real_wrong_confident", real.n(Outcome.WRONG_CONFIDENT)).put("real_wrong_flagged", real.n(Outcome.WRONG_FLAGGED)).put("real_correct", real.correct)
                .put("real_none", real.n(Outcome.NONE)).put("real_truth_offered", real.offered).put("regressions", regress).put("false_flags", falseFlags).put("docs", real.rows.size))
        }
        // rule 1 then ties, among the modes that satisfy rules 2 and 3
        val eligible = Mode.entries.filter { results.getValue(it).third }
        val chosen = eligible.minWith(compareBy<Mode>({ results.getValue(it).first.n(Outcome.WRONG_CONFIDENT) }, { results.getValue(it).first.n(Outcome.WRONG_CONFIDENT) + results.getValue(it).first.n(Outcome.WRONG_FLAGGED) }, { -results.getValue(it).first.correct }, { it.ordinal }))
        println("AMOUNT eligible=$eligible  ${if (selectOn) "SELECTED" else "best"}=$chosen")
        File(AmountReplay.root, "tools/eval/results/amount-${if (selectOn) "selection" else "final"}.json").writeText(summary.put("sets", sets.joinToString(",")).put("chosen", chosen.name).toString(1))
        if (!selectOn) for (m in listOf(Mode.OFF, chosen)) rows(sets, true, m).filter { it.outcome == Outcome.WRONG_CONFIDENT }.forEach { println("AMOUNT final $m wrong-confident ${it.doc.id} truth=${it.doc.truth} top=${it.top?.value} raw='${it.top?.raw}'") }
        return chosen
    }

    @Test fun selection() { report("selection", listOf("dev", "heldout", "fresh", "ocrtest"), true) }

    /** Run once, after [selection] and after the mode has been set as the default. Reports OFF against that mode on the untouched set. */
    @Test fun finalMeasurement() { report("final (untouched set)", listOf("ocrtest2"), false) }
}

"""Turns raw harness results (tools/eval/results/report-*.json) into metrics, docs/EVALUATION.md and docs/eval_retrieval.png.

Metrics are computed here, from the raw rankings, so the arithmetic is easy to audit:
  recall@k = share of queries whose correct document is in the top k;  MRR = mean of 1/rank (0 if not in the top 20).
"""
import json, pathlib, statistics, collections, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
RES = ROOT / "tools/eval/results"
DOCS = ROOT / "docs"
manifest = json.load(open(ROOT / "tools/eval/data/manifest.json"))
doc_by_id = {d["id"]: d for d in manifest["docs"]}
CONFIGS = ["KEYWORDS", "MEANING", "MERGED"]
CONFIG_LABEL = {"KEYWORDS": "Keywords only", "MEANING": "Meaning only (embeddings)", "MERGED": "Merged (RRF)"}
STYLES = ["en", "te", "hi", "rt", "mix"]
STYLE_LABEL = {"en": "English", "te": "Telugu script", "hi": "Hindi", "rt": "Roman Telugu", "mix": "Mixed"}
COLORS = {"KEYWORDS": "#2a78d6", "MEANING": "#eb6834", "MERGED": "#1baf7a"}  # validated categorical slots 1-3 (see README)


def rank_of(q, cfg):
    ranking = q["results"][cfg]["ranking"]
    for i, d in enumerate(ranking, 1):
        if d in q["relevant"]: return i
    return None

def metrics(qs, cfg):
    ranks = [rank_of(q, cfg) for q in qs]; n = len(qs)
    return {"n": n, "r1": sum(r == 1 for r in ranks) / n, "r5": sum(r is not None and r <= 5 for r in ranks) / n,
            "mrr": sum(1 / r for r in ranks if r) / n}

def pct(x): return f"{100 * x:.0f}%"
def pct1(x): return f"{100 * x:.1f}%"
def pctile(vals, p): s = sorted(vals); return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]

def table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "|".join("---" if i == 0 else "---:" for i in range(len(header))) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def analyse(rep):
    qs = rep["queries"]; finds = [q for q in qs if q["kind"] != "negative"]
    res = {"mode": rep["mode"], "indexing": rep["indexing"], "index_seconds": rep["index_seconds"]}
    res["retrieval"] = {cfg: {"all": metrics(finds, cfg), **{s: metrics([q for q in finds if q["style"] == s], cfg) for s in STYLES},
                              **{"target_" + l: metrics([q for q in finds if q["target_lang"] == l], cfg) for l in ("en", "hi", "te")}} for cfg in CONFIGS}
    res["latency"] = {cfg: {"median": statistics.median(q["results"][cfg]["ms"] for q in qs), "p95": pctile([q["results"][cfg]["ms"] for q in qs], 95)} for cfg in CONFIGS}

    # value questions: end-to-end extraction accuracy
    vals = [q for q in qs if q["kind"] == "value"]; negs = [q for q in qs if q["kind"] == "negative"]
    def outcome(q):
        a = q["answer"]
        if a["outcome"] == "found": return "correct" if (a["value"] == q["expected"]["value"] and a["type"] == q["expected"]["type"] and a["source"] in q["relevant"]) else "wrong"
        return a["outcome"]
    cnt = collections.Counter(outcome(q) for q in vals)
    res["value"] = {"n": len(vals), "counts": dict(cnt), "by_style": {s: collections.Counter(outcome(q) for q in vals if q["style"] == s) for s in STYLES},
                    "by_target": {l: collections.Counter(outcome(q) for q in vals if q["target_lang"] == l) for l in ("en", "hi", "te")},
                    "wrong": [(q["id"], q["text"], q["answer"]) for q in vals if outcome(q) == "wrong"]}
    res["negative"] = {"n": len(negs), "answered": [(q["id"], q["text"], q["answer"]) for q in negs if q["answer"]["outcome"] == "found"]}

    # field-level extraction against ground truth
    ex = collections.defaultdict(lambda: [0, 0]); ex_lang = collections.defaultdict(lambda: [0, 0])
    for e in rep["extraction"]:
        ex[e["type"]][0] += e["present"]; ex[e["type"]][1] += 1
        ex_lang[e["lang"]][0] += e["present"]; ex_lang[e["lang"]][1] += 1
    res["extraction"] = {t: v for t, v in ex.items()}; res["extraction_lang"] = {t: v for t, v in ex_lang.items()}

    # ledger
    upi = {d["id"]: d for d in manifest["docs"] if d["type"] == "upi_payment"}
    pay = {p["doc"]: p for p in rep["payments"]}
    det = [d for d in upi if d in pay]
    field = {"amount": 0, "date": 0, "payee": 0, "ref": 0}
    for d in det:
        u, p = upi[d]["upi"], pay[d]
        field["amount"] += p["paise"] == u["paise"]; field["date"] += p["date"] == u["date"]; field["payee"] += p["payee"] == u["payee"]; field["ref"] += p["ref"] == u["ref"]
    led = rep["ledger"]; counted = led["counted"]
    true_paid = {}
    for d, x in upi.items():
        u = x["upi"]
        if u["outcome"] == "SUCCESS" and u["direction"] == "PAID" and u["ref"] not in true_paid: true_paid[u["ref"]] = (d, u)
    truth_by_month = collections.Counter()
    for _, u in true_paid.values(): truth_by_month[u["date"][:7]] += u["paise"]
    counted_refs = {upi[d]["upi"]["ref"] for d in counted if d in upi}
    wrong_counted = [d for d in counted if d in upi and pay[d]["paise"] != upi[d]["upi"]["paise"]]
    counted_true_by_month = collections.Counter()
    for d in counted:
        if d in upi: counted_true_by_month[upi[d]["upi"]["date"][:7]] += upi[d]["upi"]["paise"]
    res["ledger"] = {"upi_docs": len(upi), "detected": len(det), "false_positive_docs": [d for d in rep["payments"] if d["doc"] not in upi], "fields": field,
                     "true_payments": len(true_paid), "counted": len(counted), "counted_true_refs": len(counted_refs & set(true_paid)), "needs_check": len(led["needs_check"]),
                     "duplicates": len(led["duplicates"]), "not_spending": len(led["not_spending"]), "unreadable": len(led["unreadable"]), "wrong_counted": wrong_counted,
                     "month_paise": led["month_paise"], "truth_month_paise": dict(sorted(truth_by_month.items())), "counted_true_month_paise": dict(sorted(counted_true_by_month.items()))}
    res["intent_table"] = intent_table(rep)
    res["failures"] = [{"id": q["id"], "style": q["style"], "text": q["text"], "rank": {c: rank_of(q, c) for c in CONFIGS}, "top3": q["results"]["MERGED"]["ranking"][:3], "target": q["relevant"][0]}
                       for q in finds if (rank_of(q, "MERGED") or 99) > 1]
    return res


INTENTS = {
    "A1": "Sai Vidya hostel fee amount (English receipt)", "A2": "electricity bill due 15 Oct (English bill)", "A3": "flight to Visakhapatnam (English ticket)",
    "A4": "water bill due date (English bill)", "A5": "organic chemistry SN1/SN2 notes (English)", "A6": "Dr Meera Rao clinic phone (English card)",
    "A7": "payment to Lakshmi Tiffins (English UPI)", "A8": "December exam timetable, operating systems (English)", "A9": "Saraswati Vidya Mandir school fee (HINDI receipt)",
    "A10": "Mughal empire / Akbar history notes (HINDI)", "A11": "Sri Chaitanya junior college fee (TELUGU receipt)",
}

def intent_table(rep):
    qs = [q for q in rep["queries"] if q["kind"] != "negative"]; rows = []
    for i, desc in INTENTS.items():
        cells = []
        for st in STYLES:
            q = next(q for q in qs if q["intent"] == i and q["style"] == st)
            r = {c: rank_of(q, c) for c in CONFIGS}
            cells.append(f"{r['MERGED'] or '-'} ({r['MEANING'] or '-'}, {r['KEYWORDS'] or '-'})")
        rows.append([i, desc] + cells)
    return table(["Intent", "Looking for"] + [STYLE_LABEL[s] for s in STYLES], rows)


def money(p): return f"₹{p / 100:,.2f}"

def render(a, label):
    L = []
    L.append(f"### {label}\n")
    ix = a["indexing"]
    L.append(f"Indexed {ix['indexed']} of 300 documents ({ix['no_text']} with no text found, {ix['duplicate']} duplicates, {ix['failed']} failed) in {a['index_seconds']:.0f} s"
             + (f"; median OCR {ix['ocr_ms_median']} ms and embedding {ix['embed_ms_median']} ms per document." if a["mode"] == "image" else "; this mode uses the true text, so there is no OCR step."))
    L.append("\n**Retrieval** (55 find/value queries; the 5 negative queries are scored separately). Recall@1 / recall@5 / MRR:\n")
    rows = []
    for s in ["all"] + STYLES:
        lbl = "All styles" if s == "all" else STYLE_LABEL[s]
        rows.append([lbl, a["retrieval"]["KEYWORDS"][s]["n"]] + [f"{pct(a['retrieval'][c][s]['r1'])} / {pct(a['retrieval'][c][s]['r5'])} / {a['retrieval'][c][s]['mrr']:.2f}" for c in CONFIGS])
    L.append(table(["Query style", "n"] + [CONFIG_LABEL[c] for c in CONFIGS], rows))
    L.append("\nBy the language of the *document* being looked for:\n")
    rows = [[{"en": "English documents", "hi": "Hindi documents", "te": "Telugu documents"}[l], a["retrieval"]["KEYWORDS"]["target_" + l]["n"]] +
            [f"{pct(a['retrieval'][c]['target_' + l]['r1'])} / {pct(a['retrieval'][c]['target_' + l]['r5'])} / {a['retrieval'][c]['target_' + l]['mrr']:.2f}" for c in CONFIGS] for l in ("en", "hi", "te")]
    L.append(table(["Looking for", "n"] + [CONFIG_LABEL[c] for c in CONFIGS], rows))
    v = a["value"]; c = v["counts"]
    L.append(f"\n**Value questions** (amount / date / phone, {v['n']} queries): " + f"{c.get('correct', 0)} correct, {c.get('wrong', 0)} wrong, {c.get('declined', 0)} declined (no answer shown), {c.get('not_a_question', 0)} not recognised as a question. "
             f"Accuracy {pct(c.get('correct', 0) / v['n'])}; when an answer was shown it was right {pct(c.get('correct', 0) / max(1, c.get('correct', 0) + c.get('wrong', 0)))} of the time.\n")
    rows = [[STYLE_LABEL[s]] + [v["by_style"][s].get(k, 0) for k in ("correct", "wrong", "declined", "not_a_question")] for s in STYLES]
    L.append(table(["Style", "correct", "wrong", "declined", "not a question"], rows))
    if v["wrong"]: L.append("\nWrong answers shown: " + "; ".join(f"{i} \"{t}\" -> {x.get('type')} {x.get('value')} from {x.get('source')}" for i, t, x in v["wrong"]))
    ng = a["negative"]
    L.append(f"\n**Questions that should get no answer** (\"how much was the car insurance\" in 5 styles; no such document exists): {len(ng['answered'])} of {ng['n']} wrongly answered"
             + ("" if not ng["answered"] else ": " + "; ".join(f"{t} -> {x['value']} from {x['source']}" for _, t, x in ng["answered"])) + ".")
    L.append("\n**Field extraction** (is the true value among the facts extracted from the document?):\n")
    L.append(table(["Field", "found / documents", "rate"], [[t, f"{x[0]} / {x[1]}", pct(x[0] / x[1])] for t, x in sorted(a["extraction"].items())]))
    L.append("\n" + table(["Document language", "found / fields", "rate"], [[{"en": "English", "hi": "Hindi", "te": "Telugu"}[t], f"{x[0]} / {x[1]}", pct(x[0] / x[1])] for t, x in sorted(a["extraction_lang"].items())]))
    g = a["ledger"]
    L.append(f"\n**Payment ledger** ({g['upi_docs']} synthetic UPI screenshots: {g['true_payments']} distinct successful payments you made, plus duplicates, failed, pending and received ones):\n")
    L.append(f"- Recognised as payments: {g['detected']} / {g['upi_docs']}; non-payment documents wrongly recognised: {len(g['false_positive_docs'])}.")
    L.append(f"- Fields read exactly (of {g['detected']}): amount {g['fields']['amount']}, date {g['fields']['date']}, payee {g['fields']['payee']}, reference {g['fields']['ref']}.")
    L.append(f"- Counted in totals: {g['counted']} (of which {g['counted_true_refs']} are genuine distinct payments); held back as 'needs a check': {g['needs_check']}; duplicates: {g['duplicates']}; failed/pending/received: {g['not_spending']}; unreadable: {g['unreadable']}.")
    L.append(f"- Counted payments with a wrong amount: {len(g['wrong_counted'])}" + ("" if not g["wrong_counted"] else " (" + ", ".join(g["wrong_counted"]) + ")") + ".\n")
    months = sorted(set(g["truth_month_paise"]) | set(g["month_paise"]))
    rows = []
    for m in months:
        t = g["truth_month_paise"].get(m, 0); got = g["month_paise"].get(m, 0); ct = g["counted_true_month_paise"].get(m, 0)
        rows.append([m, money(t), money(got), money(ct), "exact" if got == ct else f"{money(got - ct)} off", pct1(got / t) if t else "-"])
    L.append(table(["Month", "True total", "Ledger total", "True sum of what was counted", "Ledger vs that sum", "Share of true total counted"], rows))
    L.append("\n**Search latency** (end to end, emulator): " + "; ".join(f"{CONFIG_LABEL[c]}: median {a['latency'][c]['median']:.0f} ms, p95 {a['latency'][c]['p95']:.0f} ms" for c in CONFIGS) + ".\n")
    L.append("**Rank of the correct document per query intent**, written `merged (meaning-only, keywords-only)`; `-` means not in the top 20; 1 is best:\n")
    L.append(a["intent_table"] + "\n")
    fl = a["failures"]
    L.append(f"**Queries where the merged ranking did not put the right document first** ({len(fl)} of 55):\n")
    L.append(table(["Query", "Style", "Text", "Rank: keywords / meaning / merged", "Merged top 3", "Target"],
                   [[f["id"], f["style"], f["text"], " / ".join(str(f["rank"][c] or "-") for c in CONFIGS), ", ".join(f["top3"]), f["target"]] for f in fl]))
    return "\n".join(L)


def chart(analyses):
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(len(analyses), 2, figsize=(13, 4.2 * len(analyses)), squeeze=False)
    for row, a in enumerate(analyses):
        for col, (key, title) in enumerate([("r1", "Recall@1: right document first"), ("r5", "Recall@5: right document in the top 5")]):
            ax = axes[row][col]; w = 0.26
            for i, c in enumerate(CONFIGS):
                xs = [j + (i - 1) * w for j in range(len(STYLES))]; vals = [a["retrieval"][c][s][key] for s in STYLES]
                bars = ax.bar(xs, vals, w * 0.9, color=COLORS[c], label=CONFIG_LABEL[c])
                for b, vv in zip(bars, vals): ax.text(b.get_x() + b.get_width() / 2, vv + 0.015, f"{100 * vv:.0f}", ha="center", va="bottom", fontsize=8, color="#0b0b0b")
            ax.set_xticks(range(len(STYLES))); ax.set_xticklabels([STYLE_LABEL[s] for s in STYLES], color="#52514e"); ax.set_ylim(0, 1.12); ax.set_yticks([0, .5, 1]); ax.set_yticklabels(["0%", "50%", "100%"], color="#52514e")
            ax.set_title(f"{'Perfect text' if a['mode'] == 'oracle' else 'Real OCR on images'}: {title}", fontsize=11, loc="left", color="#0b0b0b")
            for sp in ("top", "right"): ax.spines[sp].set_visible(False)
            ax.spines["left"].set_color("#c8c7c0"); ax.spines["bottom"].set_color("#c8c7c0"); ax.tick_params(colors="#52514e")
            ax.grid(axis="y", color="#e6e5df", linewidth=0.8); ax.set_axisbelow(True)
    h, l = axes[0][0].get_legend_handles_labels()
    fig.legend(h, l, loc="lower center", ncol=3, frameon=False, fontsize=10)
    fig.tight_layout(rect=(0, 0.05, 1, 1)); DOCS.mkdir(exist_ok=True); fig.savefig(DOCS / "eval_retrieval.png", dpi=140); plt.close(fig)


def corpus_summary():
    langs = collections.Counter(d["lang"] for d in manifest["docs"]); types = collections.Counter(d["type"] for d in manifest["docs"])
    return (f"{len(manifest['docs'])} synthetic documents: {langs['en']} English, {langs['hi']} Hindi, {langs['te']} Telugu. "
            + ", ".join(f"{n} {t.replace('_', ' ')}" for t, n in types.most_common()) + ".")

def headline(analyses):
    rows = []
    for a in analyses:
        for c in CONFIGS:
            m = a["retrieval"][c]["all"]
            rows.append([("Perfect text" if a["mode"] == "oracle" else "Real OCR"), CONFIG_LABEL[c], pct(m["r1"]), pct(m["r5"]), f"{m['mrr']:.2f}", f"{a['latency'][c]['median']:.0f} ms"])
    t = table(["Mode", "Configuration", "Recall@1", "Recall@5", "MRR", "Median search time"], rows)
    ex = []
    for a in analyses:
        v = a["value"]["counts"]; g = a["ledger"]
        ex.append([("Perfect text" if a["mode"] == "oracle" else "Real OCR"), f"{v.get('correct', 0)} / {a['value']['n']} correct ({v.get('wrong', 0)} wrong shown)", f"{len(a['negative']['answered'])} / {a['negative']['n']}",
                   f"{sum(x[0] for x in a['extraction'].values())} / {sum(x[1] for x in a['extraction'].values())}", f"{g['counted']} of {g['true_payments']} counted, {len(g['wrong_counted'])} wrong amounts"])
    t2 = table(["Mode", "Value questions", "Wrongly answered when no document exists", "Document fields extracted (truth present)", "Ledger"], ex)
    return t + "\n\n" + t2

def main():
    reps = [json.load(open(p)) for p in sorted(RES.glob("report-*.json"), key=lambda p: (p.stem != "report-oracle", p.stem))]
    if not reps: sys.exit("no results in tools/eval/results; run tools/eval/run_eval.sh")
    analyses = [analyse(r) for r in reps]
    names = {"oracle": "Mode 1: perfect text (isolates retrieval and extraction from OCR)", "image": "Mode 2: real OCR on the rendered images (end to end)"}
    body = "\n\n".join(render(a, names[a["mode"]]) for a in analyses)
    DOCS.mkdir(exist_ok=True)
    intro = (ROOT / "tools/eval/intro.md").read_text().replace("{{CORPUS}}", corpus_summary()).replace("{{HEADLINE}}", headline(analyses))
    (DOCS / "EVALUATION.md").write_text(intro + "\n\n---\n\n## Detailed results (generated by tools/eval/report.py)\n\n![Recall by query style](eval_retrieval.png)\n\n" + body + "\n")
    chart(analyses)
    print(body)

main()

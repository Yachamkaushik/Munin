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

# ------------------------------------------------------------------------------------------- fix experiments
VARIANTS = {"B0": "baseline (as shipped)", "S1": "+ Hindi/Telugu/Roman stopwords", "C1": "+ keyword coverage gate (>= half the query words)",
            "S2": "stopwords + coverage gate", "S3": "stopwords + coverage gate + keyword weight 0.5"}
GATES = {"A0": "original gate", "A1": "grounding (every rare question word must be in the document)", "A2": "A1, topic without Hindi/Telugu/Roman function words",
         "A3": "A2, topic also without generic payment verbs (pay, spend, भरना, కట్టాను ...)"}

def same_language(q):
    t = q["target_lang"]
    return (q["style"] in ("en", "rt", "mix") and t == "en") or (q["style"] == "hi" and t == "hi") or (q["style"] == "te" and t == "te")

def variant_stats(rep, vname):
    byid = {q["id"]: q for q in rep["queries"]}; rows = rep["variants"][vname]
    fv = [r for r in rows if byid[r["id"]]["kind"] != "negative"]
    def m(rank_of_row, sel):
        ranks = [rank_of_row(r) for r in sel]; n = len(sel)
        return {"n": n, "r1": sum(x == 1 for x in ranks) / n, "r5": sum(x is not None and x <= 5 for x in ranks) / n, "mrr": sum(1 / x for x in ranks if x) / n}
    merged = lambda r: r["rank"]["MERGED"]; kw = lambda r: r["rank"]["KEYWORDS"]
    out = {"merged": m(merged, fv), "keywords": m(kw, fv), "merged_style": {st: m(merged, [r for r in fv if byid[r["id"]]["style"] == st]) for st in STYLES}}
    same = [r for r in fv if same_language(byid[r["id"]])]; cross = [r for r in fv if not same_language(byid[r["id"]])]
    out["same_r1"] = (sum(merged(r) == 1 for r in same), len(same)); out["cross"] = m(merged, cross); out["same"] = m(merged, same)
    out["gates"] = {}
    have = set.intersection(*[set(r["ans"]) for r in rows if "ans" in r])  # gates this run actually measured (older reports lack A2/A3)
    for g in [g for g in GATES if g in have]:
        cnt = collections.Counter(); neg = 0
        for r in rows:
            q = byid[r["id"]]
            if q["kind"] == "find": continue
            a = r["ans"][g]
            if q["kind"] == "negative": neg += a["outcome"] == "found"; continue
            if a["outcome"] == "found": cnt["correct" if (a["value"] == q["expected"]["value"] and a["type"] == q["expected"]["type"] and a["source"] in q["relevant"]) else "wrong"] += 1
            else: cnt[a["outcome"]] += 1
        out["gates"][g] = {"correct": cnt["correct"], "wrong": cnt["wrong"], "declined": cnt["declined"], "negatives_answered": neg, "n_value": sum(q["kind"] == "value" for q in byid.values()), "n_neg": sum(q["kind"] == "negative" for q in byid.values())}
    out["latency_ms"] = statistics.median(r["merged_ms"] for r in rows)
    return out

def select(dev_reps):
    """Pre-declared rule: highest mean merged MRR over the dev modes, but not losing more than one same-language rank-1 vs B0;
    answer gate: fewest (wrong answers + false answers to no-answer questions), ties to more correct answers."""
    stats = {v: [variant_stats(r, v) for r in dev_reps] for v in VARIANTS}
    base_same = sum(x["same_r1"][0] for x in stats["B0"])
    eligible = {v: sum(x["merged"]["mrr"] for x in st) / len(st) for v, st in stats.items() if sum(x["same_r1"][0] for x in st) >= base_same - len(st)}
    best = max(eligible, key=lambda v: (round(eligible[v], 6), -list(VARIANTS).index(v)))
    gate_score = {g: (sum(x["gates"][g]["wrong"] + x["gates"][g]["negatives_answered"] for x in stats[best]), -sum(x["gates"][g]["correct"] for x in stats[best])) for g in ("A0", "A1")}
    gate = min(gate_score, key=lambda g: (gate_score[g], list(gate_score).index(g)))
    return best, gate, stats

def select_gate2(seen_reps, retrieval="C1"):
    """Round 2 rule, written before any fresh-set result existed. Over the already-seen sets (dev and held-out, both modes), with the shipped retrieval
    variant, among the grounding gates A1-A3: fewest (wrong answers + made-up answers to no-answer questions), then most correct answers, then the simpler gate."""
    cand = ("A1", "A2", "A3")
    tot = {g: [0, 0, 0] for g in cand}
    for rep in seen_reps:
        x = variant_stats(rep, retrieval)["gates"]
        for g in cand: tot[g][0] += x[g]["wrong"] + x[g]["negatives_answered"]; tot[g][1] += x[g]["correct"]; tot[g][2] += x[g]["n_value"]
    best = min(cand, key=lambda g: (tot[g][0], -tot[g][1], cand.index(g)))
    return best, tot

def variant_section(reps, title, best=None, gate=None):
    L = [f"#### {title}\n"]
    for rep in reps:
        mode = "Perfect text" if rep["mode"] == "oracle" else "Real OCR"
        meaning = {"r1": 0, "r5": 0, "mrr": 0}; qs = [q for q in rep["queries"] if q["kind"] != "negative"]
        for q in qs:
            r = next((i for i, d in enumerate(q["results"]["MEANING"]["ranking"], 1) if d in q["relevant"]), None)
            meaning["r1"] += r == 1; meaning["r5"] += bool(r and r <= 5); meaning["mrr"] += 1 / r if r else 0
        n = len(qs); rows = []
        for v, desc in VARIANTS.items():
            st = variant_stats(rep, v)
            flag = " **(selected on dev)**" if v == best else ""
            rows.append([f"{v}: {desc}{flag}", f"{pct(st['merged']['r1'])} / {pct(st['merged']['r5'])} / {st['merged']['mrr']:.2f}", f"{pct(st['same']['r1'])} / {pct(st['same']['r5'])}", f"{pct(st['cross']['r1'])} / {pct(st['cross']['r5'])}",
                         f"{pct(st['keywords']['r1'])} / {pct(st['keywords']['r5'])}"])
        rows.append(["Meaning only (reference, unchanged)", f"{pct(meaning['r1'] / n)} / {pct(meaning['r5'] / n)} / {meaning['mrr'] / n:.2f}", "", "", ""])
        L.append(f"**{mode}**\n")
        L.append(table(["Variant", "Merged R@1 / R@5 / MRR", f"Same-language ({st['same']['n']}) R@1 / R@5", f"Cross-language ({st['cross']['n']}) R@1 / R@5", "Keywords-only R@1 / R@5"], rows) + "\n")
    return "\n".join(L)

def gate_section(reps, title, selected=None, retrieval="C1"):
    L = [f"#### {title}\n"]
    for rep in reps:
        mode = "Perfect text" if rep["mode"] == "oracle" else "Real OCR"; rows = []
        vs = variant_stats(rep, retrieval)
        for g, desc in GATES.items():
            if g not in vs["gates"]: continue
            x = vs["gates"][g]
            rows.append([f"{g}: {desc}" + (" **(selected)**" if g == selected else ""), f"{x['correct']} / {x['n_value']}", x["wrong"], x["declined"], f"{x['negatives_answered']} / {x['n_neg']}"])
        L.append(f"**{mode}** (shipped retrieval {retrieval})\n")
        L.append(table(["Answer gate", "Correct", "Wrong answer shown", "Declined", "Made-up answers to no-answer questions"], rows) + "\n")
    return "\n".join(L)

def fix_chart(by_set, best):
    import matplotlib; matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    sets = [s for s in ("dev", "heldout") if s in by_set]; modes = ["oracle", "image"]
    fig, axes = plt.subplots(len(modes), len(sets), figsize=(6.6 * len(sets), 4.0 * len(modes)), squeeze=False)
    series = [("Meaning only", "#eb6834"), ("Merged, as shipped (B0)", "#1baf7a"), (f"Merged, fixed ({best})", "#eda100")]
    for ri, mode in enumerate(modes):
        for ci, st in enumerate(sets):
            ax = axes[ri][ci]; rep = by_set[st].get(mode)
            if rep is None: ax.axis("off"); continue
            vals = []
            for si, (lbl, col) in enumerate(series):
                if si == 0:
                    v = []
                    for sty in STYLES:
                        qs = [q for q in rep["queries"] if q["kind"] != "negative" and q["style"] == sty]
                        v.append(sum(any(d in q["relevant"] for d in q["results"]["MEANING"]["ranking"][:5]) for q in qs) / len(qs))
                else:
                    name = "B0" if si == 1 else best; v = [variant_stats(rep, name)["merged_style"][sty]["r5"] for sty in STYLES]
                vals.append(v)
            w = 0.26
            for si, (lbl, col) in enumerate(series):
                xs = [j + (si - 1) * w for j in range(len(STYLES))]; bars = ax.bar(xs, vals[si], w * 0.9, color=col, label=lbl)
                for b, vv in zip(bars, vals[si]): ax.text(b.get_x() + b.get_width() / 2, vv + 0.015, f"{100 * vv:.0f}", ha="center", va="bottom", fontsize=8, color="#0b0b0b")
            ax.set_xticks(range(len(STYLES))); ax.set_xticklabels([STYLE_LABEL[x] for x in STYLES], fontsize=8, color="#52514e"); ax.set_ylim(0, 1.12); ax.set_yticks([0, .5, 1]); ax.set_yticklabels(["0%", "50%", "100%"], color="#52514e")
            ax.set_title(f"{'Dev set' if st == 'dev' else 'Held-out set'}, {'perfect text' if mode == 'oracle' else 'real OCR'}: recall@5", fontsize=10, loc="left", color="#0b0b0b")
            for sp in ("top", "right"): ax.spines[sp].set_visible(False)
            ax.spines["left"].set_color("#c8c7c0"); ax.spines["bottom"].set_color("#c8c7c0"); ax.grid(axis="y", color="#e6e5df", linewidth=0.8); ax.set_axisbelow(True)
    h, l = axes[0][0].get_legend_handles_labels(); fig.legend(h, l, loc="lower center", ncol=3, frameon=False, fontsize=10)
    fig.tight_layout(rect=(0, 0.05, 1, 1)); fig.savefig(DOCS / "eval_fixes.png", dpi=140); plt.close(fig)


# ------------------------------------------------------------------------------------------- Telugu OCR
POLICIES = {"image": "T0: ML Kit only (original)", "image_t1": "T1: Tesseract Telugu when ML Kit is doubtful", "image_t2": "T2: Tesseract Telugu on every image"}
FOOTER = "SYNTHETIC SAMPLE - not a real document"
_manifests = {}

def set_manifest(st):
    if st not in _manifests:
        pre = {"dev": "", "heldout": "heldout_", "fresh": "fresh_"}.get(st, st + "_")
        _manifests[st] = json.load(open(ROOT / f"tools/eval/data/{pre}manifest.json"))
    return _manifests[st]

def _clean(t):
    return " ".join(t.replace("\u200c", "").replace("\u200d", "").split())

def ocr_stats(rep):
    """Character error rate of the OCR text against the document's true text, by document language, plus field extraction by language."""
    from rapidfuzz.distance import Levenshtein
    man = set_manifest(rep["set"]); docs = {d["id"]: d for d in man["docs"]}
    cer = collections.defaultdict(list); used = 0
    for did, o in rep["ocr"].items():
        d = docs[did]; truth = _clean("\n".join(l if isinstance(l, str) else l[0] for l in d["lines"]) + "\n" + FOOTER)
        cer[d["lang"]].append(min(1.0, Levenshtein.distance(_clean(o["text"]), truth) / max(1, len(truth))))
        used += o["engine"].startswith("tesseract")
    ex = collections.defaultdict(lambda: [0, 0])
    for e in rep["extraction"]: ex[e["lang"]][0] += e["present"]; ex[e["lang"]][1] += 1
    out = {"cer": {l: statistics.mean(v) for l, v in cer.items()}, "cer_n": {l: len(v) for l, v in cer.items()}, "fields": dict(ex), "tess_share": used / len(rep["ocr"]),
           "ocr_ms": rep["indexing"]["ocr_ms_median"]}
    if "variants" in rep and rep["queries"]:
        v = variant_stats(rep, "C1"); out["mrr"] = v["merged"]["mrr"]; out["r5"] = v["merged"]["r5"]
        te = [q for q in rep["queries"] if q["kind"] != "negative" and q["target_lang"] == "te"]
        rows = {r["id"]: r for r in rep["variants"]["C1"]}
        out["te_r5"] = (sum(rows[q["id"]]["rank"]["MERGED"] is not None and rows[q["id"]]["rank"]["MERGED"] <= 5 for q in te), len(te))
    return out

def select_telugu(by_set, seen=("dev", "heldout", "fresh")):
    """Rule written before any Tesseract result existed. Pooled over the seen sets, a policy is eligible if (a) Telugu-document field extraction rises by at least
    20 points over T0, (b) English+Hindi field extraction falls by at most 1 point, (c) mean merged MRR falls by at most 0.01. Among eligible policies the highest
    Telugu extraction wins, ties to the faster; if none is eligible the switch stays off by default."""
    agg = {}
    for mode in POLICIES:
        te = [0, 0]; oth = [0, 0]; mrr = []
        for st in seen:
            rep = by_set.get(st, {}).get(mode)
            if rep is None: return None, {}
            o = ocr_stats(rep)
            te[0] += o["fields"].get("te", [0, 0])[0]; te[1] += o["fields"].get("te", [0, 0])[1]
            for l in ("en", "hi"): oth[0] += o["fields"].get(l, [0, 0])[0]; oth[1] += o["fields"].get(l, [0, 0])[1]
            if "mrr" in o: mrr.append(o["mrr"])
        agg[mode] = {"te": te[0] / te[1], "other": oth[0] / oth[1], "mrr": statistics.mean(mrr)}
    base = agg["image"]; eligible = []
    for mode in ("image_t1", "image_t2"):
        a = agg[mode]
        a["ok_a"] = a["te"] >= base["te"] + 0.20; a["ok_b"] = a["other"] >= base["other"] - 0.01; a["ok_c"] = a["mrr"] >= base["mrr"] - 0.01
        if a["ok_a"] and a["ok_b"] and a["ok_c"]: eligible.append(mode)
    choice = max(eligible, key=lambda m: (round(agg[m]["te"], 6), -list(POLICIES).index(m))) if eligible else None
    return choice, agg

def telugu_table(by_set, sets, title):
    L = [f"#### {title}\n"]; rows = []
    for st in sets:
        for mode, desc in POLICIES.items():
            rep = by_set.get(st, {}).get(mode)
            if rep is None: continue
            o = ocr_stats(rep); f = o["fields"]
            fr = lambda l: (f"{f[l][0]} / {f[l][1]} ({pct(f[l][0] / f[l][1])})" if l in f else "-")
            rows.append([st, desc, f"{pct1(o['cer'].get('te', 0))}", f"{pct1(o['cer'].get('hi', 0))}", f"{pct1(o['cer'].get('en', 0))}", fr("te"), fr("hi"), fr("en"),
                         (f"{o['mrr']:.2f}" if "mrr" in o else "-"), (f"{o['te_r5'][0]} / {o['te_r5'][1]}" if "te_r5" in o else "-"), f"{o['ocr_ms']} ms", pct(o["tess_share"])])
    L.append(table(["Set", "OCR policy", "CER Telugu docs", "CER Hindi", "CER English", "Fields found, Telugu docs", "Hindi docs", "English docs", "Merged MRR", "Telugu-target queries in top 5",
                    "Median OCR time", "Docs read by Tesseract"], rows))
    return "\n".join(L) + "\n"

def load_reports():
    by_set = collections.defaultdict(dict)
    for p in sorted(RES.glob("report-*-*.json")):
        _, st, mode = p.stem.split("-", 2); by_set[st][mode] = json.load(open(p))
    return by_set

def main():
    by_set = load_reports()
    if "dev" not in by_set: sys.exit("no dev results in tools/eval/results; run tools/eval/run_eval.sh")
    dev = [by_set["dev"][m] for m in ("oracle", "image") if m in by_set["dev"]]
    names = {"oracle": "Mode 1: perfect text (isolates retrieval and extraction from OCR)", "image": "Mode 2: real OCR on the rendered images (end to end)"}
    analyses = [analyse(r) for r in dev]
    DOCS.mkdir(exist_ok=True)
    body = "\n\n".join(render(a, names[a["mode"]]) for a in analyses)
    chart(analyses)
    fixes = ""
    if all("variants" in r for r in dev):
        best, gate, stats = select(dev)
        fixes += variant_section(dev, "Dev set: every pre-declared variant", best)
        if "heldout" in by_set and all("variants" in r for r in by_set["heldout"].values()):
            ho = [by_set["heldout"][m] for m in ("oracle", "image") if m in by_set["heldout"]]
            fixes += "\n" + variant_section(ho, "Held-out set: measured once, after the selection above (selected variant flagged)", best)
            fix_chart(by_set, best)
        sel = f"**Selected on the dev set by the pre-declared rule: retrieval variant {best} ({VARIANTS[best]}), answer gate {gate} ({GATES[gate]}).**"
    else:
        sel = ""
    gate2_md = ""
    def has_gate(r, g): return "variants" in r and all(g in x["ans"] for x in r["variants"]["C1"] if "ans" in x)
    if "heldout" in by_set and all(has_gate(r, "A3") for r in dev + list(by_set["heldout"].values())):
        seen = dev + [by_set["heldout"][m] for m in ("oracle", "image") if m in by_set["heldout"]]
        g2, tot = select_gate2(seen)
        sel2 = "**Selected by the round-2 rule on the already-seen sets (dev + held-out, both modes): answer gate " + g2 + f" ({GATES[g2]}).** Totals over those four runs, as (wrong + made-up answers, correct answers of {tot[g2][2]}): " + "; ".join(f"{g}: {tot[g][0]}, {tot[g][1]}" for g in ("A1", "A2", "A3")) + "."
        tabs = gate_section(dev, "Dev set: answer gates", g2) + "\n" + gate_section([by_set["heldout"][m] for m in ("oracle", "image") if m in by_set["heldout"]], "Held-out set: answer gates", g2)
        fresh_tabs = ""
        if "fresh" in by_set and all("variants" in r for r in by_set["fresh"].values()):
            fr = [by_set["fresh"][m] for m in ("oracle", "image") if m in by_set["fresh"]]
            fresh_tabs = gate_section(fr, "Fresh set: answer gates, measured once", g2) + "\n" + variant_section(fr, "Fresh set: retrieval variants (a third check of round 1)", best if "best" in dir() else None)
        t2 = ROOT / "tools/eval/fixes2.md"
        if t2.exists(): gate2_md = t2.read_text().replace("{{GATE_SELECTION}}", sel2).replace("{{SEEN_TABLES}}", tabs).replace("{{FRESH_TABLES}}", fresh_tabs)
    tel_md = ""
    t_tpl = ROOT / "tools/eval/telugu.md"
    if t_tpl.exists() and all("ocr" in by_set.get(st, {}).get(m, {}) for st in ("dev", "heldout", "fresh") for m in POLICIES):
        choice, agg = select_telugu(by_set)
        if not agg: sel_t = ""
        else:
            names = {"image": "T0", "image_t1": "T1", "image_t2": "T2"}
            sel_t = ("**Selected on the seen sets by the pre-declared rule: " + (f"{POLICIES[choice]}.**" if choice else "no policy is eligible, so the switch stays off by default.**")
                     + " Pooled over dev, held-out and fresh: " + "; ".join(f"{names[m]}: Telugu fields {pct(a['te'])}, English+Hindi fields {pct(a['other'])}, mean merged MRR {a['mrr']:.2f}" for m, a in agg.items()) + ".")
        seen_t = telugu_table(by_set, ["dev", "heldout", "fresh"], "Seen sets (dev, held-out, fresh)")
        final_t = telugu_table(by_set, sorted(x for x in by_set if x.startswith("ocrtest")), "Final set (new documents, measured once)") if any(x.startswith("ocrtest") for x in by_set) else ""
        tel_md = t_tpl.read_text().replace("{{TELUGU_SELECTION}}", sel_t).replace("{{TELUGU_SEEN}}", seen_t).replace("{{TELUGU_FINAL}}", final_t)
    intro = (ROOT / "tools/eval/intro.md").read_text().replace("{{CORPUS}}", corpus_summary()).replace("{{HEADLINE}}", headline(analyses))
    fixes_md = (ROOT / "tools/eval/fixes.md").read_text().replace("{{SELECTION}}", sel).replace("{{TABLES}}", fixes) if (ROOT / "tools/eval/fixes.md").exists() and fixes else ""
    (DOCS / "EVALUATION.md").write_text(intro + "\n\n" + fixes_md + "\n\n" + gate2_md + "\n\n" + tel_md + "\n\n---\n\n## Detailed baseline results (generated by tools/eval/report.py)\n\n![Recall by query style](eval_retrieval.png)\n\n" + body + "\n")
    print(body if not fixes else fixes)

main()

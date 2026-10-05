# Munin evaluation

How well does Munin find the right screenshot, answer value questions, and add up payments, measured on a labelled test set
instead of a few hand-picked queries. **The first honest result is that it is weaker than the demo queries suggested**, and
the headline claim "merging keyword and meaning search beats either alone" does **not** hold on this set.

> The hand-written commentary below describes the committed results. Re-running `tools/eval/run_eval.sh` regenerates the
> tables and chart further down but not this text. **No change was made to the search, answer or ledger logic after seeing
> these results**, so they are an untuned baseline.

## What was measured

- **Corpus:** {{CORPUS}} Eleven "anchor" documents are the single correct answer for the queries; the other 289 are fillers of the
  *same types* (other hostels, flights, bills, notes ...), so every query has many confusable near-misses. A script asserts that
  words specific to an anchor appear in no other document, so each query has exactly one correct document.
- **Queries:** 60 = 11 intents x 5 styles (English, Telugu script, Hindi, Roman-script Telugu, mixed) + 5 "no such document"
  questions. The same 11 intents are phrased in all five styles, so styles are directly comparable. 25 of the 55 find/value
  queries ask for a value (amount, date, phone). Anchors are in English (8), Hindi (2) and Telugu (1) documents.
- **Two modes:** *perfect text* indexes each document's true text (isolates retrieval and extraction from OCR); *real OCR* renders
  every document to a PNG and runs the real ML Kit pipeline, so it includes OCR mistakes and Telugu text that OCR cannot read.
- **Configurations:** keywords only (BM25), meaning only (embeddings), merged (reciprocal rank fusion), as in the app.
- **Metrics:** recall@1, recall@5, MRR (rank of the correct document, 1/rank, 0 if outside the top 20); value-question accuracy;
  field extraction; ledger totals against the true sums; latency (emulator).

## Headline

{{HEADLINE}}

## Findings

1. **Merged is worse than meaning-only overall, in both modes** (recall@5 67% vs 85% with perfect text; 71% vs 76% with real OCR),
   but the picture is more specific than that. **When the query and the document are in the same language, merged is as good or
   slightly better**: for English, Roman-script Telugu and mixed queries over the 8 English-document intents, merged ranked the
   right document first in 24 of 24 queries (23 of 24 with OCR) against 22 of 24 for meaning-only. **Across languages it hurts**:
   Hindi queries (recall@5 55% vs 82% with perfect text), and every query aimed at a Hindi or Telugu document from another script
   (Hindi documents: 60% vs 90%; Telugu documents: 40% vs 100% with perfect text). For Telugu-script queries the two are within
   noise (n=11) and the winner flips between modes. Reading the actual rankings shows why: in those cases the keyword leg returns
   *lexical coincidences*: Hindi function words (a Hindi question about an English receipt returned unrelated Hindi notes), the shared
   word "Vidya" (an English query for the Hindi *Saraswati Vidya Mandir* receipt returned the English *Sai Vidya Hostel* receipt), and
   the Telugu word for "bill" (a Telugu water-bill query returned Telugu electricity bills). Rank-based fusion trusts a rank-1
   keyword hit as much as a rank-1 meaning hit, and there are no Hindi or Telugu stopword lists. A gated or weighted fusion is the
   obvious next experiment; it has not been tried.
2. **Language matters a lot.** English, Roman-script Telugu and mixed queries over English documents land the right document first
   almost every time (see the per-intent table). Telugu-script and Hindi queries over English documents mostly do not. The embedding
   model finds the *topic* across languages but not *names and dates* across scripts, which is exactly what tells ten similar bills
   or receipts apart.
3. **Telugu text in images is not read, as designed.** With real OCR, Telugu documents are found 0 of 5 times and only 4 of 46
   fields in them were extracted (9%), against 96% for English and 89% for Hindi documents. A Tesseract fallback is the only fix.
4. **Value answers are far from reliable.** Only 10 of 25 (perfect text) and 9 of 25 (real OCR) value questions were answered
   correctly. With perfect text, 3 wrong answers were shown (all Telugu-script queries, answered from the wrong receipt); with real
   OCR none were wrong because the app declined 15 times. Worse, **2 of the 5 "no such document" questions got a confident answer**
   ("how much was the car insurance" -> a *health* insurance premium), because the whole-word gate accepts "insurance" as half of the
   question's topic. The gate needs to require the *distinguishing* words, not any overlap.
5. **Extraction is exact on clean text and good but not great on OCR.** 377 of 377 truth values were present with perfect text.
   With OCR: amounts 80%, dates 84%, phones 96%, addresses 100%; 42 of the 60 misses are in the Telugu documents (4 of 46 fields found), the other 18 are OCR errors in English (12) and Hindi (6) documents.
6. **The ledger is exact about what it counts but counts only part of it.** With perfect text all 35 distinct payments were counted
   and every monthly total matched to the paisa. With real OCR, 20 of the 35 were counted, **0 counted amounts were wrong**, every
   monthly total equalled the true sum of the payments counted, and 14 payments were held back as "needs a check" because OCR read
   the rupee sign as a different glyph (the amounts themselves were read correctly for all 46). The guard trades coverage for safety.
7. **Speed is not a problem:** search takes a median of 2-19 ms on the emulator (the model's query embedding dominates); OCR a median
   92 ms and embedding 32 ms per document.

## How far to trust this

- **The documents are synthetic and template-generated**: clean, regular and repetitive. Real screenshots are messier, so real
  accuracy is likely lower on extraction and the ledger, and unknown on retrieval.
- **Small test set:** 55 find/value queries, 11 per style, so one query is 9 percentage points within a style; differences under
  about 10 points are noise. The 11 intents are not independent samples (each appears in all five styles).
- **Queries in Telugu, Hindi, Roman Telugu and mixed were written by the author, not a native speaker.** Awkward phrasing would
  lower those styles' scores; they need a native review before the numbers are quoted.
- One correct document per query; near-duplicate answers are not credited.
- Latency is from an emulator, not a phone. OCR output varies slightly between runs.
- Not done: comparison with a second embedding model, and a held-out query set. Because there is no held-out set, **any tuning
  against these 60 queries would make its results optimistic** and should be re-measured on fresh queries.

## What to try next (not done)

1. Gate the keyword leg: Hindi and Telugu stopwords, drop single-token or very common hits, and fuse with weights or scores instead of
   bare ranks. Re-measure on new queries.
2. Tighten the answer gate to the distinguishing words of the question, and decline when the top two documents are close.
3. Add a Tesseract Telugu pass behind the `OcrEngine` interface and measure it with this harness (Telugu documents are currently 0%).
4. Get the non-English queries reviewed by native speakers, and add a held-out query set before tuning anything.

## Reproduce

```sh
tools/eval/run_eval.sh            # perfect-text and real-OCR modes on a connected device or emulator (about 2 minutes)
tools/eval/run_eval.sh oracle     # perfect-text mode only (about 30 seconds, no images needed)
```

`make_corpus.py` builds `tools/eval/data/` (committed), `render_images.swift` renders the PNGs (not committed, macOS only),
`EvalHarnessTest` runs the pipeline on the device and writes raw results to `tools/eval/results/` (committed), and `report.py`
computes every metric from those raw results.

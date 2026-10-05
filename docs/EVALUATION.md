# Munin evaluation

How well does Munin find the right screenshot, answer value questions, and add up payments, measured on a labelled test set
instead of a few hand-picked queries. **The first honest result is that it is weaker than the demo queries suggested**, and
the headline claim "merging keyword and meaning search beats either alone" does **not** hold on this set.

> The hand-written commentary below describes the committed results. Re-running `tools/eval/run_eval.sh` regenerates the
> tables and chart further down but not this text. **No change was made to the search, answer or ledger logic after seeing
> these results**, so they are an untuned baseline.

## What was measured

- **Corpus:** 300 synthetic documents: 243 English, 32 Hindi, 25 Telugu. 49 fee receipt, 46 upi payment, 45 ticket, 40 utility bill, 34 notes, 26 appointment, 14 timetable, 13 chat, 8 rent receipt, 8 insurance, 7 shopping list, 6 certificate, 4 recipe. Eleven "anchor" documents are the single correct answer for the queries; the other 289 are fillers of the
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

| Mode | Configuration | Recall@1 | Recall@5 | MRR | Median search time |
|---|---:|---:|---:|---:|---:|
| Perfect text | Keywords only | 49% | 51% | 0.50 | 2 ms |
| Perfect text | Meaning only (embeddings) | 69% | 85% | 0.77 | 13 ms |
| Perfect text | Merged (RRF) | 58% | 67% | 0.62 | 13 ms |
| Real OCR | Keywords only | 47% | 49% | 0.48 | 5 ms |
| Real OCR | Meaning only (embeddings) | 67% | 76% | 0.71 | 18 ms |
| Real OCR | Merged (RRF) | 56% | 71% | 0.63 | 19 ms |

| Mode | Value questions | Wrongly answered when no document exists | Document fields extracted (truth present) | Ledger |
|---|---:|---:|---:|---:|
| Perfect text | 10 / 25 correct (3 wrong shown) | 2 / 5 | 377 / 377 | 35 of 35 counted, 0 wrong amounts |
| Real OCR | 9 / 25 correct (0 wrong shown) | 2 / 5 | 317 / 377 | 20 of 35 counted, 0 wrong amounts |

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


---

## Detailed results (generated by tools/eval/report.py)

![Recall by query style](eval_retrieval.png)

### Mode 1: perfect text (isolates retrieval and extraction from OCR)

Indexed 300 of 300 documents (0 with no text found, 0 duplicates, 0 failed) in 13 s; this mode uses the true text, so there is no OCR step.

**Retrieval** (55 find/value queries; the 5 negative queries are scored separately). Recall@1 / recall@5 / MRR:

| Query style | n | Keywords only | Meaning only (embeddings) | Merged (RRF) |
|---|---:|---:|---:|---:|
| All styles | 55 | 49% / 51% / 0.50 | 69% / 85% / 0.77 | 58% / 67% / 0.62 |
| English | 11 | 73% / 73% / 0.73 | 73% / 100% / 0.84 | 73% / 82% / 0.76 |
| Telugu script | 11 | 18% / 18% / 0.18 | 36% / 55% / 0.47 | 27% / 36% / 0.34 |
| Hindi | 11 | 18% / 27% / 0.21 | 64% / 82% / 0.75 | 45% / 55% / 0.51 |
| Roman Telugu | 11 | 64% / 64% / 0.65 | 91% / 100% / 0.95 | 73% / 82% / 0.75 |
| Mixed | 11 | 73% / 73% / 0.73 | 82% / 91% / 0.85 | 73% / 82% / 0.75 |

By the language of the *document* being looked for:

| Looking for | n | Keywords only | Meaning only (embeddings) | Merged (RRF) |
|---|---:|---:|---:|---:|
| English documents | 40 | 60% / 62% / 0.61 | 70% / 82% / 0.77 | 68% / 72% / 0.71 |
| Hindi documents | 10 | 20% / 20% / 0.20 | 60% / 90% / 0.73 | 30% / 60% / 0.39 |
| Telugu documents | 5 | 20% / 20% / 0.20 | 80% / 100% / 0.85 | 40% / 40% / 0.40 |

**Value questions** (amount / date / phone, 25 queries): 10 correct, 3 wrong, 11 declined (no answer shown), 1 not recognised as a question. Accuracy 40%; when an answer was shown it was right 77% of the time.

| Style | correct | wrong | declined | not a question |
|---|---:|---:|---:|---:|
| English | 3 | 0 | 2 | 0 |
| Telugu script | 1 | 3 | 1 | 0 |
| Hindi | 1 | 0 | 4 | 0 |
| Roman Telugu | 3 | 0 | 2 | 0 |
| Mixed | 2 | 0 | 2 | 1 |

Wrong answers shown: q02 "సాయి విద్య హాస్టల్ ఫీజు ఎంత" -> AMOUNT 6500 from d100; q27 "డాక్టర్ మీరా రావు క్లినిక్ ఫోన్ నంబర్" -> PHONE +918501351006 from d207; q42 "సరస్వతి విద్యా మందిర్ స్కూల్ ఫీజు ఎంత" -> AMOUNT 15500 from d096

**Questions that should get no answer** ("how much was the car insurance" in 5 styles; no such document exists): 2 of 5 wrongly answered: how much was the car insurance -> 8600 from d264; car insurance entha -> 8600 from d264.

**Field extraction** (is the true value among the facts extracted from the document?):

| Field | found / documents | rate |
|---|---:|---:|
| ADDRESS | 18 / 18 | 100% |
| AMOUNT | 143 / 143 | 100% |
| DATE | 190 / 190 | 100% |
| PHONE | 26 / 26 | 100% |

| Document language | found / fields | rate |
|---|---:|---:|
| English | 278 / 278 | 100% |
| Hindi | 53 / 53 | 100% |
| Telugu | 46 / 46 | 100% |

**Payment ledger** (46 synthetic UPI screenshots: 35 distinct successful payments you made, plus duplicates, failed, pending and received ones):

- Recognised as payments: 46 / 46; non-payment documents wrongly recognised: 0.
- Fields read exactly (of 46): amount 46, date 46, payee 46, reference 46.
- Counted in totals: 35 (of which 35 are genuine distinct payments); held back as 'needs a check': 0; duplicates: 3; failed/pending/received: 8; unreadable: 0.
- Counted payments with a wrong amount: 0.

| Month | True total | Ledger total | True sum of what was counted | Ledger vs that sum | Share of true total counted |
|---|---:|---:|---:|---:|---:|
| 2026-08 | ₹10,380.00 | ₹10,380.00 | ₹10,380.00 | exact | 100.0% |
| 2026-09 | ₹14,959.50 | ₹14,959.50 | ₹14,959.50 | exact | 100.0% |
| 2026-10 | ₹25,021.00 | ₹25,021.00 | ₹25,021.00 | exact | 100.0% |

**Search latency** (end to end, emulator): Keywords only: median 2 ms, p95 17 ms; Meaning only (embeddings): median 13 ms, p95 28 ms; Merged (RRF): median 13 ms, p95 32 ms.

**Rank of the correct document per query intent**, written `merged (meaning-only, keywords-only)`; `-` means not in the top 20; 1 is best:

| Intent | Looking for | English | Telugu script | Hindi | Roman Telugu | Mixed |
|---|---:|---:|---:|---:|---:|---:|
| A1 | Sai Vidya hostel fee amount (English receipt) | 1 (1, 1) | - (3, -) | 11 (1, -) | 1 (1, 1) | 1 (1, 1) |
| A2 | electricity bill due 15 Oct (English bill) | 1 (2, 1) | - (-, -) | - (11, -) | 1 (1, 1) | 1 (10, 1) |
| A3 | flight to Visakhapatnam (English ticket) | 1 (1, 1) | 11 (11, -) | 19 (8, -) | 1 (1, 8) | 1 (1, 1) |
| A4 | water bill due date (English bill) | 1 (1, 1) | 8 (1, -) | 10 (1, -) | 1 (1, 1) | 1 (1, 1) |
| A5 | organic chemistry SN1/SN2 notes (English) | 1 (1, 1) | 1 (1, 1) | 1 (1, 3) | 1 (1, 1) | 1 (1, 1) |
| A6 | Dr Meera Rao clinic phone (English card) | 1 (1, 1) | 10 (7, -) | 10 (2, -) | 1 (1, 1) | 1 (1, 1) |
| A7 | payment to Lakshmi Tiffins (English UPI) | 1 (1, 1) | 20 (20, -) | 1 (1, -) | 1 (1, 1) | 1 (1, 1) |
| A8 | December exam timetable, operating systems (English) | 1 (1, 1) | 3 (2, -) | 4 (2, -) | 1 (1, 1) | 1 (1, 1) |
| A9 | Saraswati Vidya Mandir school fee (HINDI receipt) | - (2, -) | 13 (11, -) | 1 (1, 1) | - (2, -) | - (5, -) |
| A10 | Mughal empire / Akbar history notes (HINDI) | 3 (1, -) | 1 (1, -) | 1 (1, 1) | 4 (1, -) | 5 (1, -) |
| A11 | Sri Chaitanya junior college fee (TELUGU receipt) | - (4, -) | 1 (1, 1) | 1 (1, -) | - (1, -) | - (1, -) |

**Queries where the merged ranking did not put the right document first** (23 of 55):

| Query | Style | Text | Rank: keywords / meaning / merged | Merged top 3 | Target |
|---|---:|---:|---:|---:|---:|
| q02 | te | సాయి విద్య హాస్టల్ ఫీజు ఎంత | - / 3 / - | d100, d096, d099 | d001 |
| q03 | hi | साई विद्या हॉस्टल की फीस कितनी थी | - / 1 / 11 | d008, d084, d087 | d001 |
| q07 | te | అక్టోబర్ 15 కరెంటు బిల్లు చివరి తేదీ | - / - / - | d135, d136, d137 | d002 |
| q08 | hi | 15 अक्टूबर को बिजली का बिल जमा करने की तारीख | - / 11 / - | d127, d125, d126 | d002 |
| q12 | te | విశాఖపట్నం విమానం టికెట్ | - / 11 / 11 | d175, d207, d163 | d003 |
| q13 | hi | विशाखापत्तनम की फ्लाइट का टिकट | - / 8 / 19 | d203, d205, d204 | d003 |
| q17 | te | నీటి బిల్లు ఎప్పుడు కట్టాలి | - / 1 / 8 | d140, d139, d136 | d004 |
| q18 | hi | पानी का बिल कब भरना है | - / 1 / 10 | d127, d131, d126 | d004 |
| q27 | te | డాక్టర్ మీరా రావు క్లినిక్ ఫోన్ నంబర్ | - / 7 / 10 | d207, d206, d209 | d006 |
| q28 | hi | डॉ. मीरा राव के क्लिनिक का फोन नंबर | - / 2 / 10 | d203, d202, d204 | d006 |
| q32 | te | లక్ష్మి టిఫిన్స్ కి చెల్లింపు | - / 20 / 20 | d087, d084, d240 | d011 |
| q37 | te | డిసెంబర్ పరీక్షల టైమ్ టేబుల్ ఆపరేటింగ్ సిస్టమ్స్ | - / 2 / 3 | d138, d240, d007 | d007 |
| q38 | hi | दिसंबर परीक्षा समय सारणी ऑपरेटिंग सिस्टम | - / 2 / 4 | d254, d252, d253 | d007 |
| q41 | en | how much was the Saraswati Vidya Mandir school fee | - / 2 / - | d001, d061, d065 | d008 |
| q42 | te | సరస్వతి విద్యా మందిర్ స్కూల్ ఫీజు ఎంత | - / 11 / 13 | d096, d100, d097 | d008 |
| q44 | rt | saraswati vidya mandir school fee entha | - / 2 / - | d001, d061, d063 | d008 |
| q45 | mix | Saraswati Vidya Mandir school fee कितनी थी | - / 5 / - | d001, d061, d065 | d008 |
| q46 | en | history notes on the Mughal empire and Akbar | - / 1 / 3 | d217, d213, d009 | d009 |
| q49 | rt | mughal samrajyam akbar history notes | - / 1 / 4 | d217, d213, d233 | d009 |
| q50 | mix | Mughal empire Akbar గురించి history notes | - / 1 / 5 | d217, d233, d212 | d009 |
| q51 | en | how much was the Sri Chaitanya junior college fee | - / 4 / - | d064, d070, d058 | d010 |
| q54 | rt | sri chaitanya junior college fee entha | - / 1 / - | d064, d058, d070 | d010 |
| q55 | mix | Sri Chaitanya junior college fee ఎంత | - / 1 / - | d070, d059, d064 | d010 |

### Mode 2: real OCR on the rendered images (end to end)

Indexed 300 of 300 documents (0 with no text found, 0 duplicates, 0 failed) in 47 s; median OCR 92 ms and embedding 32 ms per document.

**Retrieval** (55 find/value queries; the 5 negative queries are scored separately). Recall@1 / recall@5 / MRR:

| Query style | n | Keywords only | Meaning only (embeddings) | Merged (RRF) |
|---|---:|---:|---:|---:|
| All styles | 55 | 47% / 49% / 0.48 | 67% / 76% / 0.71 | 56% / 71% / 0.63 |
| English | 11 | 73% / 73% / 0.73 | 73% / 91% / 0.80 | 73% / 82% / 0.77 |
| Telugu script | 11 | 9% / 9% / 0.09 | 45% / 55% / 0.49 | 45% / 64% / 0.52 |
| Hindi | 11 | 18% / 27% / 0.21 | 64% / 73% / 0.68 | 27% / 45% / 0.37 |
| Roman Telugu | 11 | 64% / 64% / 0.65 | 82% / 91% / 0.84 | 73% / 82% / 0.76 |
| Mixed | 11 | 73% / 73% / 0.73 | 73% / 73% / 0.75 | 64% / 82% / 0.73 |

By the language of the *document* being looked for:

| Looking for | n | Keywords only | Meaning only (embeddings) | Merged (RRF) |
|---|---:|---:|---:|---:|
| English documents | 40 | 60% / 62% / 0.61 | 75% / 82% / 0.78 | 68% / 80% / 0.73 |
| Hindi documents | 10 | 20% / 20% / 0.20 | 70% / 90% / 0.79 | 40% / 70% / 0.53 |
| Telugu documents | 5 | 0% / 0% / 0.00 | 0% / 0% / 0.00 | 0% / 0% / 0.00 |

**Value questions** (amount / date / phone, 25 queries): 9 correct, 0 wrong, 15 declined (no answer shown), 1 not recognised as a question. Accuracy 36%; when an answer was shown it was right 100% of the time.

| Style | correct | wrong | declined | not a question |
|---|---:|---:|---:|---:|
| English | 3 | 0 | 2 | 0 |
| Telugu script | 0 | 0 | 5 | 0 |
| Hindi | 1 | 0 | 4 | 0 |
| Roman Telugu | 3 | 0 | 2 | 0 |
| Mixed | 2 | 0 | 2 | 1 |

**Questions that should get no answer** ("how much was the car insurance" in 5 styles; no such document exists): 2 of 5 wrongly answered: how much was the car insurance -> 4600 from d264; car insurance entha -> 4600 from d267.

**Field extraction** (is the true value among the facts extracted from the document?):

| Field | found / documents | rate |
|---|---:|---:|
| ADDRESS | 18 / 18 | 100% |
| AMOUNT | 115 / 143 | 80% |
| DATE | 159 / 190 | 84% |
| PHONE | 25 / 26 | 96% |

| Document language | found / fields | rate |
|---|---:|---:|
| English | 266 / 278 | 96% |
| Hindi | 47 / 53 | 89% |
| Telugu | 4 / 46 | 9% |

**Payment ledger** (46 synthetic UPI screenshots: 35 distinct successful payments you made, plus duplicates, failed, pending and received ones):

- Recognised as payments: 46 / 46; non-payment documents wrongly recognised: 0.
- Fields read exactly (of 46): amount 46, date 42, payee 46, reference 43.
- Counted in totals: 20 (of which 20 are genuine distinct payments); held back as 'needs a check': 14; duplicates: 3; failed/pending/received: 8; unreadable: 1.
- Counted payments with a wrong amount: 0.

| Month | True total | Ledger total | True sum of what was counted | Ledger vs that sum | Share of true total counted |
|---|---:|---:|---:|---:|---:|
| 2026-08 | ₹10,380.00 | ₹3,935.00 | ₹3,935.00 | exact | 37.9% |
| 2026-09 | ₹14,959.50 | ₹5,469.50 | ₹5,469.50 | exact | 36.6% |
| 2026-10 | ₹25,021.00 | ₹11,097.00 | ₹11,097.00 | exact | 44.4% |

**Search latency** (end to end, emulator): Keywords only: median 5 ms, p95 11 ms; Meaning only (embeddings): median 18 ms, p95 31 ms; Merged (RRF): median 19 ms, p95 29 ms.

**Rank of the correct document per query intent**, written `merged (meaning-only, keywords-only)`; `-` means not in the top 20; 1 is best:

| Intent | Looking for | English | Telugu script | Hindi | Roman Telugu | Mixed |
|---|---:|---:|---:|---:|---:|---:|
| A1 | Sai Vidya hostel fee amount (English receipt) | 1 (1, 1) | 1 (1, -) | 12 (1, -) | 1 (1, 1) | 1 (1, 1) |
| A2 | electricity bill due 15 Oct (English bill) | 1 (4, 1) | 3 (-, -) | - (10, -) | 1 (1, 1) | 2 (9, 1) |
| A3 | flight to Visakhapatnam (English ticket) | 1 (1, 1) | - (-, -) | 18 (8, -) | 1 (1, 8) | 1 (1, 1) |
| A4 | water bill due date (English bill) | 1 (1, 1) | 1 (1, -) | 10 (1, -) | 1 (1, 1) | 1 (1, 1) |
| A5 | organic chemistry SN1/SN2 notes (English) | 1 (1, 1) | 1 (1, 1) | 1 (1, 3) | 1 (1, 1) | 1 (1, 1) |
| A6 | Dr Meera Rao clinic phone (English card) | 1 (1, 1) | 3 (3, -) | 13 (1, -) | 1 (1, 1) | 1 (1, 1) |
| A7 | payment to Lakshmi Tiffins (English UPI) | 1 (1, 1) | - (-, -) | 2 (1, -) | 1 (1, 1) | 1 (1, 1) |
| A8 | December exam timetable, operating systems (English) | 1 (1, 1) | 16 (16, -) | 5 (4, -) | 1 (1, 1) | 1 (1, 1) |
| A9 | Saraswati Vidya Mandir school fee (HINDI receipt) | - (2, -) | 1 (1, -) | 1 (1, 1) | - (4, -) | - (6, -) |
| A10 | Mughal empire / Akbar history notes (HINDI) | 2 (1, -) | 1 (1, -) | 1 (1, 1) | 3 (1, -) | 2 (1, -) |
| A11 | Sri Chaitanya junior college fee (TELUGU receipt) | - (-, -) | - (-, -) | - (-, -) | - (-, -) | - (-, -) |

**Queries where the merged ranking did not put the right document first** (24 of 55):

| Query | Style | Text | Rank: keywords / meaning / merged | Merged top 3 | Target |
|---|---:|---:|---:|---:|---:|
| q03 | hi | साई विद्या हॉस्टल की फीस कितनी थी | - / 1 / 12 | d084, d008, d087 | d001 |
| q07 | te | అక్టోబర్ 15 కరెంటు బిల్లు చివరి తేదీ | - / - / 3 | d102, d101, d002 | d002 |
| q08 | hi | 15 अक्टूबर को बिजली का बिल जमा करने की तारीख | - / 10 / - | d127, d129, d125 | d002 |
| q10 | mix | electricity bill అక్టోబర్ 15 due | 1 / 9 / 2 | d109, d002, d106 | d002 |
| q12 | te | విశాఖపట్నం విమానం టికెట్ | - / - / - | d163, d140, d138 | d003 |
| q13 | hi | विशाखापत्तनम की फ्लाइट का टिकट | - / 8 / 18 | d126, d203, d129 | d003 |
| q18 | hi | पानी का बिल कब भरना है | - / 1 / 10 | d127, d129, d131 | d004 |
| q27 | te | డాక్టర్ మీరా రావు క్లినిక్ ఫోన్ నంబర్ | - / 3 / 3 | d189, d194, d006 | d006 |
| q28 | hi | डॉ. मीरा राव के क्लिनिक का फोन नंबर | - / 1 / 13 | d205, d203, d202 | d006 |
| q32 | te | లక్ష్మి టిఫిన్స్ కి చెల్లింపు | - / - / - | d135, d137, d140 | d011 |
| q33 | hi | लक्ष्मी टिफिन्स को भुगतान | - / 1 / 2 | d237, d011, d065 | d011 |
| q37 | te | డిసెంబర్ పరీక్షల టైమ్ టేబుల్ ఆపరేటింగ్ సిస్టమ్స్ | - / 16 / 16 | d093, d254, d135 | d007 |
| q38 | hi | दिसंबर परीक्षा समय सारणी ऑपरेटिंग सिस्टम | - / 4 / 5 | d254, d253, d252 | d007 |
| q41 | en | how much was the Saraswati Vidya Mandir school fee | - / 2 / - | d001, d061, d065 | d008 |
| q44 | rt | saraswati vidya mandir school fee entha | - / 4 / - | d001, d077, d063 | d008 |
| q45 | mix | Saraswati Vidya Mandir school fee कितनी थी | - / 6 / - | d001, d061, d065 | d008 |
| q46 | en | history notes on the Mughal empire and Akbar | - / 1 / 2 | d217, d009, d212 | d009 |
| q49 | rt | mughal samrajyam akbar history notes | - / 1 / 3 | d217, d233, d009 | d009 |
| q50 | mix | Mughal empire Akbar గురించి history notes | - / 1 / 2 | d217, d009, d212 | d009 |
| q51 | en | how much was the Sri Chaitanya junior college fee | - / - / - | d058, d064, d059 | d010 |
| q52 | te | శ్రీ చైతన్య జూనియర్ కాలేజీ ఫీజు ఎంత | - / - / - | d081, d008, d091 | d010 |
| q53 | hi | श्री चैतन्य जूनियर कॉलेज की फीस कितनी थी | - / - / - | d084, d239, d009 | d010 |
| q54 | rt | sri chaitanya junior college fee entha | - / - / - | d058, d064, d070 | d010 |
| q55 | mix | Sri Chaitanya junior college fee ఎంత | - / - / - | d070, d059, d064 | d010 |

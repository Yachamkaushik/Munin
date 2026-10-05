# Munin

Offline, on-device semantic search for screenshots, document photos and PDFs on Android, in Telugu, Hindi,
English, Roman-script Telugu, or a mix. No INTERNET permission; models are bundled in the APK.

Status: **steps 1-7 built, plus an evaluation harness** (tokenizer parity, indexing, hybrid search, answers, actions, payment
ledger, voice query, and a 300-document / 60-query evaluation).

> **Read [docs/EVALUATION.md](docs/EVALUATION.md) before quoting any quality claim.** Measured on a labelled set, Munin is weaker than
> the hand-picked checks in the per-step sections below suggest: with perfect text, recall@5 is 85% for meaning-only search but 67% for
> the merged ranking (the keyword leg adds noise across languages; merged only wins when query and document share a language),
> Telugu-script and Hindi queries over English documents are weak, only 10 of 25 value questions were answered correctly, and 2 of 5
> "no such document" questions got a made-up answer. The per-step tables report what each step was checked against, not accuracy.

## Setup

```sh
tools/fetch_models.sh                                   # one-off download (~120 MB), needs network
python3 -m venv .venv && .venv/bin/pip install onnxruntime sentencepiece numpy tokenizers
.venv/bin/python tools/embed_reference.py               # tools/reference/reference.json (68 vectors)
.venv/bin/python tools/make_tokenizer_stress.py         # tools/reference/tokenizer_stress.json (1500 strings)
```

Android: JDK 17+ and the SDK in `local.properties`.

```sh
./gradlew :app:testDebugUnitTest            # tokenizer parity vs Python, JVM only
./gradlew :app:connectedDebugAndroidTest    # token ids + ONNX embeddings on a device/emulator
tools/check_offline.sh                      # fails if the built APKs request any network permission
```

## Step 1 results

| Check | Result |
|---|---|
| Kotlin vs Python token ids, 68 reference inputs | identical |
| Kotlin vs Python token ids, 1,500 stress strings (Telugu, Devanagari, full-width, ligatures, emoji, controls) | identical |
| On-device embeddings vs Python, 68 vectors (Pixel 8 emulator, arm64) | min cosine 0.9999999, max abs diff 6e-8 |
| Merged manifest | no INTERNET / ACCESS_NETWORK_STATE (see below) |

## Things worth knowing

- **Tokenizer is a hand port**, not a binding: `.model` protobuf parser, SentencePiece's `nmt_nfkc` charsmap
  normalizer (Darts double-array trie), Unigram Viterbi, and merging of consecutive `<unk>`.
  Ids use the XLM-R/fairseq numbering (`<s>=0 <pad>=1 </s>=2 <unk>=3`, SentencePiece id + 1).
- **ORT optimization level is pinned to BASIC** in both `tools/embed_reference.py` and `E5Embedder.kt`.
  With ORT's default level the int8 model's output differs by up to cosine 0.991 from a no-fusion run on some
  inputs (emoji / rare scripts worst), so Python and the device only agree when both use the same level.
  Keep the two in sync.
- **onnxruntime-android 1.30.0 injects INTERNET, ACCESS_NETWORK_STATE and a telemetry provider** through its
  AAR manifest. `AndroidManifest.xml` strips them with `tools:node="remove"`; `tools/check_offline.sh` guards it.
  Re-run it after any dependency bump.
- The int8 file is `onnx/model_qint8_avx512_vnni.onnx` from `intfloat/multilingual-e5-small` (the name refers to
  how it was quantized; it runs on ARM CPU).
- Model binaries are git-ignored (the ONNX file is 118 MB); `tools/fetch_models.sh` restores them.
- APK is ~178 MB debug, because the model ships uncompressed in assets.
- `tools/sentences.json` (the Telugu / Hindi / Roman-Telugu / mixed sentences) is **awaiting native-speaker
  review**. Edit it, then re-run both Python scripts.

## Step 2: indexing pipeline

MediaStore scan (newest first, capped at 1,000, images under 200 px skipped) -> SHA-256 (exact duplicates are not
indexed twice) -> ML Kit OCR -> chunks (<= 256 tokens, cut at line boundaries) -> E5 vectors -> Room
(`items`, `chunks`, `embeddings` as float32 blobs, `facts` reserved for step 4) + a full-text table.
A foreground WorkManager job (`IndexWorker`) drives it; each item is written in one transaction.

| Check | Result (Pixel 8 emulator, API 37) |
|---|---|
| Unit + instrumented tests | 8 indexer, 5 chunker, 2 OCR-line, 3 tokenizer, 2 embedding parity: all pass |
| Kill the app mid-run, relaunch | resumes; 42/42 processed; chunks = vectors = FTS rows = 39 indexed items, no duplicates |
| Exact duplicate file | marked DUPLICATE, no second set of chunks |
| Image with no text | kept as NO_TEXT, no chunks |
| Android 14 partial access | banner with a "Choose more photos" button |
| Median per item | OCR 636 ms, embedding 32 ms (emulator, so a real phone will differ) |

Try it on an emulator with the synthetic samples (every one carries a "SYNTHETIC SAMPLE" footer):

```sh
swift tools/make_sample_images.swift tools/sample_images   # already committed; re-run to regenerate
adb push tools/sample_images /sdcard/Pictures/MuninSamples  # then open Munin and tap "Scan and index"
```

### Limits and findings (read before trusting a demo)

- **Telugu text inside images is not read.** ML Kit has no Telugu model. Fed a Telugu screenshot, its Devanagari model
  returns confident-looking gibberish. Lines under 0.5 confidence are dropped (`OcrLines`), which removes most of it, but
  the threshold was tuned on a handful of synthetic images and is a guess until the evaluation step. `OcrEngine` is an
  interface so a Tesseract `tel` engine can be added later; none exists today. Telugu *queries* are unaffected (that is
  the embedding model's job).
- **PDFs are not indexed yet.** Step 2 covers images only.
- **FTS5 is not available in Android's system SQLite** (checked on API 37; there is a test for it), so the keyword index
  is FTS4 on devices. FTS4 has no `bm25()`, so step 3 computes BM25 from `matchinfo`. The code still tries FTS5 first.
- **Indic keyword matching needed a tokenizer tweak**: SQLite's `unicode61` splits words at Devanagari and Telugu vowel
  signs, so those marks are declared token characters. A test checks whole-word matches in Telugu and Hindi.
- **OCR is imperfect** and later steps must cope: the rupee sign is often dropped or read as `I`, and spaces after
  colons are sometimes lost ("Paid on:12 Sep 2026"). The amount extractor must not require the symbol, and should
  anchor on labels like "Amount".
- The Devanagari recognizer also reads Latin and matched the Latin recognizer exactly on the English samples, so it runs
  first; the Latin one is only tried when confidence is low (< 0.75). Based on a few images.
- With partial photo access the scan can only see the shared photos, so it does not delete rows for files it cannot see.
- The first OCR call costs about 30 s on the emulator (model load). The worker warms the models up before item 1 and the
  UI shows medians, so that one-off cost is not mixed into per-item time.

## Step 3: search

One search box. Each query is embedded (`query: ` prefix), then two legs run: **meaning** (cosine over every stored
vector, brute force) and **keywords** (BM25 over the FTS4 table, computed from `matchinfo` because FTS4 has no
`bm25()`). The legs' top 50 chunks are merged with reciprocal rank fusion (k = 60), grouped to one result per item,
and shown as thumbnail + snippet with matched words in bold. The debug line shows per-stage time. "Merged", "Meaning
only" and "Keywords only" chips switch legs (used for the later keyword vs embeddings vs merged comparison).

| Check (Pixel 8 emulator) | Result |
|---|---|
| `hostel fee receipt` over the real UI index (39 vectors) | English receipt #1 (meaning #1, keywords #1); Hindi receipt #2 by meaning only |
| Telugu `హాస్టల్ ఫీజు రసీదు` over a test corpus | Telugu receipt #1 |
| Exact id `4821937560` | found by keywords (and merged) |
| Keyword leg across languages | `hostel fee` never returns the Telugu receipt, and vice versa |
| Meaning leg across languages | Hindi `छात्रावास शुल्क` -> Hindi, Telugu, English receipts top 3; Roman Telugu `current bill entha` -> electricity bill #1 |
| Search time, 8 vectors | median 20 ms (query embedding ~15 ms, scan ~1 ms) |
| Brute-force scan, random unit vectors | 5,000 vectors: 4.4 ms; 20,000 vectors: 17.6 ms (index load 76 ms / 532 ms) |
| Tests | 24 JVM + 20 on-device, all pass |

### Known weaknesses (not fixed yet)

- **No relevance cutoff.** The meaning leg always returns its nearest neighbours, so a nonsense query such as
  `qwertyzzz` still lists five items, and in the UI a query that matches one receipt also lists unrelated ones further
  down (E5 cosine scores sit in a narrow 0.8-0.9 band, so an absolute threshold is fragile). Fusion keeps genuine
  double matches on top, but the tail is noise. The evaluation step should choose a cutoff from data.
- **A Telugu image is not searchable by its own text** because OCR cannot read it (step 2). The Telugu receipt in the
  UI index was dropped as low-confidence OCR junk, so it never appears. Telugu *queries* work against the text that was
  read, and against Telugu text that is typed in (as in the test corpus).
- **Stopword-only queries** ("how much was the") have no keyword terms, so only the meaning leg answers, loosely.
- **The FTS5 query path is untested**: Android's SQLite has no FTS5, so only the FTS4 path runs on devices.
- First query after launch pays the model load (the screen says so) and a cold embedding (~150 ms in the UI).
- Tapping a result opens the file in the gallery app. There is no item detail screen yet.
- The mode chips and debug switch are developer controls, not final UI.

## Step 4: answer extraction

At index time, rules and regexes (`extract/FactExtractor`) read **amounts, dates, phone numbers and addresses** out of each
item's text into the `facts` table (normalized value, the text as read, its label, line, confidence). At query time
`answer/QuestionParser` decides whether the query asks for a value (English, Hindi, Telugu, Roman-script Telugu/Hindi, or
mixed), then `AnswerEngine` answers from the **top search hit only**, and only when it clearly is the right item and has
that field. Otherwise it says why and shows the normal result list. No language model is involved.

- Amounts: `₹`, `Rs`, `INR`, `रु`, `రూ`, `/-`, Indian and Western grouping, decimals, Devanagari/Telugu digits. When OCR dropped
  the rupee sign, a labelled number ("Amount due: 1,250", conf 0.7) or a bare number on its own line ("I2,499", conf 0.45) is
  still read, with lower confidence and a caveat on the card. IDs, dates and phone numbers are never read as amounts.
- Dates: `12/09/2026`, `12 Sep 2026`, `September 12, 2026`, 2-digit years, Hindi and Telugu month names, a time right after the
  date. Day-first (India). A date without a year is stored as `--11-03` and flagged "year not read".
- Phones: Indian mobiles (`+91`, spaced, Devanagari digits), toll-free 1800, STD landlines. 10-digit numbers under an
  ID-like label (Transaction ID, Ref No) are not phones.
- Addresses: lines after an `Address:` / `చిరునామా` / `पता` label, or lines ending in a PIN code (lower confidence).
- Spending questions ("how much did I spend in September") are deliberately left to the ledger (step 6).

| Check | Result |
|---|---|
| Tests | 81 JVM (28 extractor, 25 question/selector/format) and 33 on-device, all pass |
| `how much was the hostel fee` (real UI, real OCR of the sample screenshot) | **₹45,000**, "Amount paid: Rs 45,000", from the source file; 19 ms |
| Same question in Hindi / Telugu / Roman Telugu / mixed (test corpus) | ₹45,000 / ₹45,000 / ₹1,250 for `current bill entha` / ₹45,000 |
| `when is the electricity bill due` | 15 Oct 2026, label "Due date" |
| Phone number and address from a clinic card | +91 98765 43210; "Road No 36, Jubilee Hills, Hyderabad 500033" |
| Item has no such field (`flight booking phone number`) | declined: "the best match (flight) has no phone in the text that was read" |
| Unrelated questions (car insurance, rent, pizza, laptop, school bus fee) | all declined |
| Upgrade from a schema-v1 database with 42 items | migrated in place; all 39 indexed items had their facts back-filled from saved text |

### Limits (read before trusting a demo)

- **The value is not highlighted on the image.** That needs OCR bounding boxes, which are not stored. The card shows the
  label and the text as the OCR read it, and tapping opens the source file.
- **Wordless paraphrases are declined.** "dormitory charges how much" or "lodging cost" share no word with a hostel receipt, and
  the meaning scores are too close to trust (lead 0.001-0.011), so no answer is given. This trades recall for never answering
  from the wrong document.
- **The confidence gate was tuned on a handful of queries** (unrelated queries led by at most 0.029, so the minimum lead is 0.05;
  at least half of the question's topic words must appear as whole words in the item). The evaluation step must re-measure it.
  The first version (prefix keyword matches plus a 0.03 lead) would have answered "school bus fee" from the hostel receipt and
  "car insurance" from a card that said "card"; the calibration log caught that.
- Only the top result is ever answered from. Extraction was checked on synthetic screenshots and on text the OCR actually
  returned; real UPI apps, bills and forms will have layouts the rules have not seen. Extraction accuracy is measured properly
  in the evaluation step.
- A guessed amount (rupee sign not read) is only answered when the item also matches by words, and then carries a caveat.
- Telugu text inside images still is not read (step 2), so Telugu answers only come from Telugu text that OCR could read.
- Date ambiguity: `04/03/2026` is read as 4 March (day first); `12/25/2026` is read as 25 December at lower confidence.

Schema is now v2 (`MIGRATION_1_2`); `FactExtractor.VERSION` marks which rules produced stored facts, so a rule change
re-extracts from saved text without redoing OCR.

## Step 5: smart actions

Facts become actions, on the answer card and on a new **item detail screen** (tap a result or the card): the image, every
fact found, the recognized text, and "Open in gallery".

| Fact | Action | How |
|---|---|---|
| Date | Add to calendar | `ACTION_INSERT` on the calendar, title / date / notes filled in; all-day, or a 1-hour event if a time was read |
| Phone number | Call | `ACTION_DIAL` (never `ACTION_CALL`), so the dialer opens with the number and **you press call** |
| Address | Open in maps | `geo:0,0?q=<address>` |
| Anything | Share | system share sheet with a short text naming the value, item and source file |

**Every action asks first.** Tapping a button only opens a dialog that shows the exact value (and, for the calendar, the title,
date and notes; for share, the exact text) plus what will happen. Only the dialog's confirm button hands anything to another app,
and each target app then has its own final step (Save, Call, choose a share target). None needs a permission, and the app still has
no network permission. Low-confidence values (a guessed amount, a missing year, a shaky OCR read) say so in the dialog.

- **Dates without a year** ("3 November") use the next upcoming 3 November and the dialog says so. Past dates are allowed but flagged.
- If no app can handle an action, a message says so instead of failing silently.

| Check | Result |
|---|---|
| Tests | 96 JVM and 38 on-device, all pass |
| Planner (14 tests) | all-day vs timed, yearless dates, leap day, past dates, titles, which actions each fact offers |
| Intents (5 tests, on device) | calendar extras and all-day flag, `ACTION_DIAL` not `CALL`, geo query encoding, share chooser |
| Real UI, tap Add to calendar, Cancel | nothing launched (Munin stayed in front) |
| Real UI, confirm Call | Google Dialer opened with `+91 98765 43210`; no call placed |
| Real UI, confirm Open in maps | Google Maps launched |
| Real UI, confirm Add to calendar | Google Calendar launched (see limit below) |

### Limits

- **I did not see the calendar's pre-filled form.** This emulator has no Google account, so Google Calendar immediately redirects
  to account setup. The intent's contents are checked in a test and the launch is confirmed, but the on-screen form still needs a
  look on a phone with a calendar account. The same goes for the maps search results and the share sheet (not exercised in the UI).
- All values come from OCR. Dialogs say so, but a wrong read can still reach another app if you confirm without checking.
- Munin does not check that a maps app, dialer or calendar exists beforehand (Android 11+ hides that without extra manifest
  declarations); it handles the failure when launching.
- The detail screen is deliberately small: no text editing, no correcting facts, no deleting.

Bugs found on the way: `AnswerFormat.display` threw on a malformed stored date (now falls back to the stored text); address labels
kept their colon ("Address::"), fixed and re-extracted via `FactExtractor.VERSION = 2`.

## Step 6: payment screenshot ledger

UPI payment screenshots are recognised by rules (`extract/PaymentExtractor`): a UPI signal (UPI / UTR / an app name / a UPI id)
plus a payment cue ("Paid to", "Payment successful", ...) plus evidence (a reference label or a currency marker). A receipt,
bill or note that only mentions "paid to" is not a payment. Amount, payee, date and time, and the reference number
(UTR / UPI ref, preferred over a generic "Transaction ID") are read per label rather than per position, because apps lay the
same fields out differently. Failed, pending and *received* payments are recognised and kept out of spending.

**Totals are exact.** Money is stored as whole paise (`Long`), so a total is a plain integer sum with no float rounding
(a test adds 10p + 20p + ...). Every payment screenshot lands in exactly one bucket, and only the first is summed:

| Bucket | Why it is there |
|---|---|
| **Counted** | readable, successful, paid by you, amount read with explicit evidence, not a duplicate |
| Needs a check | the amount was a guess (the ₹ sign was not read); you can Include or Exclude it |
| Duplicates | the same payment screenshotted again, matched by reference (or by a full timestamp when there is none) |
| Failed / pending / received | not money you spent |
| Could not be read | recognised as a payment but missing an amount or a dated month, with the reason |
| Excluded by you | your override; it survives re-extraction |

The Ledger tab shows the monthly chart, the selected month's exact total, the counted screenshots with an Exclude button, top
payees, and every bucket above. It is titled "Spending from your screenshots" with a note that it is not total spending.
"how much did I spend in September" (also in Hindi/Telugu, or "this month", "last month") is answered from the same numbers, with
how many flagged and unreadable screenshots were left out.

| Check | Result |
|---|---|
| Tests | 120 JVM, 43 on-device, all pass |
| Synthetic corpus: 60 payments x 3 layouts x 5 currency styles, plus 10 re-screenshotted duplicates, 12 failed/pending/received/date-less | every field exact; monthly and overall totals equal the true sums to the paisa; 10 duplicates, 9 not-spending, 3 unreadable |
| Real OCR on 9 synthetic payment screenshots (emulator) | 9/9 detected as payments, 44 of 45 fields exact (the miss: a failed payment's time) |
| Real UI | "how much did I spend in September" -> ₹630 (₹450 + ₹180) with 2 flagged and 1 unreadable noted; Including the flagged ₹1,275.50 payment -> ₹1,905.50, all months ₹2,000.50 |
| Upgrade from a real schema-v2 database (43 items) | migrated in place, every indexed item re-extracted from saved text |

### What real OCR taught us (and the fixes)

- ₹ came back as `३` (a Devanagari digit), so "₹180" was read as 3,180. Digit runs that mix scripts are no longer converted,
  so it reads 180 and, because the rupee sign was not read, is flagged "needs a check" rather than counted. In this run 3 of 9
  amounts were flagged that way (two of them correct), so the guard costs recall but protected the one that was wrong.
- "UPI transaction ID" came back as `UP transaction ।D` and `UPI transaction lD`; the reference label now tolerates I/l/1/|/danda.

### Limits

- **Only synthetic layouts have been tested.** The three layouts (amount first / status first / label then payee) are written from
  general knowledge of UPI receipts, not from real app screenshots, and real ones will differ. Rules are label-driven to cope, but expect misses
  until they are tried on real screenshots (blanked of private details).
- Payee extraction is English-only; Hindi/Telugu payment screens would be recognised only if they also contain the English UPI wording.
- Without a reference number, only an exact amount + payee + date + time match counts as a duplicate. Two real purchases of the same
  amount from the same payee in the same minute would be merged; two screenshots with no time would not be merged at all.
- A screenshot with no readable date cannot be placed in a month, so it is listed as unreadable and never summed.
- OCR can still misread digits in a way no confidence flag reveals (e.g. a 5 read as 6); the source image is one tap away from every row.
- The ledger covers screenshots only: no bank access, no SMS, nothing outside the images indexed on this phone.

## Step 7: voice query

A **Speak** button next to the search box, with an English / हिन्दी / తెలుగు choice. Speech goes to Android's `SpeechRecognizer`:
on Android 12+ the **on-device recognizer** is used when the phone has one (it cannot fall back to the network); otherwise the
system recognizer is asked to prefer offline, which is a request, not a guarantee. The text appears in the (editable) search
box while you talk and the search runs once on the final text. Munin asks for the microphone only when Speak is pressed, never
records otherwise, and stores no audio. Munin still has no internet permission: if audio ever goes online, the phone's own speech
service does it, which is why the UI says so.

**The UI is honest about offline.** Under the buttons a line states what the phone's speech service reported for the selected
language (Android 13+ `checkRecognitionSupport`): pack installed (speech stays on the phone) / not installed (audio may go over the
internet via the speech service) / downloading / online-only / not offered / unknown on older Android. A generic failure adds the
likely cause when the pack is known to be missing. Typed search is always fully offline, and says so.

| Check | Result |
|---|---|
| Tests | 145 JVM and 45 on-device, all pass |
| Session state machine (fake recognizer, fake clock) | normal flow, one attempt at a time, stop waits for the text, blank result = nothing heard, cancel and stale callbacks ignored, every watchdog |
| Emulator, real speech service | support check answers: English and Hindi on-device but **packs not installed**, Telugu **not offered**; the UI says exactly that |
| Permission flow | prompt appears on first Speak; Deny -> "Microphone permission is needed… you can still type"; Allow -> listening starts |
| No pack installed (real recognizer) | the service fails or goes silent; the app ends with a clear message naming the missing pack instead of hanging |

### What a real recognizer taught us

The emulator's speech service logged `LANGUAGE_PACK_ERROR` internally and, in some runs, **never told the app**: the screen sat on
"Listening…" and Stop gave a vague "stopped unexpectedly". Voice now has watchdogs (8 s to start, 20 s of silence before an
automatic stop, 6 s from Stop to a result) that end in "the speech service did not respond; this often means the offline language
pack is not installed", plus the pack hint above.

### Limits (please read)

- **I have not heard a single transcription work.** This emulator has no microphone input and no installed language pack, so the
  path from speech to text to search is covered only by the fake-recognizer tests and by seeing the real service's failures
  handled. **Test voice on a real phone, with the language pack installed, before demoing it.**
- **Telugu voice depends entirely on the phone.** The emulator's service does not list Telugu. On a real phone it varies by device
  and Google speech-services version.
- **Mixed-language speech** (Telugu + English in one sentence) is transcribed in the one language you pick, so the other
  part may come out garbled. Edit the text, or type.
- **"Offline" for voice is only true with the pack installed.** Without it the phone's speech service may use the internet.
  Android 12 and below cannot report pack status at all (shown as unknown).
- Munin does not download language packs (it has no network access); installing them is done in the phone's speech settings.

## Evaluation harness

A labelled test set and a repeatable measurement; full write-up, tables and chart in [docs/EVALUATION.md](docs/EVALUATION.md).

- **Corpus:** 300 synthetic documents (243 English, 32 Hindi, 25 Telugu): 11 hand-built anchors plus 289 template fillers of the same types, so
  every query has confusable near-misses. **Queries:** 60 = 11 intents x 5 styles (English, Telugu script, Hindi, Roman Telugu, mixed) + 5
  questions with no answer. The Telugu/Hindi/Roman/mixed wording was written by the author and **needs native-speaker review**.
- **Two modes:** perfect text (isolates retrieval from OCR) and real ML Kit OCR on rendered images. Three configurations: keywords only, meaning
  only, merged.
- `tools/eval/run_eval.sh` runs it on a connected device or emulator (about 2 minutes); `report.py` computes recall@1/@5, MRR, value-question
  accuracy, field extraction, ledger agreement and latency from the raw results in `tools/eval/results/`.

| Mode | Config | Recall@1 | Recall@5 | MRR |
|---|---|---:|---:|---:|
| Perfect text | Keywords / Meaning / **Merged** | 49% / 69% / 58% | 51% / 85% / 67% | 0.50 / 0.77 / 0.62 |
| Real OCR | Keywords / Meaning / **Merged** | 47% / 67% / 56% | 49% / 76% / 71% | 0.48 / 0.71 / 0.63 |

The numbers are an **untuned baseline**: no logic was changed after seeing them. Limits (synthetic, small, not native-reviewed, no held-out
set, emulator timing) are listed in the write-up.

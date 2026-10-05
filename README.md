# Munin

Offline, on-device semantic search for screenshots, document photos and PDFs on Android, in Telugu, Hindi,
English, Roman-script Telugu, or a mix. No INTERNET permission; models are bundled in the APK.

Status: **step 3 of 7** (search). Photos are indexed (step 2) and searchable by meaning and by keywords, merged.
No answer extraction, actions, ledger or voice yet.

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

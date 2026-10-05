## Telugu text in images: a second reader (Tesseract)

ML Kit has no Telugu model, so Telugu documents were unreadable (round-1 baseline: 9% of their fields found, character error rate about 66%). A bundled
Tesseract 5 reader with Telugu and English data (`tessdata_fast`, Apache 2.0; about 7 MB of data and 13 MB of library) was added as an optional **second
opinion** behind the `OcrEngine` interface. Its result replaces ML Kit's only if it actually looks Telugu (at least 5 Telugu letters making up at least 20% of
its letters), so it cannot overwrite good English or Hindi text. Zero-width joiners that Tesseract inserts inside Telugu words are removed so keyword search
matches. Three policies were declared before any measurement: **T0** ML Kit only; **T1** Tesseract when ML Kit looks unsure (mean confidence under 0.75, any
dropped line, or no text); **T2** Tesseract on every image. The selection rule is in `report.py` (`select_telugu`): pooled over the seen sets, a policy is eligible
if Telugu-document field extraction rises by at least 20 points, English+Hindi extraction falls by at most 1 point, and mean merged MRR falls by at most 0.01.

{{TELUGU_SELECTION}}

{{TELUGU_SEEN}}

### What it showed

1. **It works, on clean synthetic Telugu.** With T1, Telugu documents' fields found went from 9% to 97% (dev 98%, held-out 98%, fresh 96%), character error rate from about 66%
   to 3.5-6%, and Telugu-target queries found in the top 5 went from 3 of 25 to 18 of 25 across the three sets; mean merged MRR rose from 0.70 to 0.76.
2. **T1 does no harm to English or Hindi** (identical extraction and character error rate on every set), and it only ran Tesseract on 8% of documents, so the median OCR
   time did not change. **T2 (always) is worse for Hindi**: character error rate rose from about 1.5% to 7-9% and Hindi fields found fell by 2-3 documents per set, because the
   Telugu reader is not meant for Devanagari and replaces ML Kit's better text on some images; it is also the slowest. The pre-declared rule therefore selected T1.
3. **T2 was also eligible under the rule** (it has the same 97% Telugu extraction, and its English+Hindi extraction of 92% is within the 1-point tolerance of 93%, with merged MRR 0.79 vs 0.76), so
   the tie went to T1 as the faster policy. Its Hindi damage (point 2) is a reason to be glad of that tie-break rather than a failed criterion.

### Limits

- **This is clean, computer-rendered Telugu in a standard font.** Real phone photos of Telugu documents (handwriting, stylised fonts, skewed or dim photos, mixed
  scripts, small text) will be much harder; Tesseract's `tessdata_fast` Telugu model is known to be weaker than its Latin model. Expect a large gap between these numbers and
  real life.
- **The final measurement on a new document set (`ocrtest`) has not been run**, so the choice of T1 was made on the three seen sets only and has not been confirmed on data it
  never saw. The switch therefore ships **off by default**, labelled experimental in the app; turning it on uses T1.
- The Telugu reader adds about 20 MB to the APK and loads on first use.
- The amount sanity check (a second OCR read for the rupee-sign misread) has not been started.

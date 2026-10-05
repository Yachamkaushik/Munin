# Amount sanity check: pre-registration

Written **before** any extraction code was changed and before the two unseen sets were read by the reader.

## What the data said first (replaying recorded OCR through the current extractor, JVM, no device)

The three seen sets (dev, heldout, fresh) have recorded OCR text. Through the *current* extractor, of 424 documents with a true amount:

- Hindi "rupee sign read as a digit" (`२1,150`) is **already handled** by the mixed-digit rule. The older result files predate that fix, so an earlier claim of "21,150 instead of 1,150" described the old extractor.
- The real remaining damage is **look-alike digits from another script** inside English amounts: `Rs ৪,000` (a Bengali digit that looks like 8), `Rs 2,69৪`, `Rs ৪,6০০` (Bengali zero). Result today: no amount found (about 11 documents), or a **truncated amount read with full confidence** (`Rs 2,69৪` gives 269, `Rs 1,৪০9` gives 1).
- The English payment screenshots where the rupee sign is read as a leading `2` (`23,045` for 3,045) already come out as a low-confidence guess (0.45), but give the user only the wrong number.
- Damaged labels (`देयशाशिः 765`) and Telugu text are separate OCR problems and are **out of scope** here.

All three seen sets were looked at while writing this, so none of them is a clean test of a fix designed from them.
`ocrtest` and `ocrtest2` (300 documents each, same templates, different seeds) have never been read by the reader and were not looked at.

## Variants (declared in code as `AmountSanity.Mode`)

| Mode | What it does |
|---|---|
| `OFF` | Today's extractor. |
| `LOOKALIKE` | Inside a digit run that also holds ASCII digits, the two look-alikes seen (Bengali `৪` -> 8, `০` -> 0) are repaired. A repaired amount is a guess from damaged text, so its confidence is capped at 0.55 ("check this"). |
| `GUARD` | `LOOKALIKE`, plus: if a number still touches a foreign digit that cannot be repaired (inside or after the number), it is read as damaged and capped at 0.3 instead of being believed at 0.9. |
| `ALTERNATIVE` | `GUARD`, plus: a bare amount line (no currency sign, confidence 0.45) whose first digit is 2 or 3 also offers the number without that digit as a second, lower-confidence candidate, because a rupee sign is often read as a 2. Never replaces the first reading. |

A **second OCR read** (re-reading flagged documents with the other bundled recogniser) was considered and is **not built or measured**: the damage found is a specific, repeatable glyph confusion that a deterministic repair handles for free, a second pass would cost an OCR run per flagged document, and it was not needed to explain any failure above except the leading-2 case, which `ALTERNATIVE` covers by showing both values.

## Outcomes per document (the document's top amount = highest confidence, first on ties; "confident" = confidence >= 0.6)

correct-confident, correct-flagged, wrong-confident, wrong-flagged, none. Also "truth offered" = the true value is among the amounts shown at all.

## Selection rule (fixed now)

On the seen sets plus `ocrtest` (real OCR), choose the mode with:
1. the fewest **wrong-confident** documents; ties then fewest wrong-confident-or-wrong-flagged; ties then most correct (confident or flagged);
2. subject to **no regressions**: no document that is correct-confident under `OFF` may become anything else, on real OCR **and** on true text (oracle), and
3. **false flags** (correct under `OFF` and confident, but flagged under the mode) must be at most 1% of correct documents.

If no mode beats `OFF` on rule 1, `OFF` stays.

## Final measurement

`ocrtest2`, real OCR, once, `OFF` against the chosen mode. Results and the honest limits go in `docs/EVALUATION.md` and the README.

---

# Results (added after the measurement)

## Selection (dev, heldout, fresh and ocrtest together: 561 documents with a true amount, real OCR)

| Mode | wrong and confident | wrong, flagged | correct (confident / flagged) | no amount | truth shown at all | regressions | false flags |
|---|---:|---:|---:|---:|---:|---:|---:|
| OFF (before) | 3 | 7 | 390 / 66 | 95 | 456 | 0 | 0 |
| **LOOKALIKE (chosen)** | **1** | 7 | 390 / 86 | 77 | 476 | 0 | 0 |
| GUARD | 1 | 7 | 390 / 86 | 77 | 476 | 0 | 0 |
| ALTERNATIVE | 1 | 7 | 390 / 86 | 77 | 480 | 0 | 0 |

On true text (oracle) every mode is identical to OFF: 561 of 561 correct and confident, so the check never damages a clean reading.
The rule picked **LOOKALIKE**: it ties GUARD and ALTERNATIVE on the first criteria, and the simplest mode wins a tie. GUARD changed nothing on this data (no number ended in a digit that could not be repaired), and ALTERNATIVE only added a second candidate in 4 documents, which the rule does not reward.

## Final, once, on the untouched `ocrtest2` (137 documents with a true amount, real OCR)

| Mode | wrong and confident | wrong, flagged | correct (confident / flagged) | no amount | truth shown at all | regressions |
|---|---:|---:|---:|---:|---:|---:|
| OFF (before) | **2** | 2 | 96 / 16 | 21 | 112 | 0 |
| **LOOKALIKE (shipped)** | **0** | 3 | 96 / 18 | 20 | 114 | 0 |

The two wrong-and-confident amounts before were truncations: `Rs 1,90` for 1,908 and `Rs 8` for 857. Both are now whole and flagged. Clean amounts keep their confidence (96 confident before and after).

## Honest limits

- **Small numbers.** 2 wrong-and-confident documents became 0 on the final set, and 3 became 1 on the selection sets. The effect is real in direction and consistent across five sets, but these are small counts on synthetic documents.
- **The damage is specific to the reader's output on this font.** Only the two look-alikes seen are mapped (Bengali 4 drawn like 8, Bengali 0 drawn like 0). Other glyph confusions on real phones are not covered, and nothing was measured on real phone screenshots.
- **A repaired amount is a guess and is flagged:** confidence 0.55 (the answer says a digit was corrected, quotes what was read and says to check the image). It never silently replaces a value.
- **Not fixed (out of scope):** a dropped digit (`₹2,000` for 21,000), a damaged label (`देयशाशिः 765`, 3 documents), amounts that were not read at all, Telugu, and a rupee sign read as a leading digit in bare payment amounts (`23,045` for 3,045; in `ocrtest` also `73,029`). The last is already a low-confidence guess (0.45), not a confident error. ALTERNATIVE, which would also show the number without the leading digit, was pre-registered for 2 and 3 only and not selected; the ocrtest sets show 7 as well, so a future round would have to cover it.
- **A look-alike touching a letter is left alone** (`Rs৪,000` glued, or `০ct` for "Oct"): repairing those could damage dates and words.
- **Second OCR read: considered, not built, not measured** (see the pre-registration).
- The failures above were looked at in dev, heldout, fresh and ocrtest while designing; only `ocrtest2` was untouched, and it was read exactly once, after the mode was fixed.

## Fix experiments: dev set, selection, then a held-out check

The baseline above exposed two problems, so two fixes were tried. To keep this honest the procedure was fixed **before** any number
from the second set existed:

1. A second, **held-out** corpus was generated first: 300 new documents (new seed) and 60 new queries built around 11 *new* intents
   (rent at Gokul Residency, train to Madurai, an insurance premium date, TCP notes, a dentist's phone, a payment to Kamat Medical
   Store, a gongura recipe, a Hindi electricity bill, Hindi chemistry notes, a Telugu dental appointment, Vijayanagara notes) plus 5
   "no such document" questions ("bike insurance"). The original 300 documents / 60 queries became the **dev** set (their content is
   unchanged).
2. **Five retrieval variants and two answer gates were declared up front**, each switchable through `SearchOptions` / `AnswerOptions`
   (B0 = shipped behaviour; S1 = stopwords; C1 = coverage gate; S2 = both; S3 = both plus a keyword weight of 0.5; A1 = grounding).
3. A **selection rule was written into `report.py` before the held-out run**: the retrieval variant with the highest mean merged MRR on the
   dev set (over perfect text and real OCR) that does not lose more than one same-language rank-1 versus B0; the answer gate with the fewest
   wrong plus made-up answers, ties to more correct answers.
4. The held-out set was then measured once, for all variants, and the app now ships the dev-selected ones.

{{SELECTION}}

![Recall@5 by query style: meaning only, merged as shipped, merged fixed](eval_fixes.png)

### What the experiment showed

1. **The coverage gate is the fix that works, and it replicated on new data.** It requires a keyword hit to match at least half of the
   query's content words, so a lone shared word ("Vidya", "bill") or a Hindi particle no longer counts as a match. Held-out merged recall@5
   went from 73% to 87% with perfect text and from 64% to 80% with real OCR; across-language recall@5 from 50% to 77% and from 40% to 70%;
   same-language queries stayed at 100% / 92%. Stopwords alone helped less (+3 and +7 recall@5 points) and added nothing once the coverage
   gate was on; down-weighting the keyword list was no better than the gate.
2. **Merged still does not clearly beat meaning-only.** After the fix it is level with it. Perfect text: on the dev set merged is ahead at
   recall@1 (73% vs 69%) and MRR (0.79 vs 0.77) and tied at recall@5 (85%); on the held-out set meaning-only is ahead (recall@5 89% vs 87%,
   MRR 0.87 vs 0.84). Real OCR: a tie on the held-out set (65% / 80% / 0.70 for both) and merged marginally ahead on dev (MRR 0.72 vs 0.71).
   The honest summary is "merged is now about as good as meaning-only", not "better than either alone".
3. **The grounding gate removes every wrong and made-up answer, at a real cost in answers.** Held-out: wrong answers 1 -> 0, made-up answers
   to "bike insurance" 2 of 5 -> 0 of 5 (dev: 3 -> 0 and 2 -> 0). But correct answers fell from 10 to 6 of 25 (perfect text) and 8 to 5
   (real OCR), and on dev from 10 to 7 and 9 to 6. The cost has a known cause: the gate treats any question word that is rare in the
   collection and absent from the document as a missing topic, and it cannot tell a generic word from a topic word, e.g. "pay" when the
   receipt says "paid", Hindi "था", or the Telugu verb "కట్టాను". It was shipped exactly as measured in round 1; **round 2 below refines this gate and is what ships now.**

### Caveats specific to this experiment

- Dev and held-out were built by the same author with the same templates and the same query phrasing patterns, so the held-out set is
  *fresh* but not fully *independent*; and the held-out negative ("bike insurance") is deliberately the same trap as the dev one
  ("car insurance"), so it checks the fix more than it discovers new failures.
- Both sets are synthetic, small (one query is about 9 points within a style) and the non-English wording is unreviewed by native speakers.
- All five variants were also measured on the held-out set (shown above) for transparency, but only the dev-selected one was acted on; had
  a different variant been picked after looking, the held-out result would no longer be independent.

{{TABLES}}

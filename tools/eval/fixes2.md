## Round 2: the generic-word problem in the answer gate (fresh set)

Round 1 shipped a grounding gate that declined whenever a *rare* word of the question was missing from the document. It over-declined because
it could not tell topic words from function words ("था", "కి") and everyday payment verbs ("pay" vs "paid"). This round tried to win those
answers back without bringing wrong ones back. Same discipline as round 1, because **both earlier sets had by now been seen** and so could
no longer judge a fix designed from their failures:

1. A **third corpus ("fresh")** was generated first: 300 new documents, 60 new queries (10 intents x 5 styles + 2 no-answer questions x 5), new
   seed. Six of its ten intents are value questions, written in natural phrasing with payment verbs ("how much did I pay", Telugu
   "ఎంత కట్టాను", Hindi "कितनी भरी"). Its non-English wording is the author's and unreviewed.
2. **Three variants and a selection rule were fixed in code before any fresh result existed**: A1 (the round-1 gate), A2 (A1 with the question's
   topic taken without Hindi/Telugu/Roman function words), A3 (A2 plus a list of generic payment verbs). Rule: over the already-seen sets (dev and
   held-out, both modes), the gate with the fewest wrong plus made-up answers, then the most correct answers, then the simpler gate.
3. The fresh set was then measured once.

{{GATE_SELECTION}}

{{SEEN_TABLES}}

{{FRESH_TABLES}}

### What round 2 showed

1. **A2 wins back answers consistently.** Against A1 it adds 2 to 3 correct answers in every one of the six runs (dev, held-out, fresh, in both
   modes): fresh 6 -> 9 of 30 with perfect text and 5 -> 7 with real OCR. On the seen sets it did so with zero wrong and zero made-up answers.
2. **On the fresh set it is not perfectly safe.** With real OCR A2 showed **one wrong answer** that A1 did not (`q18`, Hindi "how much was the
   Bharat Gas cylinder bill"). The document was the right one, but the extracted amount was 21,150 instead of 1,150: an extra leading "2" where the rupee sign
   is, which is how the OCR garbled that sign in other runs (the raw OCR text is not stored, so this is an inference). The
   original gate gave the same wrong answer, and A1 avoided it only by accident, because its topic still contained the function word "आया" so it
   declined. That is the known rupee-sign weakness, which a topic gate cannot catch.
3. **Against the original gate**, A2 on the fresh set gives up 2 correct answers (11 -> 9 perfect text, 9 -> 7 real OCR) and avoids 3 wrong ones (3 -> 0
   perfect text; 3 -> 1 real OCR). It is the shipped default.
4. **The generic-verb list (A3) changed nothing in any of the six runs** and is not used. The reason is a property of the rarity rule: a word is
   "rare" if it appears in at most 5% of chunks (never fewer than 3 documents), so in a few-hundred-document collection "pay" is not rare and cannot
   cause a decline. It still does in a tiny collection: with only 8 documents (the unit-test corpus) "when did I pay the hostel fee" is declined
   because the receipt says "paid". With a small personal library the same would happen.
5. **The made-up-answer failure was re-tested only weakly.** The fresh no-answer questions (gym membership, passport renewal) have no near-miss document,
   so even the original gate answered them correctly (0 of 10 made-up). The failure mode that mattered, "car/bike insurance" answered from a health
   insurance premium, lives in the dev and held-out sets, where A2 gives 0 of 5 made-up answers.
6. **Round 1 replicated a third time.** On the fresh set the keyword coverage gate lifts merged recall@5 from 64% to 86% (perfect text) and from 58% to 68% (real
   OCR), exactly level with meaning-only search (86% and 68%), with across-language recall@5 35% -> 77% and 31% -> 50%. Merged still does not beat
   meaning-only. Stopwords plus the coverage gate (S2) came out marginally higher on the fresh set (88% and 70%, one query each) but it was not the
   dev-selected variant, so it was not adopted; that would be choosing after looking.

### Two measurement bugs found along the way

Both were in the evaluation tooling and both are fixed; neither affects the published numbers, because the affected runs were discarded:

- The harness and runner chose the corpus files for the `heldout` set only, so a first "fresh" run silently used the **dev** manifest and dev images.
  It was caught because its numbers were identical to the dev numbers to the digit. (No fresh result had been seen at that point, so the selection was
  not influenced.) The harness now fails if the manifest is not the requested set, and in image mode if the OCR'd text does not match the manifest.
- That same mix-up put dev images in the fresh image folder; they were re-rendered from the fresh manifest.

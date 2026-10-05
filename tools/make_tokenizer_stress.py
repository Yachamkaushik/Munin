"""Deterministic stress corpus for the Kotlin tokenizer (token ids only, no embeddings).

Mutations of the sample sentences plus random strings drawn from the scripts
Munin cares about and from nasty Unicode (full-width, ligatures, controls,
emoji, combining marks). Output: tools/reference/tokenizer_stress.json
"""
import json, pathlib, random
import sentencepiece as spm

ROOT = pathlib.Path(__file__).resolve().parent.parent
sp = spm.SentencePieceProcessor(model_file=str(ROOT / "models/sentencepiece.bpe.model"))
rng = random.Random(20261004)
base = [s["text"] for s in json.loads((ROOT / "tools/sentences.json").read_text())]

ranges = {
    "latin": (0x20, 0x7E), "latin1": (0xA0, 0x24F), "devanagari": (0x900, 0x97F),
    "telugu": (0xC00, 0xC7F), "fullwidth": (0xFF01, 0xFF5E), "ligature": (0xFB00, 0xFB06),
    "cjk": (0x4E00, 0x4F00), "emoji": (0x1F600, 0x1F64F), "punct": (0x2000, 0x206F),
    "combining": (0x300, 0x36F), "super": (0x2070, 0x209F), "enclosed": (0x2460, 0x24FF),
    "control": (0x01, 0x1F), "arrows": (0x2190, 0x21FF), "tamil": (0xB80, 0xBFF), "arabic": (0x600, 0x6FF),
}
names = list(ranges)

def rand_chars(n):
    out = []
    for _ in range(n):
        lo, hi = ranges[rng.choice(names)]
        out.append(chr(rng.randint(lo, hi)))
    return "".join(out)

def mutate(t):
    k = rng.randint(0, 5)
    if k == 0:
        a = rng.randint(0, len(t)); b = rng.randint(a, len(t)); return t[a:b]
    if k == 1: return t + " " * rng.randint(0, 3) + rng.choice(base)
    if k == 2: return rng.choice(base) + rng.choice(["", " ", "\n", "\t", "  "]) + t
    if k == 3:
        i = rng.randint(0, len(t)); return t[:i] + rand_chars(rng.randint(1, 4)) + t[i:]
    if k == 4: return t.upper() if rng.random() < .5 else t.lower()
    return " ".join(rng.sample(t.split(), len(t.split())))

texts = set(base)
while len(texts) < 700: texts.add(mutate(rng.choice(base)))
while len(texts) < 1500: texts.add(rand_chars(rng.randint(1, 30)))
texts = sorted(texts)

records = [{"text": t, "ids": [i + 1 if i > 0 else 3 for i in sp.encode(t)]} for t in texts]
(ROOT / "tools/reference/tokenizer_stress.json").write_text(json.dumps(records, ensure_ascii=False))
print(len(records), "stress strings")

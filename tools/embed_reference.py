"""Reference embeddings for Munin (Step 1).

Truth source for the Android port: sentencepiece (Python) + ONNX Runtime on
multilingual-e5-small int8. Writes tools/reference/reference.json with, for every
sentence and both prefixes: token ids (HF/fairseq numbering), and the
mean-pooled, L2-normalised 384-d vector.

XLM-R id mapping: <s>=0 <pad>=1 </s>=2 <unk>=3, sp piece i (i>=1) -> i+1.
"""
import json, pathlib, sys
import numpy as np, onnxruntime as ort, sentencepiece as spm

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAX_LEN = 512

sp = spm.SentencePieceProcessor(model_file=str(ROOT / "models/sentencepiece.bpe.model"))
# The int8 model is sensitive to ORT's fused kernels (cosine vs. default optimization drops to ~0.991 on some
# inputs), so the optimization level is pinned to BASIC here and in E5Embedder.kt. Keep them in sync.
opts = ort.SessionOptions()
opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
sess = ort.InferenceSession(str(ROOT / "models/model_int8.onnx"), opts, providers=["CPUExecutionProvider"])


def tokenize(text: str) -> list[int]:
    ids = [i + 1 if i > 0 else 3 for i in sp.encode(text)]  # sp unk(0) -> 3
    ids = ids[: MAX_LEN - 2]
    return [0] + ids + [2]


def embed(ids: list[int]) -> np.ndarray:
    a = np.array([ids], dtype=np.int64)
    out = sess.run(None, {"input_ids": a, "attention_mask": np.ones_like(a),
                          "token_type_ids": np.zeros_like(a)})[0][0]
    v = out.mean(axis=0)  # all tokens attended, so plain mean == masked mean
    return v / np.linalg.norm(v)


sentences = json.loads((ROOT / "tools/sentences.json").read_text())
# Long text to exercise the 512-token truncation path.
sentences.append({"id": "long-01", "lang": "edge",
                  "text": " ".join(s["text"] for s in sentences if s["lang"] in ("en", "hi", "te")) * 4})

records = []
for s in sentences:
    for prefix in ("passage: ", "query: "):
        ids = tokenize(prefix + s["text"])
        records.append({"id": s["id"], "lang": s["lang"], "prefix": prefix.strip(": "),
                        "text": s["text"], "input_text": prefix + s["text"],
                        "ids": ids, "vector": [round(float(x), 7) for x in embed(ids)]})

(ROOT / "tools/reference/reference.json").write_text(json.dumps(
    {"model": "multilingual-e5-small-int8", "dim": 384, "max_len": MAX_LEN, "records": records},
    ensure_ascii=False, indent=1))
print(f"wrote {len(records)} records; longest = {max(len(r['ids']) for r in records)} tokens")

# Informational: cross-check against the HF fast tokenizer if installed.
try:
    from tokenizers import Tokenizer
    hf = Tokenizer.from_file(str(ROOT / "models/tokenizer.json"))
    def hf_ids(t):
        ids = hf.encode(t).ids  # includes <s> ... </s>
        return ids if len(ids) <= MAX_LEN else ids[: MAX_LEN - 1] + [2]
    bad = [r["id"] + "/" + r["prefix"] for r in records if hf_ids(r["input_text"]) != r["ids"]]
    print("HF fast tokenizer mismatches vs sentencepiece:", bad or "none")
except ImportError:
    print("(pip install tokenizers to cross-check against the HF tokenizer)")

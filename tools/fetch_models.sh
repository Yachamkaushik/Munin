#!/bin/sh
# Downloads multilingual-e5-small (int8 ONNX + SentencePiece) from Hugging Face.
# Run once on a networked machine; the app itself never touches the network.
set -e
cd "$(dirname "$0")/.."
B=https://huggingface.co/intfloat/multilingual-e5-small/resolve/main
mkdir -p models app/src/main/assets/models
curl -L -o models/model_int8.onnx "$B/onnx/model_qint8_avx512_vnni.onnx"
curl -L -o models/sentencepiece.bpe.model "$B/sentencepiece.bpe.model"
curl -L -o models/tokenizer.json "$B/tokenizer.json"   # only for the Python cross-check
cp models/model_int8.onnx models/sentencepiece.bpe.model app/src/main/assets/models/

# Tesseract language data for the optional Telugu text pass (tessdata_fast, Apache 2.0)
mkdir -p app/src/main/assets/tessdata
T=https://github.com/tesseract-ocr/tessdata_fast/raw/main
curl -L -o app/src/main/assets/tessdata/tel.traineddata "$T/tel.traineddata"
curl -L -o app/src/main/assets/tessdata/eng.traineddata "$T/eng.traineddata"

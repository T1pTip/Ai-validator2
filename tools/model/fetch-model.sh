#!/usr/bin/env bash
# Downloads the pinned Community Forensics ViT-S/384 ONNX export (MIT licence) and verifies it.
# Official weights: OwensLab/commfor-model-384 (Park & Owens, CVPR 2025). ONNX export:
# aiwithoutborders-xyz/CommunityForensics-DeepfakeDet-ViT, pinned to an exact revision.
# The FP32 file is used: the card warns the INT8/Q4 exports can diverge by 10-70 points.
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="aiwithoutborders-xyz/CommunityForensics-DeepfakeDet-ViT"
REV="ac6ee457bea904a373065754107451793b56db00"
SHA256="a42c7d740fbb345ba9a26d469b22f301d73089ce3c6da993877ed2b6965a8ba1"
OUT="app/src/main/assets/models/commfor_vit_s384.onnx"
mkdir -p "$(dirname "$OUT")"
if [ -f "$OUT" ] && echo "$SHA256  $OUT" | sha256sum -c --status; then echo "model already present"; exit 0; fi
SRC="${1:-}"
if [ -n "$SRC" ]; then cp "$SRC" "$OUT.tmp"
else curl -fsSL --retry 3 -o "$OUT.tmp" "https://huggingface.co/$REPO/resolve/$REV/onnx/model.onnx"; fi
echo "$SHA256  $OUT.tmp" | sha256sum -c -
mv "$OUT.tmp" "$OUT"
echo "model ready: $OUT"

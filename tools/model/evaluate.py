"""Evaluates the on-device detector (ONNX) on a labelled image folder.

usage: python3 -I tools/model/evaluate.py MODEL.onnx EVAL_DIR [--social]
EVAL_DIR holds images plus labels.csv (file,label,model,config; label is real/fake).
--social also scores a WhatsApp-like copy (long edge 1600, JPEG quality 75) of every image.
Prints accuracy at 0.5, ROC AUC, per-generator accuracy, and the score distribution.
"""
import csv
import io
import os
import sys
import time
from collections import defaultdict

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clip_reference as ref  # noqa: E402


def social_copy(img):
    img = img.convert("RGB")
    w, h = img.size
    scale = 1600 / max(w, h)
    if scale < 1:
        img = img.resize((round(w * scale), round(h * scale)), Image.BICUBIC)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=75)
    buf.seek(0)
    return Image.open(buf)


def auc(scores, labels):
    pos = [s for s, l in zip(scores, labels) if l == 1]
    neg = [s for s, l in zip(scores, labels) if l == 0]
    if not pos or not neg:
        return float("nan")
    wins = sum((p > n) + 0.5 * (p == n) for p in pos for n in neg)
    return wins / (len(pos) * len(neg))


def report(title, rows):
    labels = [1 if r["label"] == "fake" else 0 for r in rows]
    scores = [r["p"] for r in rows]
    acc = np.mean([(s > 0.5) == bool(l) for s, l in zip(scores, labels)])
    real_acc = np.mean([s <= 0.5 for s, l in zip(scores, labels) if l == 0] or [np.nan])
    fake_acc = np.mean([s > 0.5 for s, l in zip(scores, labels) if l == 1] or [np.nan])
    print(f"\n== {title}: n={len(rows)} acc={acc:.3f} real-correct={real_acc:.3f} "
          f"fake-caught={fake_acc:.3f} auc={auc(scores, labels):.3f}")
    for lo, hi in [(0, .1), (.1, .3), (.3, .5), (.5, .7), (.7, .9), (.9, 1.01)]:
        band = [l for s, l in zip(scores, labels) if lo <= s < hi]
        if band:
            print(f"   p in [{lo:.1f},{min(hi, 1):.1f}): n={len(band)} fake-share={np.mean(band):.2f}")


def main():
    model, folder = sys.argv[1], sys.argv[2]
    social = "--social" in sys.argv
    session = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
    rows = []
    with open(os.path.join(folder, "labels.csv"), encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            path = os.path.join(folder, row["file"])
            if not os.path.exists(path):
                continue
            rows.append(row)
    start = time.time()
    for row in rows:
        img = Image.open(os.path.join(folder, row["file"]))
        row["p"] = ref.sigmoid(session.run(None, {"pixel_values": ref.to_tensor(img)})[0][0, 0])
        if social:
            row["p_social"] = ref.sigmoid(session.run(None, {"pixel_values": ref.to_tensor(social_copy(img))})[0][0, 0])
    print(f"scored {len(rows)} images in {time.time() - start:.1f}s")
    for config in sorted({r["config"] for r in rows}):
        report(f"config={config}", [r for r in rows if r["config"] == config])
    report("all", rows)
    if social:
        report("all, WhatsApp-like copies", [dict(r, p=r["p_social"]) for r in rows])
    per_model = defaultdict(list)
    for r in rows:
        if r["label"] == "fake":
            per_model[r["model"]].append(r["p"] > 0.5)
    print("\nfake images caught per generator (n>=3):")
    for name, hits in sorted(per_model.items(), key=lambda kv: -len(kv[1])):
        if len(hits) >= 3:
            print(f"   {name:40s} {np.mean(hits):.2f} (n={len(hits)})")


if __name__ == "__main__":
    main()

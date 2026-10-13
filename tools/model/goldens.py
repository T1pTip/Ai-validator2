"""Writes reference scores for the instrumentation test (LocalDetectorTest).

usage: python3 -I tools/model/goldens.py MODEL.onnx FIXTURE_DIR OUT_JSON
For each image: the logit and probability from the Pillow reference pipeline, and the SHA-256
of the decoded RGB pixels (to check that Android decodes the same pixels as Pillow).
"""
import hashlib
import json
import os
import sys

import numpy as np
import onnxruntime as ort
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clip_reference as ref  # noqa: E402

model, folder, out = sys.argv[1:4]
session = ort.InferenceSession(model, providers=["CPUExecutionProvider"])
goldens = []
for name in sorted(os.listdir(folder)):
    if not name.lower().endswith((".png", ".jpg", ".jpeg", ".webp")):
        continue
    img = Image.open(os.path.join(folder, name))
    rgb = np.asarray(img.convert("RGB"), dtype=np.uint8)
    logit = float(session.run(None, {"pixel_values": ref.to_tensor(img)})[0][0, 0])
    goldens.append({
        "file": name, "width": img.size[0], "height": img.size[1],
        "rgbSha256": hashlib.sha256(rgb.tobytes()).hexdigest(),
        "logit": round(logit, 6), "probability": round(ref.sigmoid(logit), 6),
    })
    print(f"{name:24s} {img.size} logit={logit:+.4f} p={ref.sigmoid(logit):.4f}")
with open(out, "w") as f:
    json.dump(goldens, f, indent=1)

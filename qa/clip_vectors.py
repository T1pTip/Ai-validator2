"""Writes Pillow reference vectors for qa/ClipPreprocessorQa.kt.

usage: python3 qa/clip_vectors.py OUT_DIR
For every case: <name>.png (input), <name>.resized.rgb (Pillow bicubic output, interleaved RGB)
and <name>.tensor.f32 (little-endian float32, 3x384x384). Synthetic inputs are generated here;
qa/vectors/*.png are real photographs.
"""
import glob
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools", "model"))
import clip_reference as ref  # noqa: E402


def synthetic(w, h, seed):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:h, 0:w]
    base = np.stack([(x * 7 + y * 3) % 256, (x * y // 5) % 256, (255 - x * 2 + y) % 256], axis=-1)
    noise = rng.integers(-40, 41, size=(h, w, 3))
    edges = ((x // 9 + y // 13) % 2) * 120
    arr = np.clip(base + noise + edges[..., None], 0, 255).astype(np.uint8)
    return Image.fromarray(arr, "RGB")


def main(out):
    os.makedirs(out, exist_ok=True)
    cases = {
        "down_landscape": synthetic(1000, 700, 1),
        "down_portrait": synthetic(613, 1201, 2),
        "up_small": synthetic(200, 150, 3),
        "exact_short": synthetic(440, 520, 4),
        "square_odd": synthetic(997, 997, 5),
    }
    alpha = synthetic(500, 400, 6).convert("RGBA")
    alpha.putalpha(Image.fromarray((np.arange(500 * 400).reshape(400, 500) % 256).astype(np.uint8)))
    cases["rgba"] = alpha
    for path in sorted(glob.glob(os.path.join(os.path.dirname(__file__), "vectors", "*.png"))):
        cases["photo_" + os.path.splitext(os.path.basename(path))[0]] = Image.open(path)
    for name, img in cases.items():
        img.save(os.path.join(out, name + ".png"))
        np.asarray(ref.resize(img), dtype=np.uint8).tofile(os.path.join(out, name + ".resized.rgb"))
        ref.to_tensor(img).astype("<f4").tofile(os.path.join(out, name + ".tensor.f32"))
        print(name, img.size, "->", ref.resized_size(*img.size))


if __name__ == "__main__":
    main(sys.argv[1])

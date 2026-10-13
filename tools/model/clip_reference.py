"""Reference preprocessing for the Community Forensics ViT-S/384 detector.

Mirrors the model's CLIPImageProcessor config (shortest edge 440, bicubic, center crop 384,
CLIP mean/std) using Pillow directly, so it does not depend on a transformers version.
The Android port (ClipPreprocessor.kt) is checked against this module.
"""
import numpy as np
from PIL import Image

SHORT_EDGE = 440
CROP = 384
MEAN = np.array([0.48145466, 0.4578275, 0.40821073], dtype=np.float32)
STD = np.array([0.26862954, 0.26130258, 0.27577711], dtype=np.float32)


def resized_size(width, height):
    if width <= height:
        return SHORT_EDGE, SHORT_EDGE * height // width
    return SHORT_EDGE * width // height, SHORT_EDGE


def resize(img):
    img = img.convert("RGB")
    return img.resize(resized_size(*img.size), resample=Image.BICUBIC)


def to_tensor(img):
    """Returns a float32 array shaped (1, 3, 384, 384)."""
    r = resize(img)
    w, h = r.size
    left, top = (w - CROP) // 2, (h - CROP) // 2
    crop = np.asarray(r, dtype=np.uint8)[top:top + CROP, left:left + CROP, :]
    arr = crop.astype(np.float32) / np.float32(255.0)
    arr = (arr - MEAN) / STD
    return np.ascontiguousarray(arr.transpose(2, 0, 1)[None, ...], dtype=np.float32)


def sigmoid(x):
    return float(1.0 / (1.0 + np.exp(-x)))

# On-device visual check

## Model
- **Community Forensics ViT-S/384**: Jeongsoo Park and Andrew Owens, *Community Forensics: Using
  Thousands of Generators to Train Fake Image Detectors*, CVPR 2025 (arXiv:2411.04125).
  The official weights are `OwensLab/commfor-model-384` (MIT licence).
- **ONNX export used**: `aiwithoutborders-xyz/CommunityForensics-DeepfakeDet-ViT` (MIT), pinned
  to revision `ac6ee457bea904a373065754107451793b56db00`. The file is `onnx/model.onnx` (FP32,
  87,442,080 bytes, SHA-256 `a42c7d74…8ba1`).
  - The INT8 and Q4 exports are **not** used. The model card warns they can diverge from FP32 by
    10–70 points.
- **Fetching**: `tools/model/fetch-model.sh` downloads the model and verifies its SHA-256. The model
  is not committed to git.

## Pipeline (Android = reference)
1. Decode the image with no EXIF rotation, no colour-space conversion and no premultiplied alpha.
   This matches Pillow's default behaviour.
2. Resize so the shortest edge is 440. The long edge is truncated, as transformers does.
   - Pillow's antialiased bicubic filter is ported bit-exactly (`PillowBicubic`).
3. Centre-crop to 384, then normalise with the CLIP mean and std, in NCHW layout.
4. The model outputs a logit; sigmoid(logit) is the probability that the image is AI-generated.

### Verification
- `qa/ClipPreprocessorQa.kt` checks the resize against Pillow byte for byte, and the tensor to
  within 1e-5.
- `LocalDetectorTest` (emulator) checks the Android output against goldens. The goldens come from
  `tools/model/goldens.py`:
  - PNG: decoded pixels identical, and logit within 0.02;
  - JPEG: logit within 0.35, to allow for decoder rounding.

## Video
- Three frames are scored, spread across the video.
- The highest frame score is reported, and every frame's score is shown.

## Evaluation
- Run: `python3 -I tools/model/evaluate.py MODEL.onnx EVAL_DIR --social`.
- Data: a random sample of the OpenFake test split (ComplexDataLab/OpenFake, CC BY-NC 4.0, used
  here only for evaluation and not redistributed). The sample covers modern generators (GPT-image,
  Midjourney 7, Flux.2, Imagen 4 and others) and real photos.
- Results and the chosen thresholds are recorded below.

### Results (2026-10-13, 392 images: 171 real, 221 AI)

| Threshold | Real photos flagged | AI images caught | Precision |
|---|---|---|---|
| p ≥ 0.10 | 3 of 171 (1.8%) | 60 of 221 (27%) | 95% |
| p ≥ 0.50 | 1 of 171 (0.6%) | 39 of 221 (18%) | 97.5% |

- ROC AUC is 0.83.
- WhatsApp-like copies (long edge 1600 px, JPEG quality 75) score the same (AUC 0.83).
- **Detection rate by generator** (share of AI images caught):

| Generator | Caught |
|---|---|
| Midjourney 7 | 9% |
| GPT-image 1.5 | 7% |
| Flux.2 | 3% |
| Z-image | 7% |
| Illustrious (anime) | 62% |
| Reddit-posted AI images | 19% |

**Conclusion:** the model is conservative. When it says AI it is usually right, but it misses about
three quarters of modern AI images. A "no signs" answer is therefore weak evidence, and the app
says so and points to the cloud check.

**Bands used in the app** (`VisualBand`):

| Score | Band |
|---|---|
| ≥ 0.5 | likely AI |
| 0.1 – 0.5 | leans AI |
| < 0.1 | no signs |

**On-device verification:** in the emulator, Android logits matched the Python reference to within
3e-5 for every fixture (PNG, JPEG and WebP). Inference took about 300 ms per image on x86_64.

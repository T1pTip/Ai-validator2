# AI Origin Detector v0.9 — experimental Android source

Target: Samsung Galaxy S23 Ultra / 12GB RAM. Native Kotlin + Compose.

## Current installation status (2026-10-13)

Historical QA entries below describe earlier sessions. Signed release run
[38310829684](https://github.com/T1pTip/Ai-validator2/actions/runs/38310829684)
successfully built, linted, verified, installed and launched v0.17 on an API 35
x86_64 emulator. Physical S23 Ultra installation failed according to the owner;
the exact failure is not yet captured. The product remains experimental and
contains no trained visual AI-origin classifier.

v0.18 separates debug/release package IDs, preserves the existing release signer,
adds API 36 installation/launch checks and publishes installation evidence.
See [S23 installation investigation](qa/S23_INSTALLATION.md) for evidence,
safe diagnostic steps and verification limits. No Android protections are bypassed.

Stage 2 has no definition in the available repository or readable imported chat.
Its requirements must be recovered before that phase can be implemented faithfully.

## Implemented in source
- Android picker/share import for image/video.
- PNG textual metadata (tEXt, zTXt, iTXt), EXIF and video metadata.
- Limited video frame sampling and scene-change heuristic (not AI classification).
- Embedded text extraction and clipboard copy.
- Device profiling and approximately 10-second in-app status banner.
- Metadata evidence assessment that distinguishes descriptions from generation-tool strings, and **always labels metadata unverified**.
- No INTERNET permission, paid API, background upload or paid service.

## QA performed
- 8/8 PNG parser JVM fixture checks passed.
- 8/8 metadata assessment JVM checks passed.
- ZIP integrity checked when packaged.
- CI workflow configured to run these checks and attempt Android build/lint, but CI has not run.

## Release status: NO-GO
- Visual AI detection model, model weights, NPU/GPU inference, C2PA verification,
  original prompt lookup, reconstructed vision prompt, video AI detection all missing.
- Android Gradle compile, installation, device QA and release security QA NOT performed.
- Metadata is editable and cannot authenticate provenance. Description is not an exact prompt.
- Some PNG metadata after large image data may not be found due to parsing bounds.

## Reproduce local JVM tests
```
kotlinc app/src/main/java/com/aiorigin/detector/PngMetadataReader.kt qa/PngReaderQa.kt -include-runtime -d /tmp/pngqa.jar
java -jar /tmp/pngqa.jar
kotlinc app/src/main/java/com/aiorigin/detector/MetadataAssessment.kt qa/MetadataAssessmentQa.kt -include-runtime -d /tmp/metadataqa.jar
java -jar /tmp/metadataqa.jar
```

## v0.9: file fingerprint
- SHA-256 digest computed locally using 64 KiB streaming buffers, capped at 1 GiB.
- Digest supports future exact-file matches; it is NOT perceptual matching and does not indicate AI origin.
- Four additional JVM tests cover known vectors, size limit and multi-buffer streaming.
- Full Android build still not run in this environment; release remains NO-GO.

## v0.10: AUTOMATIC1111 generation parameters
- Parses editable A1111 infotext into positive prompt, negative prompt and generation settings.
- Exposes steps, sampler, CFG scale, model/model hash as unverified evidence when available.
- Does not confuse negative prompts with positive prompts.
- Ten isolated JVM tests added; 30/30 total isolated JVM tests passed locally.
- No AI vision classifier, original prompt verification or on-device APK test yet; release remains NO-GO.


## v0.11 — negative prompt as first-class output
- A1111 infotext positive and negative prompts are displayed separately in the Android UI.
- Each prompt has an independent clipboard action; long negative prompts are no longer truncated in the results UI.
- Four JVM regression cases added for multiline, negative-only, non-marker text, and case-insensitive markers.
- Both prompts originate from editable metadata and are **not authenticated**.

## v0.12 — JPEG metadata
- Added bounded offline JPEG COM and standard XMP APP1 prompt and negative-prompt extraction.
- Integrated JPEG metadata into the scan pipeline, including separate negative_prompt display.
- 11 JPEG parser tests passed locally. No AI vision classifier or verified Android APK yet.

## v0.13 — WebP support (2026-10-12)
- Added bounded RIFF/WebP XMP extraction with length and padding validation.
- Scanner routes WebP files through the new reader; embedded XMP is **unverified**.
- Ten isolated Kotlin/JVM WebP parser tests passed locally.
- No Android APK built or physical-device tests completed. **NO-GO**.

### v0.14 update
Negative-only metadata is now isolated from positive prompts. Explicit positive and negative fields are prioritized, including case-insensitive keys; AUTOMATIC1111-style combined fields are separated. 10 local JVM checks passed. Visual AI detection and an installable validated APK remain outstanding.

## v0.15 — reliability and user-controlled export
- Explicit prompt fields and A1111 generation settings are treated separately from generic descriptions/comments. Raw JSON graphs are no longer displayed as verbatim prompts.
- Positive and negative prompts remain separately visible and copyable.
- Added a **Share text report** action using Android's share sheet. This action is initiated by the user; no automatic uploads occur.
- Standalone Kotlin QA executed: 15/15 prompt-field tests and 5/5 report-format tests passed. Full Android build and device testing are **not** verified.
- Release decision: **NO-GO**. No trained visual AI-origin detector, no reliable prompt reconstruction, no verified APK.

### v0.16 updates
- Deterministic positive-before-negative prompt field selection even when metadata fields arrive out of order.
- Cancellation-safe scan exception handling in the UI.
- `bash qa/run-jvm-tests.sh` runs the isolated Kotlin JVM suites and is used by CI. Local full-suite attempt timed out after JPEG; see `qa/RELEASE_QA.md`.
- **Not an AI visual detector.** No validated APK or trained inference model is bundled.

### v0.17 changes
- Scan-generation identity guards prevent canceled scan jobs from overwriting newer results, clearing the spinner, or dismissing newer notices.
- Android GitHub Actions explicitly installs SDK 35 and build-tools 35.0.0 before assembling debug APK.
- These changes are not a trained AI-content classifier. The debug APK is not locally compiled or tested on hardware.

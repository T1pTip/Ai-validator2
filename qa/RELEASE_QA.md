# AI Origin Detector v0.6 — Release QA assessment

Date: 2026-10-12
Decision: **NO-GO** (experimental source, not production APK)

## Executed
- Kotlin/JVM compilation of isolated PNG parser: PASS
- PNG fixture suite: 8/8 PASS (tEXt, zTXt, iTXt Unicode, compressed iTXt, CRC tampering, truncated signature, zip-bomb limit, excessive chunk length)
- Archive integrity: validated on packaging

## Implemented, not yet device-tested
- Android Photo Picker and share import
- PNG/EXIF/video metadata inspection
- Local video sampling of up to 12 scaled keyframes, with coarse scene-change count
- Device RAM/thermal profile, copy button and 10-second status banner
- No network permission, no upload, no paid APIs

## Release blockers
1. Full Android Gradle compile not performed: Android SDK/Gradle absent from environment.
2. No trained visual AI-image detector integrated; no NPU/GPU benchmark.
3. No visual prompt reconstruction, C2PA verification or internet source matching.
4. No on-device tests on S23 Ultra, Android 16, or 12GB memory variant.
5. No validated accuracy, sensitivity, specificity, or robustness tests against curated AI/real media.
6. No security fuzzing or end-to-end Android UI/instrumentation testing.
7. No signed APK/AAB, Play policy review, accessibility audit or privacy threat-model signoff.

## Known limitations
- Frame sampling reports visual changes only and is **not** evidence of AI generation.
- File metadata is untrusted; cannot prove prompt authorship or image origin.
- The 8MB cumulative PNG chunk parsing budget can skip metadata after large IDAT chunks.
- A file lacking AI metadata must remain 'origin unknown'.
- Network APIs are not integrated yet, so free API quota claims do not apply to this build.

## Minimum release criteria
- Full release build and install on device; 0 blocker/critical bugs.
- Offline and malformed file tests; thermal and memory soak testing.
- Calibrated AI classification validated against real and synthetic content across generators.
- Provenance/source display distinguishes verified prompt from speculative reconstruction.
- Free API providers rate limited, privacy reviewed and fallback tested before enabling.

## v0.7 incremental QA (2026-10-12)
- Re-ran isolated Kotlin/JVM PNG fixtures: 8/8 PASS.
- Added GitHub Actions CI configuration for Android debug assembly and lint; workflow has **not** been executed here.
- Fixed overlapping file selection scan races and overlapping 10-second status timers via coroutine cancellation; **not UI-tested**.
- Added cautious ComfyUI sampler-linked positive text extraction; **not separately tested**.
- Decision remains NO-GO.

## v0.8
- PASS: 8 PNG parser JVM tests.
- PASS: 8 metadata assessor JVM tests.
- NOT RUN: Android Gradle build, Android instrumentation, device thermal/performance, security review.
- BLOCKER: no visual AI inference or prompt reconstruction.
- RELEASE: NO-GO.

## v0.9 test evidence (2026-10-12)
- PASS: 8 PNG metadata Kotlin/JVM tests
- PASS: 8 metadata assessment Kotlin/JVM tests
- PASS: 4 SHA-256 fingerprint Kotlin/JVM tests
- NOT RUN: Android Gradle compile, Android lint, instrumented tests, device profiling, AI classification, prompt reconstruction
- Release decision: NO-GO

## v0.10 QA (2026-10-12)
- PASS: 10/10 A1111 generation parameter parser JVM tests.
- PASS: 8/8 PNG, 8/8 metadata assessment, 4/4 SHA-256 tests (re-run).
- Total: 30/30 isolated Kotlin/JVM tests passed.
- Android Gradle build and CI remain unexecuted in this environment.
- Release decision: NO-GO.

## v0.12
11 JPEG JVM tests passed locally. Full Android build and device validation still pending.

## v0.13
- PASS: 10/10 isolated WebP metadata reader Kotlin/JVM tests (including truncated/oversized/odd-length chunks).
- NOT RUN: Android build, device tests, AI model validation. Release NO-GO.

## v0.14 — prompt field isolation
- Added pure Kotlin `PromptFieldsResolver` to prevent a negative-only prompt from being displayed as a positive prompt.
- Ran `PromptFieldsQa.kt` locally: 10/10 checks passed.
- Prior suite rerun started; time budget prevented completing all checks. No Android APK build or device validation.
- Release decision: NO-GO. No trained visual classifier or validated APK.

## v0.15
- PASS: `PromptFieldsQa` 15/15 (executed with kotlinc + java).
- PASS: `ScanReportQa` 5/5 (executed with kotlinc + java).
- Full regression suite attempted but exceeded execution timeout; do not infer pass.
- Android Gradle build, device tests, NPU inference: NOT RUN / NOT VERIFIED.
- Release: NO-GO.

## v0.16 — prioritized prompt metadata and reproducible JVM runner
- Fixed source-field priority: positive_prompt/parameters take precedence over negative_prompt regardless of metadata order; trims/case-folds field keys.
- Added three metadata regression cases. 11/11 metadata assessment checks passed using local kotlinc/java.
- Added qa/run-jvm-tests.sh and simplified GitHub Actions to call one reproducible script.
- Full regression runner was executed but exceeded the execution time limit during WebP compilation; suites through JPEG passed, later suites not verified in this run.
- Hardened MainActivity scan cancellation exception handling; Android compilation NOT VERIFIED.
- No trained image/video classifier, verified C2PA provenance, or Android device test. RELEASE: NO-GO.

## v0.17 — asynchronous scan race hardening and CI prerequisites
- Scan generation counter prevents a canceled, non-cooperative background scan from publishing stale results or resetting the newer scan's busy state.
- Ten-second status notification now checks generation identity before dismissing a newer notice.
- GitHub Actions installs Android SDK platform/build tools 35 before building.
- Full JVM suite: see qa/v017-test-results.txt for the actual run output. A passing JVM suite does not validate Android compilation or real-device behavior.
- Android APK and device tests remain NOT VERIFIED locally. Visual AI classifier and prompt reconstruction remain unimplemented. RELEASE: NO-GO.

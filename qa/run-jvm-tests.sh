#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=app/src/main/java/com/aiorigin/detector
TMPDIR_QA="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_QA"' EXIT
run() {
  local name="$1"; shift
  echo "===== $name ====="
  kotlinc "$@" -include-runtime -d "$TMPDIR_QA/$name.jar"
  java -jar "$TMPDIR_QA/$name.jar"
}
run png "$SRC/PngMetadataReader.kt" "$SRC/C2paSniffer.kt" qa/PngReaderQa.kt
run assessment "$SRC/MetadataAssessment.kt" "$SRC/GenerationParameters.kt" qa/MetadataAssessmentQa.kt
run fingerprint "$SRC/MediaFingerprint.kt" qa/MediaFingerprintQa.kt
run parameters "$SRC/GenerationParameters.kt" qa/GenerationParametersQa.kt
run jpeg "$SRC/JpegMetadataReader.kt" "$SRC/XmpFields.kt" "$SRC/C2paSniffer.kt" qa/JpegMetadataQa.kt
run webp "$SRC/WebpMetadataReader.kt" "$SRC/XmpFields.kt" "$SRC/C2paSniffer.kt" qa/WebpMetadataQa.kt
run prompt-fields "$SRC/GenerationParameters.kt" "$SRC/PromptFields.kt" qa/PromptFieldsQa.kt
run report "$SRC/ScanReport.kt" qa/ScanResultStub.kt qa/ScanReportQa.kt
echo 'ALL JVM SUITES PASSED'

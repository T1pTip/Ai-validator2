#!/usr/bin/env bash
set -euo pipefail
APK="${1:?APK path required}"
OUT="${2:?Evidence directory required}"
TOOLS="${ANDROID_HOME:?ANDROID_HOME required}/build-tools/35.0.0"
test -s "$APK"
mkdir -p "$OUT"
"$TOOLS/apksigner" verify --verbose --print-certs "$APK" > "$OUT/signature.txt"
"$TOOLS/zipalign" -c -p 4 "$APK"
"$TOOLS/aapt" dump badging "$APK" > "$OUT/apk-badging.txt"
# Store a basename so checksum verification also works after artifact extraction.
(cd "$(dirname "$APK")" && sha256sum "$(basename "$APK")") > "$OUT/SHA256SUMS.txt"
printf 'commit=%s\nrun=%s\n' "${GITHUB_SHA:-unknown}" "${GITHUB_RUN_ID:-local}" > "$OUT/build.txt"

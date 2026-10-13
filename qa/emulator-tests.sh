#!/usr/bin/env bash
# Runs the instrumentation suite on the booted CI emulator and keeps evidence for upload.
set -uo pipefail
cd "${GITHUB_WORKSPACE:-$(dirname "$0")/..}"
mkdir -p app/build
adb logcat -c || true
gradle --no-daemon :app:connectedDebugAndroidTest
status=$?
adb logcat -d > app/build/emulator-logcat.txt || true
grep 'LocalDetectorTest' app/build/emulator-logcat.txt > app/build/detector-timings.txt || true
exit $status

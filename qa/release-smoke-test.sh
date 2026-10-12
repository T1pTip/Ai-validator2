#!/usr/bin/env bash
set -Eeuo pipefail

APK="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is missing}/release-artifacts/ai-origin-release.apk"
TOOLS="${ANDROID_HOME:?ANDROID_HOME is missing}/build-tools/35.0.0"
PACKAGE="com.aiorigin.detector"

echo "== Verify APK exists and is readable =="
test -s "$APK"
ls -lh "$APK"
"$TOOLS/apksigner" verify --verbose "$APK"
"$TOOLS/zipalign" -c -p 4 "$APK"

echo "== Inspect APK manifest =="
"$TOOLS/aapt" dump badging "$APK" | grep -E '^(package:|sdkVersion:|targetSdkVersion:|launchable-activity:)'

echo "== Wait for emulator =="
adb wait-for-device
for attempt in $(seq 1 60); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then
    break
  fi
  sleep 2
done
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1"

echo "== Install APK =="
adb install -r "$APK"
adb shell pm path "$PACKAGE" | grep -q '^package:'

echo "== Launch app =="
adb logcat -c
adb shell am start -W -n "$PACKAGE/.MainActivity"
sleep 8

echo "== Check app process is alive =="
PID="$(adb shell pidof "$PACKAGE" | tr -d '\r' | tr -d '[:space:]')"
if [ -z "$PID" ]; then
  echo "::error::App process not running after launch"
  adb logcat -d -t 500 | tail -150
  exit 1
fi
echo "App process running with PID $PID"
adb shell dumpsys activity activities | grep -E "$PACKAGE|topResumedActivity|mResumedActivity" || true

echo "== Smoke test passed =="

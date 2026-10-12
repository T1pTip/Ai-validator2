#!/usr/bin/env bash
set -Eeuo pipefail

APK="${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is missing}/release-artifacts/ai-origin-release.apk"
TOOLS="${ANDROID_HOME:?ANDROID_HOME is missing}/build-tools/35.0.0"
PACKAGE="com.aiorigin.detector"
API="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
REPORT="${GITHUB_WORKSPACE}/release-artifacts/emulator-api-${API}.txt"
exec > >(tee "$REPORT") 2>&1
trap 'status=$?; if [ "$status" -ne 0 ]; then adb logcat -d -t 300 > "${REPORT%.txt}-failure-logcat.txt" || true; fi; exit "$status"' EXIT
echo "Emulator API: $API; ABI: $(adb shell getprop ro.product.cpu.abilist)"

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

echo "== Repeat install preserves signature/version update compatibility =="
adb install -r "$APK"

echo "== Launch app =="
adb logcat -c
START="$(adb shell am start -W -n "$PACKAGE/.MainActivity")"
printf '%s\n' "$START"
echo "$START" | grep -q 'Status: ok'
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

echo "== Debug and release coexist with distinct package identities =="
DEBUG_APK="$GITHUB_WORKSPACE/app/build/outputs/apk/debug/app-debug.apk"
adb install -r "$DEBUG_APK"
adb shell pm path "$PACKAGE.debug" | grep -q '^package:'
adb shell pm path "$PACKAGE" | grep -q '^package:'
DEBUG_START="$(adb shell am start -W -n "$PACKAGE.debug/$PACKAGE.MainActivity")"
printf '%s\n' "$DEBUG_START"
echo "$DEBUG_START" | grep -q 'Status: ok'
sleep 3
test -n "$(adb shell pidof "$PACKAGE.debug" | tr -d '\r' | tr -d '[:space:]')"

echo "== Smoke test passed =="

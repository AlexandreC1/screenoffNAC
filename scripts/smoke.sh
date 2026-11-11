#!/usr/bin/env bash
# Emulator smoke test: install, record with rotation, stop, check the clips,
# then arm motion mode and stop. Fails on any crash or error notification.
set -uo pipefail

PKG=dev.screenoff.recorder
OUT=smoke-out
mkdir -p "$OUT"
APK=$(ls apk/*.apk | head -1)
FAILED=0

say() { echo; echo "=== $*"; }
fail() { echo "FAIL: $*"; FAILED=1; }
svc() { adb shell dumpsys activity services "$PKG/.service.RecorderService" 2>/dev/null; }
trigger() { adb shell am start -W -n "$PKG/.triggers.TriggerActivity" -a "$PKG.action.$1" >/dev/null 2>&1; }
finish() {
  adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
  adb shell dumpsys notification --noredact > "$OUT/notifications.txt" 2>/dev/null
  if [ "$FAILED" -ne 0 ]; then
    say "logcat (app, camera, crashes)"
    grep -E "AndroidRuntime|screenoff|CameraX|Camera2|Recorder|VideoCapture|ForegroundService|FATAL" "$OUT/logcat.txt" | tail -150
    echo; echo "SMOKE TEST FAILED"; exit 1
  fi
  echo; echo "SMOKE TEST PASSED"; exit 0
}

say "install"
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb install -r "$APK" || { fail "install"; finish; }
adb shell pm grant $PKG android.permission.CAMERA
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# 30 second segments so rotation happens during the test.
printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <int name=\"segmentLengthSec\" value=\"30\" />\n</map>\n" \
  | adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/config.xml'"
adb logcat -c

say "open the app"
adb shell am start -W -n "$PKG/.ui.MainActivity" | tail -3
sleep 6
adb shell pidof $PKG >/dev/null || fail "app is not running after launch"
adb exec-out screencap -p > "$OUT/home.png" 2>/dev/null

say "start continuous recording through the trigger activity"
adb shell input keyevent KEYCODE_HOME
trigger START_CONTINUOUS
sleep 8
svc | grep -q "isForeground=true" || fail "recorder service is not in the foreground after start"

say "screen off for 70 seconds"
adb shell input keyevent KEYCODE_SLEEP
sleep 70
svc | grep -q "isForeground=true" || fail "recorder service died with the screen off"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard >/dev/null 2>&1

say "stop"
trigger STOP
sleep 12
svc | grep -q "ServiceRecord" && fail "recorder service still running after stop"

say "clips"
INDEX=$(adb shell "run-as $PKG cat files/segments.json" 2>/dev/null)
echo "$INDEX" > "$OUT/segments.json"
echo "$INDEX"
adb shell "run-as $PKG ls -la /sdcard/Android/data/$PKG/files/Movies/continuous/" | tee "$OUT/files.txt"
COUNT=$(echo "$INDEX" | grep -o '"id"' | wc -l)
[ "$COUNT" -ge 3 ] || fail "expected at least 3 clips (2 rotations and the stop), found $COUNT"
echo "$INDEX" | grep -q '"sizeBytes":0[,}]' && fail "a clip has size 0"
echo "$INDEX" | grep -q '"durationMs":0[,}]' && fail "a clip has duration 0"
LEFT=$(adb shell "run-as $PKG ls /sdcard/Android/data/$PKG/files/Movies/staging/" 2>/dev/null | wc -l)
[ "$LEFT" -eq 0 ] || fail "staging folder is not empty after stop"

say "motion mode: arm, wait, stop"
trigger START_MOTION
sleep 15
svc | grep -q "isForeground=true" || fail "recorder service is not in the foreground in motion mode"
trigger STOP
sleep 12
svc | grep -q "ServiceRecord" && fail "recorder service still running after motion stop"
adb shell "run-as $PKG cat files/segments.json" > "$OUT/segments_after_motion.json" 2>/dev/null
echo "clips after motion mode: $(grep -o '"id"' "$OUT/segments_after_motion.json" | wc -l)"

say "crashes and error notifications"
adb logcat -d > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "the app crashed"
adb shell dumpsys notification --noredact | grep -q "Recording stopped" && {
  adb shell dumpsys notification --noredact | grep -B2 -A12 "Recording stopped" | grep -iE "text=|bigText" | head -5
  fail "the app posted an error notification"
}

finish

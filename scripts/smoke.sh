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
trigger() { adb shell am start -W -n "$PKG/.triggers.TriggerActivity" -a "$PKG.action.$1" 2>&1 | grep -E "Status|Error|Warning" ; }
applog() { adb logcat -d -s ScreenOff:V ActivityTaskManager:I | grep -E "ScreenOff|screenoff" | tail -${1:-15} | cut -c1-400; }
# Taps the on-screen element whose text is exactly $1.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null
  XY=$(python3 - "$1" "$OUT/ui.xml" <<'PY'
import re, sys
want, path = sys.argv[1], sys.argv[2]
data = open(path, encoding="utf-8", errors="ignore").read()
for node in re.findall(r"<node [^>]*>", data):
    t = re.search(r'text="([^"]*)"', node)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if t and b and t.group(1) == want:
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$XY" ] || { echo "texts on screen:"; grep -o 'text="[^"]\+"' "$OUT/ui.xml" | head -30; return 1; }
  adb shell input tap $XY
}
wait_fg() { for i in $(seq 1 15); do svc | grep -q "isForeground=true" && { echo "foreground after ~$((i*2))s"; return 0; }; sleep 2; done; return 1; }
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
adb shell pm grant $PKG android.permission.RECORD_AUDIO
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
sleep 3
trigger START_CONTINUOUS
wait_fg || fail "recorder service is not in the foreground after start"
applog
adb exec-out screencap -p > "$OUT/after_start.png" 2>/dev/null

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
MOVIES=/data/media/0/Android/data/$PKG/files/Movies
adb shell ls -la $MOVIES/continuous/ | tee "$OUT/files.txt"
COUNT=$(echo "$INDEX" | grep -o '"id"' | wc -l)
[ "$COUNT" -ge 3 ] || fail "expected at least 3 clips (2 rotations and the stop), found $COUNT"
echo "$INDEX" | grep -q '"sizeBytes":0[,}]' && fail "a clip has size 0"
echo "$INDEX" | grep -q '"durationMs":0[,}]' && fail "a clip has duration 0"
LEFT=$(adb shell ls $MOVIES/staging/ 2>/dev/null | wc -l)
[ "$LEFT" -eq 0 ] || fail "staging folder is not empty after stop"

say "sound"
AUDIO_TRACKS=$(adb logcat -d | grep -c "Muxer.addTrack() for audio")
echo "clips with an audio track: $AUDIO_TRACKS"
[ "$AUDIO_TRACKS" -ge 3 ] || { adb logcat -d | grep -iE "audio" | grep -iE "recorder|source|error" | tail -20 | cut -c1-300; fail "clips were recorded without sound"; }
adb shell dumpsys activity services "$PKG/.service.RecorderService" | grep -i "types" | head -3

say "motion mode, started with the real START button"
adb shell svc power stayon true
adb shell am force-stop $PKG
printf "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <int name=\"segmentLengthSec\" value=\"30\" />\n    <string name=\"mode\">MOTION</string>\n    <int name=\"configVersion\" value=\"2\" />\n    <boolean name=\"audioEnabled\" value=\"true\" />\n</map>\n" \
  | adb shell "run-as $PKG sh -c 'cat > shared_prefs/config.xml'"
adb shell am start -W -n "$PKG/.ui.MainActivity" >/dev/null
sleep 6
adb exec-out screencap -p > "$OUT/home_motion.png" 2>/dev/null
tap_text START || fail "START button not found on the Home screen"
wait_fg || fail "recorder service is not in the foreground in motion mode"
applog 6
adb logcat -d -s ScreenOff:V | grep -q "start requested, mode=MOTION" || fail "the START button did not start motion mode"
sleep 40
svc | grep -q "isForeground=true" || fail "recorder service died in motion mode"
LAST_STATS=$(adb logcat -d -s ScreenOff:V | grep "motion stats" | tail -1)
echo "$LAST_STATS"
echo "$LAST_STATS" | grep -qE "frames=[1-9]" || fail "motion mode received no camera images"
adb logcat -d -s ScreenOff:V | grep -q "motion detected" || fail "motion mode never detected motion"
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard >/dev/null 2>&1; sleep 2
tap_text STOP || fail "STOP button not found on the Home screen"
sleep 15
svc | grep -q "ServiceRecord" && fail "recorder service still running after pressing STOP"
adb shell "run-as $PKG cat files/segments.json" > "$OUT/segments_after_motion.json" 2>/dev/null
cat "$OUT/segments_after_motion.json"; echo
echo "motion clips: $(grep -o '"mode":"MOTION"' "$OUT/segments_after_motion.json" | wc -l)"
grep -q '"sizeBytes":0[,}]' "$OUT/segments_after_motion.json" && fail "a motion clip has size 0"
adb shell ls -la $MOVIES/motion/ 2>/dev/null
[ "$(adb shell ls $MOVIES/staging/ 2>/dev/null | wc -l)" -eq 0 ] || fail "staging folder is not empty after motion stop"

say "app log"
applog 60

say "crashes and error notifications"
adb logcat -d > "$OUT/logcat.txt"
grep -q "FATAL EXCEPTION" "$OUT/logcat.txt" && grep -A3 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "$PKG" && fail "the app crashed"
adb shell dumpsys notification --noredact | grep -q "Recording stopped" && {
  adb shell dumpsys notification --noredact | grep -B2 -A12 "Recording stopped" | grep -iE "text=|bigText" | head -5
  fail "the app posted an error notification"
}

finish

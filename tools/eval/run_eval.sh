#!/bin/sh
# Runs the evaluation on the connected device/emulator and pulls the raw reports into tools/eval/results/.
#   tools/eval/run_eval.sh [modes] [set]  # modes: oracle,image (default both); set: dev (default) or heldout
#   tools/eval/run_eval.sh oracle       # fast: no images needed
# Needs: a booted emulator/phone, the Python venv (.venv), and Swift only if images have to be (re)rendered.
set -e
cd "$(dirname "$0")/../.."
MODES=${1:-oracle,image}
SET=${2:-dev}
PFX=$([ "$SET" = heldout ] && echo heldout_ || echo "")
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
PKG=com.munin.app

.venv/bin/python tools/eval/make_corpus.py dev >/dev/null
.venv/bin/python tools/eval/make_corpus.py heldout >/dev/null
case "$MODES" in *image*)
  [ -d tools/eval/images/$SET ] && [ "$(ls tools/eval/images/$SET | wc -l)" -ge 300 ] || swift tools/eval/render_images.swift tools/eval/data/${PFX}manifest.json tools/eval/images/$SET
  ;;
esac

# install (not connectedAndroidTest: that uninstalls the app afterwards, which would delete the pushed images)
./gradlew -q :app:installDebug :app:installDebugAndroidTest
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
case "$MODES" in *image*)
  # into the app's internal storage (the app cannot reliably list files adb creates in its external storage)
  $ADB shell rm -rf /data/local/tmp/munin_images
  $ADB push -q tools/eval/images/$SET /data/local/tmp/munin_images
  $ADB shell run-as $PKG rm -rf files/eval/images
  $ADB shell run-as $PKG mkdir -p files/eval
  $ADB shell run-as $PKG cp -r /data/local/tmp/munin_images files/eval/images
  ;;
esac

$ADB shell am instrument -w -e class com.munin.app.eval.EvalHarnessTest -e eval_modes "$MODES" -e eval_set "$SET" $PKG.test/androidx.test.runner.AndroidJUnitRunner | tail -8
mkdir -p tools/eval/results
for m in $(echo "$MODES" | tr ',' ' '); do
  $ADB exec-out run-as $PKG cat files/eval/report-$SET-$m.json > tools/eval/results/report-$SET-$m.json
  echo "pulled tools/eval/results/report-$SET-$m.json ($(wc -c < tools/eval/results/report-$SET-$m.json) bytes)"
done
.venv/bin/python tools/eval/report.py

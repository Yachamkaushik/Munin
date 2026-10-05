#!/bin/sh
# Runs the evaluation on the connected device/emulator and pulls the raw reports into tools/eval/results/.
#   tools/eval/run_eval.sh              # oracle (true text) and image (real OCR) modes
#   tools/eval/run_eval.sh oracle       # fast: no images needed
# Needs: a booted emulator/phone, the Python venv (.venv), and Swift only if images have to be (re)rendered.
set -e
cd "$(dirname "$0")/../.."
MODES=${1:-oracle,image}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
PKG=com.munin.app

.venv/bin/python tools/eval/make_corpus.py >/dev/null
case "$MODES" in *image*)
  [ -d tools/eval/images ] && [ "$(ls tools/eval/images | wc -l)" -ge 300 ] || swift tools/eval/render_images.swift tools/eval/data/manifest.json tools/eval/images
  ;;
esac

# install (not connectedAndroidTest: that uninstalls the app afterwards, which would delete the pushed images)
./gradlew -q :app:installDebug :app:installDebugAndroidTest
$ADB shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>/dev/null || true
case "$MODES" in *image*)
  DEST=/sdcard/Android/data/$PKG/files/eval/images
  $ADB shell rm -rf /sdcard/Android/data/$PKG/files/eval
  $ADB shell mkdir -p $DEST
  $ADB push -q tools/eval/images/. $DEST/
  ;;
esac

$ADB shell am instrument -w -e class com.munin.app.eval.EvalHarnessTest -e eval_modes "$MODES" $PKG.test/androidx.test.runner.AndroidJUnitRunner | tail -8
mkdir -p tools/eval/results
for m in $(echo "$MODES" | tr ',' ' '); do
  $ADB exec-out run-as $PKG cat files/eval/report-$m.json > tools/eval/results/report-$m.json
  echo "pulled tools/eval/results/report-$m.json ($(wc -c < tools/eval/results/report-$m.json) bytes)"
done
.venv/bin/python tools/eval/report.py

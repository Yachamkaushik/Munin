#!/bin/sh
# Fails if the built APK requests any network-related permission. Run after assembling.
set -e
cd "$(dirname "$0")/.."
./gradlew -q :app:assembleDebug :app:assembleRelease >/dev/null
AAPT=$(ls ~/Library/Android/sdk/build-tools/*/aapt2 | tail -1)
for apk in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk; do
  [ -f "$apk" ] || continue
  perms=$("$AAPT" dump permissions "$apk" | grep -E "INTERNET|NETWORK|WIFI" || true)
  if [ -n "$perms" ]; then echo "FAIL $apk requests: $perms"; exit 1; fi
  echo "OK   $apk: no network permissions"
done

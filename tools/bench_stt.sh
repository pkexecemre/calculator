#!/usr/bin/env bash
# On-device live-caption benchmark. Builds and installs the debug APK, feeds
# the bundled speech samples through the real LiveTranscriber, and reports
# inference latency (RTF = inference seconds / audio seconds).
#
# Usage:
#   tools/bench_stt.sh                 # uses the only connected device
#   tools/bench_stt.sh <adb-serial>    # specific phone/emulator
#
# The phone must have USB debugging enabled and the debug APK installable.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SERIAL="${1:-}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then ADB+=( -s "$SERIAL" ); fi

if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "No adb device. Connect the Poco X6 Pro (USB debugging) or start an emulator." >&2
  exit 1
fi

echo "Building debug APK with exported debug tools..."
# The harness activities are normally unexported; this local-only build opens
# them so adb can launch the benchmark.
./gradlew :app:assembleDebug -PdebugToolsExported=true --console=plain >/dev/null

echo "Installing..."
"${ADB[@]}" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null

run_case() {
  local name="$1" audio="$2" hint="$3"
  echo
  echo "== $name (audio=$audio hint=$hint) =="
  "${ADB[@]}" logcat -c || true
  "${ADB[@]}" shell am force-stop com.simplecalculator.app
  "${ADB[@]}" shell am start -n com.simplecalculator.app/.DebugSttActivity \
    --es model base --es audio "$audio" --es hint "$hint" >/dev/null

  # DebugSttActivity waits 20 s after feeding, then logs "done in ...".
  for _ in $(seq 1 45); do
    if "${ADB[@]}" logcat -d -s LocalSTT_TEST:V | grep -q 'done in'; then break; fi
    sleep 2
  done

  "${ADB[@]}" logcat -d -v raw -s LocalSTT:V | grep -E 'infer |window |flush window' || true
  echo "-- latency --"
  "${ADB[@]}" logcat -d -v raw -s LocalSTT:V | grep 'infer ' | awk '
    {
      ms=0; sec=0
      for (i=1; i<=NF; i++) {
        if ($i ~ /^[0-9]+ms$/) ms=$i+0
        if ($i ~ /^audio=/) { gsub(/audio=|s/,"",$i); sec=$i+0 }
      }
      if (sec > 0) { printf "  inference=%dms audio=%.2fs rtf=%.2f\n", ms, sec, (ms/1000)/sec }
    }'
  "${ADB[@]}" shell am force-stop com.simplecalculator.app
}

run_case "English speech" "jfk.wav" "en"
run_case "Japanese -> English" "ja.wav" "ja"

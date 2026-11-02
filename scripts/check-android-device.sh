#!/usr/bin/env bash
# Optional: runs connectedDebugAndroidTest when an emulator/device is attached.
# Exit codes: 0 = ran and passed, 1 = tests failed or ambiguous device choice, 2 = SKIPPED (no device; NOT a pass).
# Honors ANDROID_SERIAL=<id>. With several devices attached and no ANDROID_SERIAL it fails and lists them.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEVICES=()
if command -v adb >/dev/null; then
  while IFS= read -r line; do DEVICES+=("$line"); done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
fi
if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "ANDROID_DEVICE_CHECK_SKIPPED: no emulator or device attached (adb devices is empty). This is NOT a pass." >&2
  exit 2
fi
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  printf '%s\n' "${DEVICES[@]}" | grep -qx "$ANDROID_SERIAL" || {
    echo "ANDROID_SERIAL=$ANDROID_SERIAL is not attached. Attached: ${DEVICES[*]}" >&2; exit 1; }
elif [[ ${#DEVICES[@]} -gt 1 ]]; then
  echo "Multiple devices attached: ${DEVICES[*]}. Set ANDROID_SERIAL=<id> to choose one." >&2
  exit 1
fi
# Emulator prep (target device only): system ANR dialogs and animations break UiAutomator tests on a loaded host.
TARGET="${ANDROID_SERIAL:-${DEVICES[0]}}"
if [[ "$TARGET" == emulator-* ]]; then
  for key in window_animation_scale transition_animation_scale animator_duration_scale; do adb -s "$TARGET" shell settings put global "$key" 0 || true; done
  adb -s "$TARGET" shell settings put global hide_error_dialogs 1 || true
  adb -s "$TARGET" shell pm disable-user --user 0 com.google.android.gms >/dev/null 2>&1 || true
fi
"$ROOT/gradlew" -p "$ROOT" :composeApp:connectedDebugAndroidTest --console=plain
python3 "$ROOT/tools/localization/check_test_results.py" --instrumentation
echo "ANDROID_DEVICE_CHECK_PASSED"

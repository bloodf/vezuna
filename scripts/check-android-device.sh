#!/usr/bin/env bash
# Optional: runs connectedDebugAndroidTest when an emulator/device is attached; skips otherwise.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! command -v adb >/dev/null || [[ -z "$(adb devices | awk 'NR>1 && $2=="device"')" ]]; then
  echo "ANDROID_DEVICE_CHECK_SKIPPED: no emulator or device attached (adb devices is empty)"
  exit 0
fi
"$ROOT/gradlew" -p "$ROOT" :composeApp:connectedDebugAndroidTest --console=plain
python3 "$ROOT/tools/localization/check_test_results.py" --instrumentation
echo "ANDROID_DEVICE_CHECK_PASSED"

#!/usr/bin/env bash
# Redesign screenshot harness: opens debug builds on the design fixture (synthetic DATA_AND_COPY household,
# in-memory, the vault is never touched) and captures one PNG per route and theme. No taps: each route is
# reached through the debug deep link, which also works where simulator tapping is broken.
#
#   scripts/redesign-shots.sh android <out-dir> [Route ...]   needs ANDROID_SERIAL (never a personal device)
#   scripts/redesign-shots.sh ios <out-dir> [Route ...]       needs SIMULATOR_ID; builds with build-ios.sh first
#
# Routes are AppScreen names (Dashboard, Labs, Medications, Trends, More, ResultDetail:hgb-2026-10, ...).
# Default: the five root destinations. Files: <out-dir>/<platform>-<route>-<light|dark>.png
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLATFORM="${1:?android or ios}"
OUT="${2:?output directory}"
shift 2
ROUTES=("$@")
[[ ${#ROUTES[@]} -gt 0 ]] || ROUTES=(Dashboard Labs Medications Trends More)
mkdir -p "$OUT"
SETTLE="${SETTLE_SECONDS:-4}"

android_shots() {
  : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a test emulator}"
  local apk="$ROOT/composeApp/build/outputs/apk/debug/composeApp-debug.apk"
  [[ -f "$apk" ]] || "$ROOT/gradlew" -p "$ROOT" :composeApp:assembleDebug --console=plain
  adb install -r "$apk" >/dev/null
  for theme in light dark; do
    adb shell cmd uimode night "$([[ $theme == dark ]] && echo yes || echo no)"
    for route in "${ROUTES[@]}"; do
      adb shell am force-stop app.vezuna
      adb shell am start -W -n app.vezuna/.AndroidMainActivity --ez fixture true --es route "$route" >/dev/null
      sleep "$SETTLE"
      adb exec-out screencap -p >"$OUT/android-${route//:/_}-$theme.png"
      echo "SHOT android $route $theme"
    done
  done
  adb shell cmd uimode night no
}

ios_shots() {
  : "${SIMULATOR_ID:?Set SIMULATOR_ID}"
  local app="$ROOT/iosApp/.ios-build/derived-data/Debug/Build/Products/Debug-iphonesimulator/Vezuna.app"
  [[ -d "$app" ]] || CONFIGURATION=Debug bash "$ROOT/scripts/build-ios.sh"
  xcrun simctl bootstatus "$SIMULATOR_ID" -b >/dev/null
  xcrun simctl install "$SIMULATOR_ID" "$app"
  for theme in light dark; do
    xcrun simctl ui "$SIMULATOR_ID" appearance "$theme"
    for route in "${ROUTES[@]}"; do
      xcrun simctl terminate "$SIMULATOR_ID" app.vezuna >/dev/null 2>&1 || true
      xcrun simctl launch "$SIMULATOR_ID" app.vezuna -VezunaFixture -VezunaRoute "$route" >/dev/null
      sleep "$SETTLE"
      xcrun simctl io "$SIMULATOR_ID" screenshot "$OUT/ios-${route//:/_}-$theme.png" >/dev/null
      echo "SHOT ios $route $theme"
    done
  done
  xcrun simctl ui "$SIMULATOR_ID" appearance light
}

case "$PLATFORM" in
  android) android_shots ;;
  ios) ios_shots ;;
  *) echo "Unknown platform: $PLATFORM" >&2; exit 2 ;;
esac

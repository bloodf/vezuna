#!/usr/bin/env bash
# Local iOS gate: Kotlin/Native shared tests on the simulator, simulator build, full XCTest suite. Prints IOS_CHECK_PASSED or IOS_CHECK_FAILED.
# Picks an available iPhone simulator (override with SIMULATOR_ID=<udid>; it pins the Kotlin/Native tests and XCTest alike). iOS Simulator only.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if "$ROOT/gradlew" -p "$ROOT" :shared:iosSimulatorArm64Test --console=plain && bash "$ROOT/scripts/build-ios.sh" && bash "$ROOT/scripts/test-ios.sh"; then
  echo "IOS_CHECK_PASSED"
else
  echo "IOS_CHECK_FAILED" >&2
  exit 1
fi

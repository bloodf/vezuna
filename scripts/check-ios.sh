#!/usr/bin/env bash
# Local iOS gate: simulator build + full XCTest suite. Prints IOS_CHECK_PASSED or IOS_CHECK_FAILED.
# Picks the first available iPhone simulator (override with SIMULATOR_ID=<udid>). iOS Simulator only.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if bash "$ROOT/scripts/build-ios.sh" && bash "$ROOT/scripts/test-ios.sh"; then
  echo "IOS_CHECK_PASSED"
else
  echo "IOS_CHECK_FAILED" >&2
  exit 1
fi

#!/usr/bin/env bash
# Runs the iOS XCTest suite on an iOS simulator. Requires `bash scripts/build-ios.sh` first (builds the shared framework).
#   scripts/test-ios.sh                       first available iPhone simulator
#   SIMULATOR_ID=<udid> scripts/test-ios.sh   specific simulator
# Parallel testing is off: clones of the simulator made the host app fail to launch intermittently (#114).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIMULATOR_ID="${SIMULATOR_ID:-$(xcrun simctl list devices available -j | python3 -c '
import json, sys
for runtime, devices in sorted(json.load(sys.stdin)["devices"].items(), reverse=True):
    for d in devices:
        if d["name"].startswith("iPhone") and d["isAvailable"]:
            print(d["udid"]); sys.exit(0)
sys.exit("no available iPhone simulator")
')}"

xcodebuild test \
  -project "$ROOT/iosApp/Vezuna.xcodeproj" \
  -scheme Vezuna \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$SIMULATOR_ID" \
  -derivedDataPath "$ROOT/iosApp/.ios-build/derived-data/Test" \
  -parallel-testing-enabled NO \
  CODE_SIGNING_ALLOWED=NO \
  ARCHS=arm64

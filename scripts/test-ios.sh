#!/usr/bin/env bash
# Runs the iOS XCTest suite on an iOS simulator. Requires `bash scripts/build-ios.sh` first (builds the shared framework).
#   scripts/test-ios.sh                       highest-iOS (>= 18.5) available iPhone simulator
#   SIMULATOR_ID=<udid> scripts/test-ios.sh   specific simulator
# Parallel testing is off: clones of the simulator made the host app fail to launch intermittently (#114).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIMULATOR_ID="${SIMULATOR_ID:-$(xcrun simctl list devices available -j | python3 -c '
import json, re, sys
# Highest iOS runtime (numeric sort, must be >= the 18.5 deployment target) with an available iPhone.
found = []
for runtime, devices in json.load(sys.stdin)["devices"].items():
    m = re.search(r"\.iOS-(\d+)-(\d+)", runtime)
    if not m or (int(m[1]), int(m[2])) < (18, 5):
        continue
    for d in devices:
        if d["name"].startswith("iPhone") and d["isAvailable"]:
            found.append(((int(m[1]), int(m[2])), d["udid"]))
if not found:
    sys.exit("no available iPhone simulator on iOS >= 18.5")
print(max(found)[1])
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

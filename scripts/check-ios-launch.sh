#!/usr/bin/env bash
# Launches the app `scripts/build-ios.sh` built, the way a person does: no test host and no design fixture, which
# both skip the real vault. XCTest cannot catch a first run that fails to open it, so this does, on a throwaway
# simulator it creates and deletes (a fresh Keychain, and no one's data at risk).
# A first run writes its default profile to the encrypted vault; no vault files means the app never got its key.
#   APP=<path to .app> overrides the app; SIMULATOR_ID=<udid> picks the device type and runtime to copy.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="${APP:-$ROOT/iosApp/.ios-build/derived-data/Debug/Build/Products/Debug-iphonesimulator/Vezuna.app}"
BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw "$APP/Info.plist")"

read -r DEVICE_TYPE RUNTIME < <(xcrun simctl list devices available -j | SIMULATOR_ID="${SIMULATOR_ID:-}" python3 -c '
import json, os, re, sys
pinned = os.environ["SIMULATOR_ID"]
found = []
for runtime, devices in json.load(sys.stdin)["devices"].items():
    m = re.search(r"\.iOS-(\d+)-(\d+)", runtime)
    if not m or (int(m[1]), int(m[2])) < (18, 5):
        continue
    for d in devices:
        if (d["udid"] == pinned) if pinned else d["name"].startswith("iPhone"):
            found.append(((int(m[1]), int(m[2])), d["deviceTypeIdentifier"], runtime))
if not found:
    sys.exit("no available iPhone simulator on iOS >= 18.5")
_, device_type, runtime = max(found)
print(device_type, runtime)
')

DEVICE="$(xcrun simctl create "Vezuna launch check $$" "$DEVICE_TYPE" "$RUNTIME")"
trap 'xcrun simctl shutdown "$DEVICE" >/dev/null 2>&1 || true; xcrun simctl delete "$DEVICE" >/dev/null 2>&1 || true' EXIT
xcrun simctl bootstatus "$DEVICE" -b >/dev/null
xcrun simctl install "$DEVICE" "$APP"
xcrun simctl launch "$DEVICE" "$BUNDLE_ID" >/dev/null

VAULT="$(xcrun simctl get_app_container "$DEVICE" "$BUNDLE_ID" data)/Library/Application Support/Vezuna/Vault"
for _ in $(seq 1 60); do
  if [[ -n "$(find "$VAULT" -type f 2>/dev/null | head -1)" ]]; then
    echo "IOS_LAUNCH_PASSED"
    exit 0
  fi
  sleep 1
done
echo "IOS_LAUNCH_FAILED: the first run wrote nothing to the vault. Is the app signed? An unsigned app gets no Keychain (-34018)." >&2
exit 1

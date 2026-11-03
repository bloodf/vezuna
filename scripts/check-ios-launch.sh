#!/usr/bin/env bash
# Launches the app `scripts/build-ios.sh` built, the way a person does: no test host and no design fixture, which
# both skip the real vault. XCTest cannot catch a first run that fails to open it, so this does, on a throwaway
# simulator it creates and deletes (a fresh Keychain, and no one's data at risk).
# A first run writes its default profile to the encrypted vault; no vault files means the app never got its key.
# Every simctl call has a deadline, so a wedged CoreSimulator fails this check instead of hanging check-all.
#   APP=<path to .app> overrides the app; SIMULATOR_ID=<udid> picks the device type and runtime to copy.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIGURATION="${CONFIGURATION:-Debug}"
APP="${APP:-$ROOT/iosApp/.ios-build/derived-data/$CONFIGURATION/Build/Products/$CONFIGURATION-iphonesimulator/Vezuna.app}"
LAUNCH_BUDGET_SECONDS=180
DEVICE_PREFIX="Vezuna launch check"
BUNDLE_ID="$(plutil -extract CFBundleIdentifier raw "$APP/Info.plist")"

# Stock macOS has no `timeout`. The alarm survives exec, so it kills the command itself.
within() { # $1 seconds, then the command
  perl -e 'alarm shift; exec @ARGV' "$@" || { local status=$?; echo "IOS_LAUNCH_FAILED: failed or timed out after $1 s: ${*:2}" >&2; return "$status"; }
}

remove_device() {
  within 60 xcrun simctl shutdown "$1" >/dev/null 2>&1 || true
  within 60 xcrun simctl delete "$1" >/dev/null 2>&1 || true
}

# A hard-killed run cannot clean up. Its pid in the device name tells it apart from another lane's live run.
while read -r stale_pid stale_device; do
  kill -0 "$stale_pid" 2>/dev/null || remove_device "$stale_device"
done < <(within 60 xcrun simctl list devices -j | DEVICE_PREFIX="$DEVICE_PREFIX" python3 -c '
import json, os, re, sys
pattern = re.compile(re.escape(os.environ["DEVICE_PREFIX"]) + r" (\d+)")
for devices in json.load(sys.stdin)["devices"].values():
    for d in devices:
        m = pattern.fullmatch(d["name"])
        if m:
            print(m[1], d["udid"])
')

read -r DEVICE_TYPE RUNTIME < <(within 60 xcrun simctl list devices available -j | SIMULATOR_ID="${SIMULATOR_ID:-}" python3 -c '
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

DEVICE="$(within 120 xcrun simctl create "$DEVICE_PREFIX $$" "$DEVICE_TYPE" "$RUNTIME")"
trap 'remove_device "$DEVICE"' EXIT
within 300 xcrun simctl bootstatus "$DEVICE" -b >/dev/null
within 300 xcrun simctl install "$DEVICE" "$APP"
within 120 xcrun simctl launch "$DEVICE" "$BUNDLE_ID" >/dev/null

VAULT="$(within 60 xcrun simctl get_app_container "$DEVICE" "$BUNDLE_ID" data)/Library/Application Support/Vezuna/Vault"
# The vault key's fallback file (IosSecureVault hashes its file names) is not proof of a working Keychain: finding it
# means the Keychain add failed and the key sits in a plain file.
KEY_FILE="$(printf master-key | shasum -a 256 | cut -d' ' -f1)"
SECONDS=0
while (( SECONDS < LAUNCH_BUDGET_SECONDS )); do
  if [[ -n "$(find "$VAULT" -type f ! -name "$KEY_FILE" 2>/dev/null | head -1)" ]]; then
    if [[ -e "$VAULT/$KEY_FILE" ]]; then
      echo "IOS_LAUNCH_FAILED: the vault key fell back to a file, so the Keychain add failed." >&2
      exit 1
    fi
    echo "IOS_LAUNCH_PASSED"
    exit 0
  fi
  sleep 1
done
echo "IOS_LAUNCH_FAILED: the first run wrote nothing to the vault, or timed out after $LAUNCH_BUDGET_SECONDS s. Is the app signed? An unsigned app gets no Keychain (-34018)." >&2
exit 1

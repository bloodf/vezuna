#!/usr/bin/env bash
# Local release signing. Prompts stay in the user's terminal; nothing is uploaded.
set -euo pipefail
umask 077

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-build}"

case "$MODE" in
  create-upload-key|build) ;;
  *) echo "Usage: $0 [create-upload-key|build]" >&2; exit 2 ;;
esac

export VEZUNA_UPLOAD_STORE_FILE="${VEZUNA_UPLOAD_STORE_FILE:-$HOME/.local/share/vezuna/release/vezuna-upload.jks}"
export VEZUNA_UPLOAD_KEY_ALIAS="${VEZUNA_UPLOAD_KEY_ALIAS:-vezuna-upload}"
if [[ -z "${VEZUNA_LVL_PUBLIC_KEY+x}" && -f "$HOME/.local/share/vezuna/release/play-licensing-public-key.txt" ]]; then
  VEZUNA_LVL_PUBLIC_KEY="$(cat "$HOME/.local/share/vezuna/release/play-licensing-public-key.txt")"
  export VEZUNA_LVL_PUBLIC_KEY
fi

if [[ "$MODE" == create-upload-key ]]; then
  if [[ -e "$VEZUNA_UPLOAD_STORE_FILE" ]]; then
    echo "Refusing to overwrite an existing keystore: $VEZUNA_UPLOAD_STORE_FILE" >&2
    exit 1
  fi
else
  [[ -f "$VEZUNA_UPLOAD_STORE_FILE" ]] || {
    echo "Keystore missing. Set VEZUNA_UPLOAD_STORE_FILE or run $0 create-upload-key." >&2
    exit 1
  }
fi

if [[ "$MODE" == build ]]; then
  "$ROOT/gradlew" -p "$ROOT" --no-daemon --no-configuration-cache \
    --init-script "$ROOT/tools/release/license_inventory.gradle" :composeApp:writeVezunaLicenseInventory
  python3 "$ROOT/tools/release/inventory_runtime_notices.py" \
    "$ROOT/build/reports/licenses/android-artifacts.json" --platform android --check
  python3 "$ROOT/tools/release/build_notices.py" --check
  python3 "$ROOT/tools/release/sync_privacy_policy.py" --check
fi

if [[ -z "${VEZUNA_UPLOAD_STORE_PASSWORD:-}" ]]; then
  [[ -t 0 ]] || { echo "Run in your terminal to enter signing passwords." >&2; exit 1; }
  read -r -s -p "Upload keystore password: " VEZUNA_UPLOAD_STORE_PASSWORD
  echo
fi
[[ -n "$VEZUNA_UPLOAD_STORE_PASSWORD" ]] || { echo "Password must not be empty." >&2; exit 1; }
export VEZUNA_UPLOAD_STORE_PASSWORD

if [[ "$MODE" == create-upload-key ]]; then
  [[ -t 0 ]] || { echo "Run in your terminal to confirm the new password." >&2; exit 1; }
  [[ ${#VEZUNA_UPLOAD_STORE_PASSWORD} -ge 12 ]] || { echo "Use at least 12 characters." >&2; exit 1; }
  read -r -s -p "Confirm password: " VEZUNA_PASSWORD_CONFIRM
  echo
  [[ "$VEZUNA_PASSWORD_CONFIRM" == "$VEZUNA_UPLOAD_STORE_PASSWORD" ]] || { echo "Passwords do not match." >&2; exit 1; }
  unset VEZUNA_PASSWORD_CONFIRM
  mkdir -p "$(dirname "$VEZUNA_UPLOAD_STORE_FILE")"
  keytool -genkeypair -noprompt -storetype PKCS12 \
    -keystore "$VEZUNA_UPLOAD_STORE_FILE" -alias "$VEZUNA_UPLOAD_KEY_ALIAS" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -dname 'CN=Vezuna Upload' -storepass:env VEZUNA_UPLOAD_STORE_PASSWORD
  chmod 600 "$VEZUNA_UPLOAD_STORE_FILE"
  echo "Created $VEZUNA_UPLOAD_STORE_FILE. Back it up securely before any upload."
  exit 0
fi

if [[ -z "${VEZUNA_UPLOAD_KEY_PASSWORD:-}" ]]; then
  [[ -t 0 ]] || { echo "Set VEZUNA_UPLOAD_KEY_PASSWORD or run in your terminal." >&2; exit 1; }
  read -r -s -p "Key password (Enter uses keystore password): " VEZUNA_UPLOAD_KEY_PASSWORD
  echo
  VEZUNA_UPLOAD_KEY_PASSWORD="${VEZUNA_UPLOAD_KEY_PASSWORD:-$VEZUNA_UPLOAD_STORE_PASSWORD}"
fi
export VEZUNA_UPLOAD_KEY_PASSWORD

# Disable caches that can retain the signing configuration and its passwords.
"$ROOT/gradlew" -p "$ROOT" --no-daemon --no-configuration-cache \
  :composeApp:bundleRelease :composeApp:assembleRelease \
  "-PVEZUNA_VERSION_CODE=${VEZUNA_VERSION_CODE:-1}" \
  "-PVEZUNA_VERSION_NAME=${VEZUNA_VERSION_NAME:-1.0}"

python3 "$ROOT/tools/release/check_android_native.py" \
  "$ROOT/composeApp/build/outputs/bundle/release/composeApp-release.aab"
python3 "$ROOT/tools/release/check_bundled_notices.py" \
  "$ROOT/composeApp/build/outputs/bundle/release/composeApp-release.aab"
python3 "$ROOT/tools/release/check_bundled_notices.py" \
  "$ROOT/composeApp/build/outputs/apk/release/composeApp-release.apk"
python3 "$ROOT/tools/release/sync_privacy_policy.py" --check --artifact \
  "$ROOT/composeApp/build/outputs/bundle/release/composeApp-release.aab"
python3 "$ROOT/tools/release/sync_privacy_policy.py" --check --artifact \
  "$ROOT/composeApp/build/outputs/apk/release/composeApp-release.apk"
echo "ANDROID_RELEASE_BUILT (local only; store validation and approval remain required)"

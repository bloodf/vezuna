#!/usr/bin/env bash
# Local all-in-one gate (no GitHub CI). Fails fast and prints a summary.
# Release build uses a throwaway keystore: real signing is required by design, never use a real key here.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASSED=()

step() { # $1 name, rest command
  local name="$1"; shift
  echo "==> $name"
  if "$@"; then PASSED+=("$name"); else
    echo; echo "CHECK_ALL_FAILED at: $name"; echo "Passed before failure: ${PASSED[*]:-none}"; exit 1
  fi
}

gradle_all() {
  keytool -genkeypair -keystore "$TMP/check.jks" -storepass check-throwaway -keypass check-throwaway \
    -alias check -keyalg RSA -keysize 2048 -validity 1 -dname 'CN=check' >/dev/null 2>&1 || return 1
  VEZUNA_UPLOAD_STORE_FILE="$TMP/check.jks" VEZUNA_UPLOAD_STORE_PASSWORD=check-throwaway \
  VEZUNA_UPLOAD_KEY_ALIAS=check VEZUNA_UPLOAD_KEY_PASSWORD=check-throwaway \
    ./gradlew :shared:jvmTest :composeApp:assembleDebug :composeApp:testDebugUnitTest \
      :composeApp:assembleDebugAndroidTest :composeApp:lintDebug :composeApp:assembleRelease --console=plain
}

step localization python3 tools/localization/check_localization.py
step brand python3 tools/branding/check_brand.py
step lab-importer python3 tools/lab_importer/test_importer.py
step gradle gradle_all
step ios bash scripts/check-ios.sh
echo; echo "CHECK_ALL_PASSED: ${PASSED[*]}"

#!/usr/bin/env bash
# Local counterpart of .github/workflows/ci.yml. See docs/LOCAL_CI.md.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  echo "Usage: $0 [all|python|gradle|results|release]" >&2
}
stage="${1:-all}"
if [ "$#" -gt 1 ]; then usage; exit 2; fi
case "$stage" in all|python|gradle|results|release) ;; *) usage; exit 2 ;; esac

python_checks() {
  python3 tools/localization/test_check_test_results.py
  python3 tools/localization/check_localization.py
  python3 tools/branding/check_brand.py
  python3 tools/lab_importer/test_importer.py
  python3 tools/release/test_android_native.py
  python3 tools/release/check_identity.py
  python3 tools/release/sync_privacy_policy.py --check
}
gradle_checks() {
  : "${SCRATCH:?Set SCRATCH to the directory containing the existing build-slot.sh}"
  [ -f "$SCRATCH/build-slot.sh" ] || { echo "Missing semaphore: $SCRATCH/build-slot.sh" >&2; return 1; }
  VEZUNA_BUILD_SLOTS=1 GRADLE_OPTS=-Dorg.gradle.daemon=false \
    timeout 3600 bash "$SCRATCH/build-slot.sh" bash gradlew :shared:jvmTest :shared:testAndroidHostTest :composeApp:testDebugUnitTest :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
}
results_checks() {
  python3 tools/localization/check_test_results.py --android-host
  python3 tools/release/check_android_native.py composeApp/build/outputs/apk/debug/composeApp-debug.apk
}
release_checks() {
  npm ci
  npx --no-install eas --version
  timeout 30s node --max-old-space-size=256 tools/release/eas-compatibility.cjs
  bash -n scripts/build-ios.sh
}
run() { echo "==> $1"; "$1"; }
case "$stage" in
  all)
    run python_checks
    run gradle_checks
    run results_checks
    run release_checks
    echo 'CHECK_CI_LOCAL_PASSED: android release-tooling'
    ;;
  python) run python_checks ;;
  gradle) run gradle_checks ;;
  results) run results_checks ;;
  release) run release_checks ;;
esac
if [ "$stage" != all ]; then echo "CHECK_CI_LOCAL_PARTIAL_PASSED: $stage (other stages not run)"; fi

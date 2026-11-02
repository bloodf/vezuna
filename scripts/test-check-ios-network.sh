#!/usr/bin/env bash
# Self-test for check-ios-network.sh using a throwaway tree (no real data).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/scripts" "$T/iosApp/Vezuna" "$T/shared/src/iosMain/kotlin"
: > "$T/scripts/ios-network-allowlist.txt"
printf 'import SwiftUI\n// URLSession mention in a comment is fine\nstruct A {}\n' > "$T/iosApp/Vezuna/A.swift"
bash "$HERE/check-ios-network.sh" "$T" >/dev/null || { echo "FAIL: clean tree rejected"; exit 1; }
for bad in 'let s = URLSession.shared' 'import Network' 'let c = NWConnection(host: h, port: p, using: .tcp)'; do
  printf '%s\n' "$bad" > "$T/iosApp/Vezuna/B.swift"
  bash "$HERE/check-ios-network.sh" "$T" >/dev/null 2>&1 && { echo "FAIL: not caught: $bad"; exit 1; }
done
rm "$T/iosApp/Vezuna/B.swift"
printf 'import platform.Foundation.NSURLSession\n' > "$T/shared/src/iosMain/kotlin/K.kt"
bash "$HERE/check-ios-network.sh" "$T" >/dev/null 2>&1 && { echo "FAIL: Kotlin iosMain not caught"; exit 1; }
printf 'shared/src/iosMain/kotlin/K.kt:NSURLSession\n' > "$T/scripts/ios-network-allowlist.txt"
bash "$HERE/check-ios-network.sh" "$T" >/dev/null 2>&1 || { echo "FAIL: allowlist ignored"; exit 1; }
echo "check-ios-network self-test passed"

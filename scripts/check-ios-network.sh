#!/usr/bin/env bash
# Local guard: app-owned iOS code must not open network connections (health data never leaves the device).
# Scans iosApp Swift/ObjC and shared iosMain Kotlin for networking APIs. Android blocks INTERNET in its manifest;
# iOS has no manifest equivalent, so this check is the only automated barrier.
# Allowlist: scripts/ios-network-allowlist.txt, one "relative/path:Symbol" per line, with a reason in a comment above it.
# Usage: check-ios-network.sh [root]   (root defaults to the repo root; used by the self-test)
set -uo pipefail
ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ALLOW="$ROOT/scripts/ios-network-allowlist.txt"

PATTERN='URLSession|NSURLSession|NSURLConnection|URLRequest|NSURLRequest|NSMutableURLRequest|NWConnection|NWListener|NWPathMonitor|NWBrowser|CFNetwork|CFSocket|CFStream|CFHTTP|WKWebView|SFSafariViewController|ASWebAuthenticationSession|MultipeerConnectivity|MCSession|GKMatch|NetService|NSNetService|GCDAsyncSocket|Alamofire|io\.ktor|platform\.Network|^[[:space:]]*(@_exported[[:space:]]+)?import[[:space:]]+Network([[:space:]]|$)|URLSession\.shared'

DIRS=()
for d in iosApp shared/src/iosMain; do [[ -d "$ROOT/$d" ]] && DIRS+=("$d"); done
[[ ${#DIRS[@]} -gt 0 ]] || { echo "check-ios-network: no iOS source dirs under $ROOT" >&2; exit 2; }

hits="$(cd "$ROOT" && grep -rnE --include='*.swift' --include='*.m' --include='*.mm' --include='*.h' --include='*.kt' \
  --exclude-dir=build --exclude-dir=.build --exclude-dir=DerivedData --exclude-dir='*.xcodeproj' \
  "$PATTERN" "${DIRS[@]}" || true)"

fail=0
while IFS= read -r line; do
  [[ -n "$line" ]] || continue
  file="${line%%:*}"
  text="${line#*:*:}"
  # Comments are not code.
  trimmed="${text#"${text%%[![:space:]]*}"}"
  [[ "$trimmed" == //* || "$trimmed" == \** || "$trimmed" == /\** ]] && continue
  allowed=0
  if [[ -f "$ALLOW" ]]; then
    while IFS= read -r entry; do
      entry="${entry%%#*}"; entry="${entry//[[:space:]]/}"
      [[ -n "$entry" ]] || continue
      apath="${entry%%:*}"; asym="${entry#*:}"
      if [[ "$file" == "$apath" && "$text" == *"$asym"* ]]; then allowed=1; break; fi
    done < "$ALLOW"
  fi
  if [[ $allowed -eq 0 ]]; then echo "NETWORK API: $line"; fail=1; fi
done <<< "$hits"

if [[ $fail -ne 0 ]]; then
  echo "check-ios-network FAILED: networking API in app-owned iOS code. Remove it, or add path:Symbol to scripts/ios-network-allowlist.txt with a justification (and update PRIVACY.md)." >&2
  exit 1
fi
echo "check-ios-network passed"

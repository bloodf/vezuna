#!/usr/bin/env bash
# Local guard: app-owned iOS code must not open network connections (health data never leaves the device).
# Scans iosApp Swift/ObjC/Kotlin plus shared iosMain and commonMain Kotlin (both compile into the iOS framework)
# for networking API NAMES after stripping comments, and greps build files for networking libraries.
# This is a name-pattern heuristic: it cannot see system frameworks, SDK internals, or reflection/dynamic lookup.
# Android blocks INTERNET in its manifest; iOS has no equivalent, so this is the only automated barrier.
# Allowlist: scripts/ios-network-allowlist.txt, one "relative/path:Symbol" per line, with a reason in a comment above it.
# Usage: check-ios-network.sh [root]   (root defaults to the repo root; used by the self-test)
set -uo pipefail
ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ALLOW="$ROOT/scripts/ios-network-allowlist.txt"

PATTERN='\b(URLSession\w*|NSURLSession\w*|NSURLConnection|URLRequest|NSURLRequest|NSMutableURLRequest|NWConnection|NWListener|NWPathMonitor|NWBrowser|NWEndpoint|CFNetwork|CFSocket|CF(Read|Write)?Stream\w*|CFHTTP\w*|CFHost\w*|CFURLConnection|NSInputStream|NSOutputStream|WKWebView|WKWebViewConfiguration|SFSafariViewController|ASWebAuthenticationSession|MultipeerConnectivity|MCSession|GKMatch|NetService|NSNetService|GCDAsyncSocket|Alamofire|AsyncImage|Link|openURL|dataWithContentsOfURL|stringWithContentsOfURL|initWithContentsOfURL|getStreamsToHost\w*|UIWebView|CloudKit|CKContainer|playerWithURL|getaddrinfo|gethostbyname|socket|nw_\w+)\b|io\.ktor|okhttp3|platform\.Network|platform\.posix\.(socket|connect|getaddrinfo|gethostbyname|send|recv)|@import[[:space:]]+Network|#import[[:space:]]*<Network/|\bimport[[:space:]]+(typealias[[:space:]]+|struct[[:space:]]+|class[[:space:]]+|func[[:space:]]+)?Network([.[:space:]]|$)|\b(Data|String|NSData|NSString)\(contentsOf:[^)]*(URL\(string|https?:)|InputStream\(url:|AVPlayer\(url:|AVPlayerItem\(url:|AVURLAsset\(url:|UIApplication[A-Za-z.]*\.open\(|\bconnect[[:space:]]*\([^)]*sock'

export PATTERN
DIRS=()
for d in iosApp shared/src/iosMain shared/src/commonMain; do
  [[ -d "$ROOT/$d" ]] || { echo "check-ios-network: missing source dir $d under $ROOT" >&2; exit 2; }
  DIRS+=("$d")
done

# Strip /* */ (keeping newlines so line numbers hold) and // comments (not the // in a URL), then print matching lines.
hits="$(cd "$ROOT" && find "${DIRS[@]}" \( -name build -o -name .build -o -name DerivedData -o -path iosApp/Frameworks -o -name '*.xcodeproj' \) -prune -o \
  -type f \( -name '*.swift' -o -name '*.m' -o -name '*.mm' -o -name '*.h' -o -name '*.c' -o -name '*.cc' -o -name '*.cpp' -o -name '*.hpp' -o -name '*.kt' \) -print0 \
  | xargs -0 perl -e '
      $re = qr/$ENV{PATTERN}/;
      for $f (@ARGV) {
        open(my $h, "<", $f) or do { print STDERR "unreadable: $f\n"; exit 3 };
        local $/; my $s = <$h>; close $h;
        # One pass so a "/*" or "//" inside a string is not read as a comment. Strings stay, so a name in one is flagged.
        $s =~ s{("""(?s:.*?)"""|"(?:\\.|[^"\\\n])*"|\x27(?:\\.|[^\x27\\\n])\x27)|/\*.*?\*/|//[^\n]*}{defined $1 ? $1 : ($& =~ tr/\n//cdr)}gse;
        my $n = 0;
        for my $l (split /\n/, $s, -1) { $n++; print "$f:$n:$l\n" if $l =~ $re; }
      }' )"
rc=$?
if [[ $rc -ne 0 ]]; then echo "check-ios-network: scan error (rc=$rc)" >&2; exit 2; fi

fail=0
while IFS= read -r line; do
  [[ -n "$line" ]] || continue
  file="${line%%:*}"
  text="${line#*:*:}"
  allowed=0
  if [[ -f "$ALLOW" ]]; then
    while IFS= read -r entry; do
      entry="${entry%%#*}"; entry="${entry//[[:space:]]/}"
      [[ -n "$entry" ]] || continue
      apath="${entry%%:*}"; asym="${entry#*:}"
      [[ "$entry" == *:* && -n "$asym" ]] || { echo "check-ios-network: allowlist entry needs path:Symbol: $entry" >&2; exit 2; }
      if [[ "$file" == "$apath" && "${text//[[:space:]]/}" == *"$asym"* ]]; then allowed=1; break; fi
    done < "$ALLOW"
  fi
  if [[ $allowed -eq 0 ]]; then echo "NETWORK API: $line"; fail=1; fi
done <<< "$hits"

# Networking libraries reachable from the iOS framework or app.
dep_files=()
for f in shared/build.gradle.kts gradle/libs.versions.toml iosApp/SharedBridge.gradle iosApp/Podfile iosApp/Package.swift; do [[ -f "$ROOT/$f" ]] && dep_files+=("$f"); done
for f in iosApp/Vezuna.xcodeproj/project.pbxproj; do [[ -f "$ROOT/$f" ]] && dep_files+=("$f"); done
if [[ ${#dep_files[@]} -gt 0 ]]; then
  dhits="$(cd "$ROOT" && grep -nEi 'ktor|okhttp|retrofit|alamofire|apollo|afnetworking|XCRemoteSwiftPackageReference|^[[:space:]]*pod[[:space:]]' "${dep_files[@]}")"
  drc=$?
  if [[ $drc -gt 1 ]]; then echo "check-ios-network: dependency grep error (rc=$drc)" >&2; exit 2; fi
  if [[ -n "$dhits" ]]; then echo "NETWORK DEPENDENCY:"; echo "$dhits"; fail=1; fi
fi

if [[ $fail -ne 0 ]]; then
  echo "check-ios-network FAILED: networking API or library in app-owned iOS code. Remove it, or add path:Symbol to scripts/ios-network-allowlist.txt with a justification (and update PRIVACY.md)." >&2
  exit 1
fi
echo "check-ios-network passed"

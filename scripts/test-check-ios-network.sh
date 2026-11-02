#!/usr/bin/env bash
# Self-test for check-ios-network.sh using a throwaway tree (no real data).
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/scripts" "$T/iosApp/Vezuna" "$T/iosApp/SharedBridge" "$T/shared/src/iosMain/kotlin" "$T/shared/src/commonMain/kotlin"
: > "$T/scripts/ios-network-allowlist.txt"
run() { bash "$HERE/check-ios-network.sh" "$T" >/dev/null 2>&1; }
printf 'import SwiftUI\n// URLSession mention in a comment is fine\n/* multi\n   line URLSession */\nlet d = try? Data(contentsOf: fileURL)\nstruct A {}\n' > "$T/iosApp/Vezuna/A.swift"
run || { echo "FAIL: clean tree rejected"; exit 1; }

expect_caught() { # file, content
  printf '%s\n' "$2" > "$T/$1"
  run; rc=$?
  [[ $rc -eq 1 ]] || { echo "FAIL: expected rc=1, got rc=$rc in $1: $2"; exit 1; }
  rm "$T/$1"
}
SW=iosApp/Vezuna/B.swift
for bad in \
  'let s = URLSession.shared' 'import Network' '@preconcurrency import Network' 'let c = NWConnection(host: h, port: p, using: .tcp)' \
  'let d = try Data(contentsOf: URL(string: "https://x.test")!)' 'let s = try String(contentsOf: URL(string: "http://x.test")!)' \
  'let i = InputStream(url: u)' 'Stream.getStreamsToHost(withName: h, port: 80, inputStream: &a, outputStream: &b)' \
  'let r = CFReadStreamCreateWithFTPURL(nil, u)' 'let w = CFWriteStreamCreateWithBuffer(nil, b, 1)' 'CFStreamCreatePairWithSocketToHost(nil, h, 80, &a, &b)' \
  'let fd = socket(AF_INET, SOCK_STREAM, 0)' 'getaddrinfo(h, nil, nil, &res)' 'let c = nw_connection_create(e, p)' \
  'AsyncImage(url: u)' 'let p = AVPlayer(url: u)' 'UIApplication.shared.open(u)' 'Link("x", destination: u)' 'Environment(\.openURL) var openURL' \
  'let w = WKWebView(frame: .zero)' '/* x */ let s = URLSession.shared' 'let a = 1 /* c */ ; URLSession.shared' 'let s = "http://a"; URLSession.shared'; do
  expect_caught "$SW" "$bad"
done
for bad in '@import Network;' '#import <Network/Network.h>' '[NSData dataWithContentsOfURL:u];' '[NSString stringWithContentsOfURL:u encoding:1 error:nil];' 'int fd = socket(AF_INET, SOCK_STREAM, 0);'; do
  expect_caught iosApp/Vezuna/C.m "$bad"
done
for bad in 'import platform.Foundation.NSURLSession' 'import platform.posix.socket' 'import io.ktor.client.HttpClient' 'import platform.Network.nw_connection_create'; do
  expect_caught shared/src/iosMain/kotlin/K.kt "$bad"
  expect_caught shared/src/commonMain/kotlin/K.kt "$bad"
  expect_caught iosApp/SharedBridge/K.kt "$bad"
done

# A "/*" or "//" inside a string or line comment must not hide the code after it.
for bad in $'let g = "/*"\nlet s = URLSession.shared\nlet h = "*/"' $'// path /*\nlet s = URLSession.shared\n/* note */' 'let a = "x//y"; URLSession.shared' \
  'Stream.getStreamsToHostWithName(h)' 'let w = UIWebView()' 'import CloudKit' 'let c = CKContainer.default()'; do
  expect_caught "$SW" "$bad"
done
expect_caught iosApp/Vezuna/D.cpp 'int fd = socket(AF_INET, SOCK_STREAM, 0);'

# Build output is not scanned, so results do not depend on local build state.
mkdir -p "$T/iosApp/Frameworks"; printf 'let s = URLSession.shared\n' > "$T/iosApp/Frameworks/G.swift"
run || { echo "FAIL: iosApp/Frameworks was scanned"; exit 1; }
rm -r "$T/iosApp/Frameworks"

# An allowlist entry with no symbol is an error, not a whole-file pass.
printf 'iosApp/Vezuna/B.swift:\n' > "$T/scripts/ios-network-allowlist.txt"
printf 'let s = URLSession.shared\n' > "$T/$SW"
run; rc=$?; [[ $rc -eq 2 ]] || { echo "FAIL: empty allowlist symbol gave rc=$rc"; exit 1; }
rm "$T/$SW"; : > "$T/scripts/ios-network-allowlist.txt"

# A missing source dir is an error, not a smaller scan.
mv "$T/shared/src/commonMain" "$T/shared/src/commonMain.off"
run; rc=$?; [[ $rc -eq 2 ]] || { echo "FAIL: missing source dir gave rc=$rc"; exit 1; }
mv "$T/shared/src/commonMain.off" "$T/shared/src/commonMain"

# Networking dependency in build files.
printf 'implementation("io.ktor:ktor-client-darwin:3.0.0")\n' > "$T/shared/build.gradle.kts"
run && { echo "FAIL: ktor dependency not caught"; exit 1; }
rm "$T/shared/build.gradle.kts"

# Unreadable file must fail the check, not pass it.
printf 'struct Z {}\n' > "$T/iosApp/Vezuna/Z.swift"; chmod 000 "$T/iosApp/Vezuna/Z.swift"
if [[ "$(id -u)" -ne 0 ]]; then
  run && { echo "FAIL: unreadable file passed"; exit 1; }
fi
chmod 644 "$T/iosApp/Vezuna/Z.swift"; rm "$T/iosApp/Vezuna/Z.swift"

printf 'import platform.Foundation.NSURLSession\n' > "$T/shared/src/iosMain/kotlin/K.kt"
printf 'shared/src/iosMain/kotlin/K.kt:NSURLSession\n' > "$T/scripts/ios-network-allowlist.txt"
run || { echo "FAIL: allowlist ignored"; exit 1; }
echo "check-ios-network self-test passed"

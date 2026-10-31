#!/usr/bin/env bash
# Builds the iOS app.
#   scripts/build-ios.sh                     simulator build (default; CONFIGURATION=Debug|Release)
#   scripts/build-ios.sh device-archive      signed Release .xcarchive for physical devices / App Store
#   scripts/build-ios.sh device-export       device-archive + .ipa export (needs EXPORT_METHOD, see docs/RELEASING.md)
# Device modes require DEVELOPMENT_TEAM (Apple Team ID). Nothing is uploaded.
# Framework slices live in iosApp/Frameworks/<Configuration>/<sdk>/ (matches FRAMEWORK_SEARCH_PATHS),
# so simulator and device builds coexist.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-simulator}"
# Deployment target: iOS 18.5 (set in the Xcode project and shared/build.gradle.kts), required by the Skiko ICU object.

GRADLE=("$ROOT/gradlew" -p "$ROOT" --init-script "$ROOT/iosApp/SharedBridge.gradle")

copy_framework() { # $1 source framework, $2 destination subdir under iosApp/Frameworks
  [[ -d "$1" ]] || { echo "Framework not found: $1" >&2; exit 1; }
  mkdir -p "$ROOT/iosApp/Frameworks/$2"
  rm -rf "$ROOT/iosApp/Frameworks/$2/VitaHearthShared.framework"
  cp -R "$1" "$ROOT/iosApp/Frameworks/$2/VitaHearthShared.framework"
}

case "$MODE" in
  simulator)
    CONFIGURATION="${CONFIGURATION:-Debug}"
    if [[ "$CONFIGURATION" == "Release" ]]; then
      TASK=":shared:linkReleaseFrameworkIosSimulatorArm64"; KIND="releaseFramework"
    else
      TASK=":shared:linkDebugFrameworkIosSimulatorArm64"; KIND="debugFramework"
    fi
    "${GRADLE[@]}" "$TASK"
    PLATFORM_NAME=iphonesimulator ARCHS=arm64 "$ROOT/gradlew" -p "$ROOT" :shared:syncComposeResourcesForIos
    copy_framework "$ROOT/shared/build/bin/iosSimulatorArm64/$KIND/VitaHearthShared.framework" "$CONFIGURATION/iphonesimulator"
    xcodebuild \
      -project "$ROOT/iosApp/VitaHearth.xcodeproj" \
      -scheme VitaHearth \
      -configuration "$CONFIGURATION" \
      -sdk iphonesimulator \
      -destination 'generic/platform=iOS Simulator' \
      -derivedDataPath "$ROOT/iosApp/.ios-build/derived-data/$CONFIGURATION" \
      CODE_SIGNING_ALLOWED=NO \
      ARCHS=arm64 \
      build
    echo "IOS_BUILD_PASSED"
    ;;
  device-archive|device-export)
    : "${DEVELOPMENT_TEAM:?Set DEVELOPMENT_TEAM to your Apple Developer Team ID}"
    if [[ "$MODE" == "device-export" ]]; then
      : "${EXPORT_METHOD:?Set EXPORT_METHOD: app-store-connect | release-testing | debugging | enterprise}"
    fi
    OUT="${OUT_DIR:-$ROOT/build/ios}"
    mkdir -p "$OUT"

    "${GRADLE[@]}" :shared:linkReleaseFrameworkIosArm64
    PLATFORM_NAME=iphoneos ARCHS=arm64 "$ROOT/gradlew" -p "$ROOT" :shared:syncComposeResourcesForIos
    copy_framework "$ROOT/shared/build/bin/iosArm64/releaseFramework/VitaHearthShared.framework" "Release/iphoneos"

    VERSION_ARGS=()
    [[ -n "${VEZUNA_BUILD_NUMBER:-}" ]] && VERSION_ARGS+=("CURRENT_PROJECT_VERSION=$VEZUNA_BUILD_NUMBER")
    [[ -n "${VEZUNA_VERSION_NAME:-}" ]] && VERSION_ARGS+=("MARKETING_VERSION=$VEZUNA_VERSION_NAME")

    ARCHIVE="$OUT/Vezuna.xcarchive"
    rm -rf "$ARCHIVE"
    xcodebuild archive \
      -project "$ROOT/iosApp/VitaHearth.xcodeproj" \
      -scheme VitaHearth \
      -configuration Release \
      -destination 'generic/platform=iOS' \
      -archivePath "$ARCHIVE" \
      -derivedDataPath "$ROOT/iosApp/.ios-build/derived-data/DeviceRelease" \
      -allowProvisioningUpdates \
      DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" \
      IPHONEOS_DEPLOYMENT_TARGET=18.5 \
      ${VERSION_ARGS[@]+"${VERSION_ARGS[@]}"}
    echo "IOS_ARCHIVE_PASSED $ARCHIVE"

    if [[ "$MODE" == "device-export" ]]; then
      PLIST="$OUT/ExportOptions.plist"
      cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>$EXPORT_METHOD</string>
  <key>teamID</key><string>$DEVELOPMENT_TEAM</string>
  <key>signingStyle</key><string>automatic</string>
  <key>destination</key><string>export</string>
  <key>stripSwiftSymbols</key><true/>
  <key>uploadSymbols</key><false/>
</dict>
</plist>
EOF
      rm -rf "$OUT/export"
      xcodebuild -exportArchive \
        -archivePath "$ARCHIVE" \
        -exportOptionsPlist "$PLIST" \
        -exportPath "$OUT/export" \
        -allowProvisioningUpdates
      echo "IOS_EXPORT_PASSED $OUT/export"
    fi
    ;;
  *)
    echo "Usage: $0 [simulator|device-archive|device-export]" >&2
    exit 2
    ;;
esac

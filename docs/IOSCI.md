# iOS native CI

`.github/workflows/ios-native.yml` uses the standard GitHub-hosted `macos-15`
Apple Silicon runner. Job-scoped `DEVELOPER_DIR` selects the installed Xcode
26.3 because `AssetPackManager` requires an iOS 26 SDK. The runner default
Xcode 16.4 ships SDK 18.5 and cannot compile that API even behind a runtime
availability guard. The installed toolchain is listed in the
[official runner inventory](https://github.com/actions/runner-images/blob/main/images/macos/macos-15-arm64-Readme.md).
The toolchain step records the selected Xcode and simulator SDK. The job reuses the Java, Python and action versions from
`ci.yml`, the Gradle wrapper, and `scripts/build-ios.sh`.

The job compiles Kotlin iOS test sources with
`:shared:compileTestKotlinIosSimulatorArm64`, including the existing
`iosApp/SharedBridge.gradle` source injection. The build script links the shared
framework, syncs Compose resources, builds the app and checks bundled metadata.
Finally, `xcodebuild build-for-testing` compiles the `VezunaTests` target.
Ad-hoc signing follows the existing simulator build script and needs no Apple
account or signing secrets.

This is compile coverage for issue #104. A passing job does not establish
XCTest runtime success, app launch, device behavior or local AI inference.
`:shared:iosSimulatorArm64Test` and `xcodebuild test` remain manual and deferred;
they are not enabled by this workflow. See `docs/DEVELOPMENT.md` for the existing
manual test commands. Do not run AI inference in a simulator. Physical-device
acceptance remains separate.

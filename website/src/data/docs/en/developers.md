---
title: Developer guide
description: Set up the native repository, run synthetic checks, preserve localization and branding, review models, and submit signed builds.
lang: en
order: 6
audience: developer
translationKey: developers
essential: false
---

Vezuna is a native Kotlin Multiplatform and Compose Multiplatform project with native Android and Xcode iOS shells. It is not React Native, an Expo app, or a web view.

## Repository structure

```text
shared/      Domain, Compose UI, localization, vault contracts, models, exporters
composeApp/  Android app (applicationId app.vezuna) and native integrations
iosApp/      Xcode app (bundle ID app.vezuna), SwiftUI shell, Kotlin bridge
tools/       Python importer, localization, branding, and release checks
fixtures/    Synthetic importer fixtures
brand/       Supplied brand assets and licenses
website/     Static marketing site and public documentation
```

The shared Apple framework is `VezunaShared`; the declared app group is `group.app.vezuna`. The current pre-release identity is `app.vezuna`. Changing it creates a separate installation and does not transfer an earlier sandbox or Android Keystore keys.

## Requirements

- **JDK 17**
- **Android SDK API 37**; `compileSdk` and `targetSdk` are 37, `minSdk` is 26
- **Python 3** for repository tools
- Included Gradle wrapper: `./gradlew`
- **macOS with Xcode** for iOS, deployment target **18.5**
- **Node 20+** for EAS Submit; root `eas-cli` is pinned to **27.2.0**
- For the website only, a supported even Node release defined in `website/package.json`

As checked on 2026-10-31, App Store uploads require the **iOS 26 SDK or later**. This SDK floor is separate from the iOS 18.5 deployment target. Recheck store requirements before uploading.

Never commit `local.properties`, keystores, store credentials, `samples/`, model binaries, or `models/`.

## Local checks

Run from the repository root:

```bash
python3 tools/localization/check_localization.py
python3 tools/branding/check_brand.py
python3 tools/lab_importer/test_importer.py
./gradlew :shared:jvmTest :composeApp:assembleDebug :composeApp:testDebugUnitTest :composeApp:assembleDebugAndroidTest
```

Public tests use synthetic fixtures. Private `samples/` content is ignored and is never read by builds or tests. Do not replace synthetic records with real medical documents.

## Android instrumentation

A connected emulator or device is required:

```bash
python3 tools/release/stage_smoke_model.py models/qwen2.5-0.5b-instruct-q4_k_m.gguf
./gradlew :composeApp:connectedDebugAndroidTest
python3 tools/localization/check_test_results.py --instrumentation
```

The inference test requires the exact official Qwen fixture pinned in `third_party/qwen2.5-0.5b-instruct-q4_k_m/provenance.json`. Staging verifies its size and SHA-256 and copies it into test-only assets. Without staging, `LocalLlmInstrumentedTest` fails because `smoke.gguf` is missing. Never upload the test APK or commit the model.

## iOS simulator builds

```bash
bash scripts/build-ios.sh
CONFIGURATION=Release bash scripts/build-ios.sh
```

A successful build prints `IOS_BUILD_PASSED`. The script links the shared framework, syncs Compose resources, and disables code signing for simulator builds. Simulator compilation does not establish iPhone runtime behavior, store signing, hosted-asset delivery, or physical-device correctness.

## Localization and branding

Shared and native resources support English, Brazilian Portuguese, and Spanish. Keep string identifiers stable, preserve plural and accessibility catalogs, and run the localization checks after edits. Decimal-comma input must preserve canonical stored values.

Use supplied files under `brand/` rather than redrawing logos. Run `tools/branding/sync_assets.py` when synchronizing platform assets and `tools/branding/check_brand.py` to check identifiers, hashes, and brand constraints. Noto Sans retains its SIL OFL license; original artwork has its own MIT license and does not grant trademark rights.

## Model provenance and delivery

`ModelCatalog.unconfigured()` is intentionally empty. Do not invent model URLs, asset identifiers, sizes, or hashes. Every production pack needs a stable ID, GGUF filename, platform asset-pack IDs, exact size, minimum RAM, context limit, and lowercase SHA-256.

Record the upstream model URL, exact revision, license, conversion or quantization source, and redistribution obligations. Llamatik 2.1.0 native-library attribution remains an explicit release blocker. Production Play Asset Delivery, Apple-hosted assets, licensing, memory, and inference require store and physical-device tests.

## Android signed release

Create the upload keystore outside the repository and back it up:

```sh
keytool -genkeypair -v -keystore ~/vezuna-upload.jks -alias vezuna-upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Set signing values locally:

```sh
export VEZUNA_UPLOAD_STORE_FILE=$HOME/vezuna-upload.jks
export VEZUNA_UPLOAD_STORE_PASSWORD=...
export VEZUNA_UPLOAD_KEY_ALIAS=vezuna-upload
export VEZUNA_UPLOAD_KEY_PASSWORD=...
./gradlew :composeApp:bundleRelease -PVEZUNA_VERSION_CODE=2
./gradlew :composeApp:assembleRelease
```

Android defaults to version name `1.0` and version code `1`; use an increasing integer version code for every upload. Outputs are:

```text
composeApp/build/outputs/bundle/release/composeApp-release.aab
composeApp/build/outputs/apk/release/composeApp-release.apk
```

Verify artifacts with the appropriate tools:

```sh
apksigner verify --print-certs composeApp/build/outputs/apk/release/composeApp-release.apk
jarsigner -verify -verbose -certs composeApp/build/outputs/bundle/release/composeApp-release.aab
python3 tools/release/test_android_native.py
python3 tools/release/check_android_native.py composeApp/build/outputs/bundle/release/composeApp-release.aab
```

The native-library checker requires 64-bit libraries and checks ELF `PT_LOAD` segments for 16 KB alignment. Its success marker is `ANDROID_ELF_16KB_PASSED`; it does not validate signatures, generated APK ZIP alignment, or runtime behavior. Perform the separate bundletool/`zipalign` and 16 KB device checks in the full release guide.

## iOS device archive and IPA

A paid Apple Developer membership, distribution signing, the `app.vezuna` App ID, and a Team ID are required:

```sh
export DEVELOPMENT_TEAM=ABCDE12345
VEZUNA_BUILD_NUMBER=2 bash scripts/build-ios.sh device-archive
EXPORT_METHOD=app-store-connect bash scripts/build-ios.sh device-export
```

`EXPORT_METHOD` supports `app-store-connect`, `release-testing`, `debugging`, and `enterprise`. Outputs are `build/ios/Vezuna.xcarchive` and `build/ios/export/*.ipa`. Inspect the final archive’s privacy manifests, signature, entitlements, and provisioning profile before export.

## Submit externally built binaries with Expo EAS

Expo is used **only for EAS Submit**. It uploads an externally built, already signed `.aab` or `.ipa`; it does not build or sign the native app, configure health permissions, or complete store review. Nothing in the repository uploads automatically.

```sh
npm install
npx eas login
```

For Google Play:

```sh
export GOOGLE_SERVICE_ACCOUNT_KEY_PATH=$HOME/play-service-account.json
npm run submit:android -- composeApp/build/outputs/bundle/release/composeApp-release.aab
```

The **first AAB must be uploaded manually in Play Console**. EAS Submit works from the second version onward. The configured profile uses the `internal` track with `releaseStatus: draft`.

For App Store Connect:

```sh
export ASC_API_KEY_PATH=$HOME/AuthKey_XXXXXXXXXX.p8
export ASC_API_KEY_ISSUER_ID=...
export ASC_API_KEY_ID=XXXXXXXXXX
npm run submit:ios -- build/ios/export/Vezuna.ipa
```

Use an IPA exported with `EXPORT_METHOD=app-store-connect`. Never submit debug APKs, test APKs, private records, or unlicensed model files.

## Contributions and release gates

Review `CONTRIBUTING.md`, `SECURITY.md`, and `THIRD_PARTY_NOTICES.md` before submitting changes. Use synthetic data only. Report vulnerabilities privately through GitHub rather than publishing exploit details.

Before publication, configure production signing, Play licensing, real model catalogs and hosted assets, required Health Connect policy entry points, and publisher disclosures. Test HealthKit, Health Connect, camera, OCR quality, actual notification timing, backup behavior, exports, model memory and speed, purchases, and hosted downloads on physical devices. Compilation alone does not satisfy these gates.

The full source guides remain in the repository: [Development](https://github.com/bloodf/vezuna/blob/main/docs/DEVELOPMENT.md), [Releasing](https://github.com/bloodf/vezuna/blob/main/docs/RELEASING.md), and [Architecture](https://github.com/bloodf/vezuna/blob/main/docs/ARCHITECTURE.md).

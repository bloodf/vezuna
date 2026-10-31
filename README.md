# Vezuna

Vezuna is a paid, fully offline household health-record app for Android and iOS. It uses Kotlin Multiplatform and Compose Multiplatform with native platform integrations. The visible name is Vezuna; the preserved application and bundle ID is `com.bloodf.vitahearth`.

Source code is [MIT licensed](LICENSE). Paid store distribution and open-source licensing are independent. Fonts and third-party components retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).

**Status:** release preparation. Store signing, model delivery, physical-device checks, and store review remain required. This app records information; it does not diagnose conditions or recommend treatment. Confirm imported results against the original report.

## Project guides

- [Development setup](docs/DEVELOPMENT.md) and [architecture](docs/ARCHITECTURE.md)
- [Native builds and Expo EAS submission](docs/RELEASING.md)
- [Contributing](CONTRIBUTING.md) and [security reporting](SECURITY.md)
- [Privacy and local storage](PRIVACY.md)
- [Third-party licenses](THIRD_PARTY_NOTICES.md) and [changelog](CHANGELOG.md)

## Implemented capabilities

- Encrypted, profile-isolated health records and household switching.
- Camera, image, and PDF import with embedded-text extraction and offline OCR fallback.
- Structured unconfirmed OCR candidates, manual correction, revision history, and duplicate prevention.
- Longitudinal lab comparisons and confirmed-result trend graphs.
- Medication schedules, local reminders, taken/skipped/snoozed actions, and reboot/timezone rescheduling.
- Read-only Health Connect and HealthKit integration for heart rate, steps, and weight.
- Multiple local GGUF models with installation, streaming SHA-256 verification, selection, switching, deletion, and truthful loaded state.
- Fully local Llamatik inference; no AI service or app server.
- Device-language English, Portuguese, and Spanish across shared and native interfaces, notifications, permissions, errors, accessibility labels, plurals, and date/time presentation. Decimal-comma input preserves canonical stored values.
- Supplied Vezuna logo and native icons, local Noto Sans 400/700 with license, semantic light/dark themes, enlarged-text support, and reduced-motion-aware animations.
- Clinical lab-style PDF reports with exam sections, result/reference columns, status indicators, history tables, charts, and native sharing, plus CSV export.
- Store-mediated AI/model activation through Play LVL and StoreKit AppTransaction. Records and exports are never license-gated.

## Offline and privacy boundaries

There is no application server and no health-data HTTP client. Android removes Internet permission from the merged application manifest. Store services handle purchase verification and model-pack delivery without receiving health records.

Android uses a non-exportable AES-256 key in Android Keystore with AES-GCM. iOS uses CryptoKit AES-GCM and protected app-private files, not Keychain-backed key storage. See [privacy details](PRIVACY.md) for file protection, backup exclusions, and limitations. No independent security audit is claimed.

`BackupService` creates an authenticated encrypted profile envelope for same-device restore. Portable cross-device recovery requires a separately designed user-passphrase envelope; it is not claimed by this version.

Private source PDFs stay under the ignored `samples/` directory and are never build assets. Public importer and Android OCR tests use synthetic data.

## Native project layout

- `shared/`: domain, localized Compose UI and resources, encrypted-vault contracts, model lifecycle, comparisons, and PDF/CSV exporters.
- `composeApp/`: Android app, native resources, encrypted persistence, Health Connect, ML Kit/PDF import, reminders, Play LVL, Play Asset Delivery, and native sharing.
- `iosApp/`: Xcode app, localized SwiftUI/resources, encrypted persistence, HealthKit, PDFKit/Vision import, reminders, StoreKit, hosted asset loaders, and native sharing. Minimum supported iOS: 18.5.
- `tools/lab_importer/`: reference parser and synthetic golden/privacy regression checks.
- `tools/localization/`: catalog parity/completeness and nested JUnit result checks.
- `tools/branding/`: reproducible supplied-asset synchronization and brand/token/identifier checks.
- `fixtures/lab_importer/`: synthetic golden fixtures with no direct identifiers.

## Model-pack setup

`ModelCatalog.unconfigured()` is intentionally empty until real store assets are configured. The project does not invent model URLs, asset IDs, sizes, or hashes. Every `ModelPack` requires a stable ID and GGUF filename, separate Android/iOS asset-pack IDs, exact size, minimum RAM, context limit, and lowercase SHA-256.

Play Asset Delivery and Apple-hosted asset packs/ODR require your developer accounts, signed catalog metadata, uploaded model packs, and store testing. Model selection verifies integrity with streaming SHA-256 on both platforms. AI/model activation may use store licensing; records, backup, and export never do.

## Build and verification

Requirements: JDK 17, Android SDK 37, macOS with Xcode for iOS.

```bash
python3 tools/localization/check_localization.py
python3 tools/branding/check_brand.py
./gradlew :shared:jvmTest :composeApp:assembleDebug :composeApp:testDebugUnitTest :composeApp:assembleDebugAndroidTest
./gradlew :composeApp:connectedDebugAndroidTest
python3 tools/localization/check_test_results.py --instrumentation
python3 tools/lab_importer/test_importer.py
bash scripts/build-ios.sh
```

Android instrumentation requires a connected emulator or device. Its committed OCR image and runtime-generated PDF use fictional data. Device and store tests are separate release gates; successful compilation alone does not prove health permissions, notification delivery, or local-model performance.

## Release steps and known limits

- Configure Play LVL public key, App Store/Play signing, and real model-pack catalogs/assets.
- Run Play/App Store licensing and hosted-model download tests through the stores.
- Validate HealthKit, Health Connect, camera, notifications, OCR quality, and model performance on physical devices.
- Install an iOS simulator runtime or use a physical iPhone for iOS launch/runtime testing; the current environment has no iOS runtime installed.
- Verify actual elapsed notification delivery; workflow and scheduling tests do not prove wall-clock delivery on physical devices.
- iOS 18.5 is the minimum supported version, aligned with the bundled Skiko ICU object. Older iOS versions are unsupported.
- The JVM PDF exporter is an inspection fallback; production Android/iOS exporters preserve native Unicode rendering.

## Publishing with Expo

Vezuna stays Kotlin Multiplatform and native iOS; it is not an Expo/React Native app. EAS Submit uploads signed, externally built `.aab` and `.ipa` files. It does not build or sign these binaries, configure health permissions, or complete store review. Follow [RELEASING](docs/RELEASING.md) for account setup and commands. Never upload debug APKs, test APKs, private records, or unlicensed model files.

# Architecture

Vezuna is a native Kotlin Multiplatform app. It is not React Native or a web view. Shared logic and Compose UI live in `shared/`. Each platform has its own native shell for OS integrations.

```
shared/      Kotlin Multiplatform: domain, Compose UI, vault contract, models, exporters
composeApp/  Android application (applicationId com.bloodf.vitahearth)
iosApp/      Xcode project (bundle ID com.bloodf.vitahearth), SwiftUI shell + Kotlin bridge
tools/       Python checks: lab importer, localization, branding
fixtures/    Synthetic importer fixtures
brand/       Supplied brand kit (separate licensing, see THIRD_PARTY_NOTICES.md)
```

The ID `com.bloodf.vitahearth` and package `com.bloodf.vitahearth` predate the Vezuna name and are kept on purpose so stored data and store identity do not change.

## shared/

Targets: Android library, JVM (tests and inspection fallback), `iosArm64`, `iosSimulatorArm64` (static framework `VitaHearthShared`, iOS 18.5 minimum).

| Package (`shared/src/commonMain/kotlin/com/bloodf/vitahearth/`) | Role |
|---|---|
| `ui/` | Compose screens, theme and tokens, `AppController` (import, review, confirm flow) |
| `domain/` | Profiles, lab observations, units, reference assessment, duplicate fingerprints, trends |
| `security/` | `SecureVault` contract, `CipherEnvelope`, `BackupService` |
| `llm/` | `ModelPack`, `ModelCatalog`, `InstalledModelManager`, `LocalHealthChat`, prompt builder |
| `localization/` | Locale mapping, decimal input, date formatting, message keys |
| `data/` | Repository interface and encrypted storage |

Resources (`composeResources/`) hold strings in English, Portuguese, and Spanish, Noto Sans, and Vezuna marks.

Platform `expect`/`actual` code: `createPlatformVault` (Android Keystore, iOS file-protected key + CryptoKit bridge, JVM test key), localization, hashing, and the Llamatik adapter.

## Android (composeApp/)

`AndroidMainActivity` hosts the shared Compose app. Native pieces:

- `importer/`: system pickers, camera capture, ML Kit OCR, PDFBox text extraction.
- `health/`: Health Connect read for heart rate, steps, weight, with change tokens.
- `notifications/`: `AlarmManager` reminders, dose actions, reschedule on boot/time/timezone/package replace.
- `export/`: system document creation and `FileProvider` sharing from app cache.
- `llm/`: model file store with streaming SHA-256 and path-safe file names; `PlayAssetPackLoader`.
- `license/`: `PlayLvlLicenseGate`, backed by vendored Google Play Licensing code under `com/google/android/vending/licensing/` (Apache-2.0).

The manifest removes `INTERNET`, disables backup, and excludes all data from cloud backup and device transfer.

## iOS (iosApp/)

SwiftUI shell around the Kotlin framework. `iosApp/SharedBridge/` is Kotlin compiled into the framework (injected by `iosApp/SharedBridge.gradle` through `scripts/build-ios.sh`). Swift pieces: `CryptoKitAesGcm`, `AppFileStorage` (complete file protection, excluded from backup), `HealthKitImporter`, `SourceTextExtractor` (PDFKit/Vision), `MedicationNotificationCoordinator`, `LicenseManager`/`LicenseView` (StoreKit), `StoreModelPackLoader`.

## Data flow

1. User picks a camera image, image, or PDF. Native code extracts embedded text, falling back to OCR.
2. Importer produces **unconfirmed** candidates (test, value, unit, reference range, date). Duplicates are fingerprinted.
3. User reviews and confirms. Only confirmed results appear in trends and doctor exports.
4. Records are serialized and encrypted by the platform `SecureVault`, scoped to a profile.
5. Export builds PDF/CSV from confirmed data and hands it to the OS share sheet. Backup wraps one profile in an AES-GCM envelope with the profile ID as associated data.

Records, backup, and export never depend on the licensing gate or on a model being installed.

## Local AI

`LocalHealthChat` builds a prompt from selected confirmed observations and calls a `LocalLlm` (Llamatik/llama.cpp, in process). `InstalledModelManager` installs a model pack, checks size and lowercase SHA-256, and tracks the loaded model. Packs come from Play Asset Delivery or Apple-hosted assets. The catalog is empty by default; no model URLs or hashes are invented. AI activation can require a store license; nothing else does.

## Tooling

- `tools/lab_importer/`: Python reference parser, golden tests against the synthetic `fixtures/lab_importer/`, and a fixture privacy scanner. Tests use synthetic data only.
- `tools/localization/`: string catalog parity and test-result checks.
- `tools/branding/`: sync from `brand/` into platform resources, with hash and contrast checks.

## Known limits

See the "Known limits" sections of [SECURITY.md](../SECURITY.md) and [CHANGELOG.md](../CHANGELOG.md). Notably, the iOS vault key is a protected file, not a Keychain item, and no cross-device recovery exists.

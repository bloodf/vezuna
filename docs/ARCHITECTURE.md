# Architecture

Vezuna is a native Kotlin Multiplatform app. It is not React Native or a web view. Shared logic and Compose UI live in `shared/`. Each platform has its own native shell for OS integrations.

```
shared/      Kotlin Multiplatform: domain, Compose UI, vault contract, models, exporters
composeApp/  Android application (applicationId app.vezuna)
iosApp/      Xcode project (bundle ID app.vezuna), SwiftUI shell + Kotlin bridge
tools/       Python checks: lab importer, localization, branding
fixtures/    Synthetic importer fixtures
brand/       Supplied brand kit (separate licensing, see THIRD_PARTY_NOTICES.md)
```

The Android application ID, Kotlin package root, and iOS bundle ID are `app.vezuna`. The shared Apple framework is `VezunaShared`; the app group is `group.app.vezuna`.

The pre-release identity changed on 2026-10-31. Builds using this ID install separately from builds using an earlier ID. Renaming source does not transfer another app's sandbox or Android Keystore keys. Existing installations are not uninstalled, and their data is not moved or deleted. The same-device encrypted backup format does not provide cross-app recovery. This applies to earlier tester builds such as `com.bloodf.vitahearth`: their data stays in that app, and `app.vezuna` starts empty. See [Moving from earlier tester builds](RELEASING.md#moving-from-earlier-tester-builds).

## shared/

Targets: Android library, JVM (tests and inspection fallback), `iosArm64`, `iosSimulatorArm64` (static framework `VezunaShared`, iOS 18.5 minimum).

| Package (`shared/src/commonMain/kotlin/app/vezuna/`) | Role |
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

SwiftUI shell around the Kotlin framework. `iosApp/SharedBridge/` is Kotlin compiled into the framework (injected by `iosApp/SharedBridge.gradle` through `scripts/build-ios.sh`). Swift pieces: `CryptoKitAesGcm`, `KeychainKeyStore` (vault master key, `AfterFirstUnlockThisDeviceOnly`; `KeyFileVaultCore` migrates the legacy key file (deleted only once data decrypts with the Keychain key; Keychain add is add-only) and returns `KEY_UNAVAILABLE`, never a new key, while the item is unreadable), `AppFileStorage` (complete protection for models/exports; vault files use `completeUntilFirstUserAuthentication` for lock-screen dose actions, data still AES-GCM encrypted; all excluded from backup), `HealthKitImporter`, `SourceTextExtractor` (PDFKit/Vision), `MedicationNotificationCoordinator`, `LicenseManager`/`LicenseView` (StoreKit), `StoreModelPackLoader`.

## Data flow

1. User picks a camera image, image, or PDF. Native code extracts embedded text, falling back to OCR.
2. Importer produces **unconfirmed** candidates (test, value, unit, reference range, date). Duplicates are fingerprinted.
3. User reviews and confirms. Only confirmed results appear in trends and doctor exports.
4. Records are serialized and encrypted by the platform `SecureVault`, scoped to a profile.
5. Export builds PDF/CSV from confirmed data and hands it to the OS share sheet. Backup is a passphrase-sealed file (see Storage and backup).

The laboratory-history PDF layout lives in `shared/.../export/PdfReportLayout.kt`; each platform supplies only a `PdfCanvas` (Android `PdfDocument`, iOS `UIGraphicsPDFRenderer`, JVM PDFBox for tests and samples). A4 or US Letter (default from the device region) and an optional grayscale mode are export options. The PDF is **not tagged**: neither mobile renderer can write a structure tree, so there are no PDF/UA tags, heading structure, table-header tags or chart alt text. Only the JVM renderer sets `/Lang`; iOS sets the title; Android sets no metadata. Text is selectable in reading order, and every chart is followed by a table of the exact values as its text alternative. The CSV export is the machine-readable alternative.

Records, backup, and export never depend on the licensing gate or on a model being installed.

## Storage and backup

`VaultBackedRepository` (`shared/.../data/`) is the one repository on both platforms. Vault keys: one encrypted file per profile (`repository-profile-<id>-v1`), an encrypted profile index written last (`repository-profile-index-v1`), per-profile vitals (`repository-vitals-<id>-v1`), and original source files (`source-document-<documentId>`, deleted with their document). A commit rewrites only the touched profile. A missing profile record is skipped on load; a corrupt one falls back to the vault's `.prev` generation and the damaged file is quarantined.

On-disk formats and migration:

- Files are `CipherEnvelopeCodec` binary envelopes (version byte 2). Version 1, the JSON envelope with int-array bytes, is still read; each file is rewritten in the new form the next time it is written.
- Plaintext documents carry `schemaVersion` (`StorageSchema.CURRENT`). Unversioned documents are version 1. Add a migration step in `StorageSchema.steps` for every bump. Data from a newer schema fails with `VaultFailure.SCHEMA_NEWER` and is never rewritten or quarantined.
- Older Android builds kept one `repository-v1` blob. On first load it is split into per-profile files, the index is written last, and the blob is deleted only after the new files load. An interrupted migration restarts from the untouched blob.

Backup payload `SCHEMA_VERSION` is 4 (versions 1-4 open). `BackupService.create` still writes the single-profile version 3 shape. `createHousehold` writes version 4: every profile with its vitals and original source files (Base64 inside the sealed payload). A new file must stay under `MAX_BACKUP_WRITE_BYTES` (24 MiB, because the payload is held in memory several times over); files up to `MAX_BACKUP_READ_BYTES` (64 MiB, the limit of released builds) still open; a household whose originals exceed roughly 18 MiB of raw files is refused with a clear message, and profiles are then backed up one at a time. Multi-photo documents keep page 1 in `vaultKey` and the rest in `pageVaultKeys`; a backup accepts up to 10 extra pages per document (11 in total, matching the 10-photo import screens) and refuses document IDs that end in `-page-<number>`, which would collide with page file IDs.

## Local AI

`LocalHealthChat` builds a prompt from selected confirmed observations and calls a `LocalLlm` (Llamatik/llama.cpp, in process). `InstalledModelManager` installs a model pack, checks size and lowercase SHA-256, and tracks the loaded model. Packs come from Play Asset Delivery or Apple-hosted assets. The catalog is empty by default; no model URLs or hashes are invented. AI activation can require a store license; nothing else does.

## Tooling

- `tools/lab_importer/`: Python reference parser, golden tests against the synthetic `fixtures/lab_importer/`, and a fixture privacy scanner. Tests use synthetic data only.
- `tools/localization/`: string catalog parity and test-result checks.
- `tools/branding/`: sync from `brand/` into platform resources, with hash and contrast checks.

## Known limits

See the "Known limits" sections of [SECURITY.md](../SECURITY.md) and [CHANGELOG.md](../CHANGELOG.md). Notably, the iOS vault key is a protected file, not a Keychain item, and no cross-device recovery exists.

# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). No release has been published or tagged yet.

## [Unreleased]

Current state of the working tree (`versionName` 1.0, `versionCode` 1):

### Added

- Kotlin Multiplatform shared module (`shared/`) with Compose Multiplatform UI, domain logic, encrypted-vault contract, model lifecycle, comparisons, and PDF/CSV export.
- Android app (`composeApp/`): encrypted persistence (Android Keystore AES-256-GCM), ML Kit and PDF text import, Health Connect read, medication reminders, Play LVL gate, Play Asset Delivery loader, native sharing.
- iOS app (`iosApp/`): SwiftUI shell, CryptoKit AES-GCM with file protection, HealthKit read, PDFKit/Vision import, reminders, StoreKit gate, hosted-asset loader, native sharing.
- Household profiles with per-profile data isolation, lab history, trends, and unconfirmed-OCR review with duplicate prevention.
- Local GGUF inference through Llamatik, with SHA-256 verification of model files.
- English, Portuguese, and Spanish localization.
- Vezuna brand kit integration (logo, icons, tokens, Noto Sans).
- Reference lab-text importer, synthetic fixtures, and localization/brand check scripts under `tools/`.
- Open-source documentation: CONTRIBUTING, SECURITY, PRIVACY, THIRD_PARTY_NOTICES, architecture and development guides, and the MIT `LICENSE` for source code (fonts, brand, and dependencies keep their own terms).

### Changed

- Android model and controller state now lives in an Activity ViewModel, surviving configuration changes (in-flight controller work keeps running through rotation) and unloading the native model when the session is cleared.
- Android OCR instrumentation test now uses only the synthetic image in `composeApp/src/androidTest/assets/` and a PDF generated at run time from fictional text. The PDF test checks page count and extracted text only; it does not assert whether text came from embedded PDF text or the OCR fallback. The test no longer reads private reports, and the `androidTest` asset sources for `samples/lab-reports` and `build/qa/ui` were removed from `composeApp/build.gradle.kts`.
- `tools/lab_importer/test_importer.py` is synthetic-only: the optional private-sample test class was removed, so no test reads `samples/`.

### Known limits

- Model catalog is empty until store assets are configured (`ModelCatalog.unconfigured()`).
- Store licensing, hosted-model download, HealthKit, Health Connect, camera, notification timing, OCR quality, and model performance are not validated on physical devices or through the stores.
- No iOS simulator runtime was available during development; the iOS app compiles but has not been launched.
- iOS minimum is 18.5 because the Skiko ICU object requires it; iOS 16.6–18.4 is unsupported.
- Cross-device backup recovery is not implemented.
- No independent security or accessibility audit.

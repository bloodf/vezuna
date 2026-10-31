# Security Policy

Vezuna stores personal health records on the device. Please report vulnerabilities privately.

## Reporting a vulnerability

1. Open the repository's **Security** tab and choose **Report a vulnerability** (GitHub private vulnerability reporting).
2. If that option is not available, open a public issue that says only that you have a security report and asks for a private channel. Do not put details, exploit steps, or health data in the issue.

Include: affected platform (Android/iOS), app version or commit, reproduction steps, impact, and a suggested fix if you have one. Use synthetic data only. Never attach real medical documents, screenshots of real records, vault files, or backups.

No response-time commitment or bug bounty exists. The project is maintained on a best-effort basis.

## Supported versions

Only the latest commit on the default branch is supported. No stable release has been published yet.

## Scope

In scope:

- Encrypted vault, key handling, and `BackupService` envelope (`shared/src/*/security/`, `iosApp/VitaHearth/CryptoKitAesGcm.swift`, `iosApp/VitaHearth/AppFileStorage.swift`).
- Source import (camera, image, PDF, OCR) and the lab importer parsing.
- Model file handling and SHA-256 verification (`shared/.../llm/ModelManager.kt`, `composeApp/.../llm/AndroidModelFileStore.kt`).
- Export and sharing (`FileProvider` in `composeApp/src/main/AndroidManifest.xml`, PDF/CSV exporters).
- Licensing gates (Play LVL, StoreKit) that must never gate records or exports.
- Unintended network access or data leaving the device.

Out of scope: physical access to an unlocked device, rooted/jailbroken devices, vulnerabilities in third-party dependencies that are already fixed upstream (report upstream, then tell us to bump the version), social engineering, and clinical accuracy of OCR output (report as a regular bug).

## Known limits (read before reporting)

These are documented design limits, not undisclosed vulnerabilities:

- **iOS key storage.** The vault master key is a random 32-byte file in the app's protected directory (`NSFileProtectionComplete`), not a Keychain item. Ciphertext and key sit in the same sandbox. Protection depends on iOS file protection and device lock.
- **Android key storage.** AES-256-GCM key in Android Keystore (`KeyGenParameterSpec`, not exportable by the app). StrongBox is not requested.
- **No cross-device recovery.** `BackupService` produces an authenticated envelope that only the same device key can open. There is no user-passphrase backup.
- **Debug builds.** `DEBUG_PREMIUM_BYPASS` is `true` in the debug build type and enables premium/AI features when no `VITAHEARTH_LVL_PUBLIC_KEY` is set. It is `false` in release. Do not distribute debug builds.
- **Release hardening.** `isMinifyEnabled` is `false` for Android release builds.
- **Vendored Play LVL code.** The Google Play Licensing library is vendored (Apache-2.0) and uses `AESObfuscator` with a fixed IV, as upstream does. Licensing is a deterrent, not strong DRM.
- **No audit.** The project has had no independent security audit or certification.
- **Local AI.** Output of the local model is unverified text, not medical advice. Model files are verified by SHA-256 against catalog metadata; the catalog itself must come from a trusted store channel.
- **Shared exports.** Exported PDF/CSV and backup files leave the app's encryption when the user shares them. They are staged in the app cache (`cache-path` entries in `file_paths.xml`).

## Handling sensitive data when contributing

Never commit real health data, signing keys (`*.keystore`, `*.jks`), the Play LVL key from a private build, store credentials, or model binaries. See [CONTRIBUTING.md](CONTRIBUTING.md#synthetic-data-only).

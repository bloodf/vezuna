# Privacy

This document describes what the Vezuna source code does with data, as of this repository's current state. It is a technical description, not a legal privacy policy. Anyone publishing a build must supply their own store privacy disclosures and verify them against the build they ship.

## Summary

- Health records are stored on the device, encrypted, and isolated per profile.
- The app contains no analytics, telemetry, advertising SDK, account system, or application server.
- The Android manifest removes the `INTERNET` permission (`tools:node="remove"`), so the Android app cannot open network connections itself.
- No health-data HTTP client exists in the app source (no `URLSession`, `HttpURLConnection`, or OkHttp usage found in app-owned code).
- AI inference runs locally via Llamatik (llama.cpp). Prompts and answers stay on the device.

## Data the app handles

| Data | Source | Where it goes |
|---|---|---|
| Profiles, lab results, OCR candidates, medications, dose events, chat messages | User input and imports | Encrypted vault on the device |
| Imported PDFs, photos, camera captures | User-selected or camera | Processed on-device (PDF text extraction, ML Kit / Vision OCR); camera captures staged in app cache |
| Heart rate, steps, weight | Health Connect (Android) / HealthKit (iOS), read-only, only after user permission | Encrypted on device |
| Doctor exports (PDF/CSV) and encrypted backups | Generated on demand | The destination the user picks in the system share sheet |
| Language and reminders | Device settings | Local notifications only |

## Permissions

Android (`composeApp/src/main/AndroidManifest.xml`): `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED` (reschedule reminders), `com.android.vending.CHECK_LICENSE` (Play licensing), and Health Connect read permissions for heart rate, steps, and weight. Camera is optional (`android.hardware.camera.any` not required).

iOS (`iosApp/VitaHearth/`): camera, photo library read for selected images, and HealthKit read (`NSHealthShareUsageDescription`). Health is read only when the user requests an import.

## Storage and protection

- Android: AES-256-GCM key in Android Keystore. `allowBackup="false"`, and `data_extraction_rules.xml` excludes all domains from cloud backup and device transfer.
- iOS: AES-GCM via CryptoKit. Vault and model files live under the app's Application Support directory (`VitaHearth/`) with `NSFileProtectionComplete`. `AppFileStorage` and `IosModelFileStore` also set the exclude-from-backup flag on directories and files they write through those helpers. `IosSecureVault` itself sets only file protection, so its backup exclusion rests on the parent `Vault` directory being prepared by `AppFileStorage`. The master key is a 32-byte protected file, not a Keychain item (no Keychain API use found). The entitlements declare app group `group.com.bloodf.vitahearth`; no source use of the shared group container was found. None of this has been independently audited.
- Backups created in the app are encrypted for the same device only. See [SECURITY.md](SECURITY.md#known-limits-read-before-reporting).

Encryption does not protect data once the user exports or shares it. Shared PDFs and CSVs are plaintext.

## Network and third parties

The app itself does not send health data anywhere. Platform and store services still run on the device and have their own privacy terms:

- Google Play (license check, Play Asset Delivery for model packs) and Apple App Store (StoreKit `AppTransaction`, Apple-hosted assets). These handle purchase verification and model-pack delivery. They are not given health records by app code.
- Google ML Kit text recognition is used for Android OCR. Its Latin model is bundled with the dependency; Google's ML Kit terms apply.
- Health Connect, HealthKit, and the system share sheet.

Because the Android build has no `INTERNET` permission, store-side delivery happens through the Play Store app, not through the app's own connection. The iOS build has no equivalent manifest guarantee: its network behavior is limited by code review only, and no iOS network audit has been done.

## Medical content

Vezuna organizes records the user provides. It does not diagnose, recommend treatment, or change doses. OCR output stays an unconfirmed draft until the user confirms it. Local AI answers are generated text and can be wrong.

## Deleting data

Removing the app deletes its sandbox, including the vault and downloaded models. Files the user exported or shared elsewhere are not touched.

## Contact

Use GitHub issues for privacy questions and GitHub private vulnerability reporting for security issues. See [SECURITY.md](SECURITY.md). No privacy email address is provided.

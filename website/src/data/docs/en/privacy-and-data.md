---
title: Privacy and data
description: Understand local storage, optional permissions, platform services, sharing boundaries, backups, and deletion.
lang: en
order: 4
audience: user
translationKey: privacy-and-data
essential: false
---

## On-device storage

Profiles, laboratory results, OCR candidates, medications, dose events, and local chat messages are stored in an encrypted, profile-isolated vault on the device.

- **Android:** AES-256-GCM with a non-exportable key in Android Keystore. StrongBox is not requested. Cloud backup and device transfer are disabled for app data.
- **iOS:** AES-GCM through CryptoKit and `NSFileProtectionComplete`. The master key is a protected 32-byte file, **not a Keychain item**; the key and ciphertext reside in the same app sandbox.

No independent security audit or certification has been completed. Device lock and platform protection remain important.

## Network boundaries

App-owned code contains no account system, advertising SDK, analytics, telemetry, application server, or health-data HTTP client. Android removes the `INTERNET` permission from the merged application manifest.

This is not a final “no data collected” declaration. Third-party and platform services—including Google Play, the App Store, ML Kit, Health Connect, HealthKit, and the system share sheet—have their own terms and behavior. The iOS build has no manifest-level equivalent to Android’s Internet-permission removal; its network behavior has been reviewed in source, but no iOS network audit has been performed.

Store services may handle purchase verification and planned model-pack delivery. App code does not provide health records to those services. SDK privacy behavior and final store disclosures must be assessed against the exact release build.

## Permissions

### Android

The app-owned manifest includes notifications, reboot handling for reminder rescheduling, Play licensing, and read-only Health Connect access to heart rate, steps, and weight. Camera hardware is optional.

The inspected merged release manifest also includes SDK-added foreground-service/data-sync, wake-lock, network-state, and signature-protected dynamic-receiver permissions. Store declarations must reflect the actual merged release manifest.

### iOS

Camera, selected photo-library images, and read-only HealthKit access support their respective workflows. Health data is read only when you request an import.

Denying health permissions does not prevent manual recordkeeping. You can keep entering and importing records without connecting Health Connect or HealthKit.

## App lock

App lock is optional. When it is on, Vezuna asks for biometrics or the device passcode when it opens and after five minutes in the background, and it blocks screenshots and app-switcher previews. It needs a screen lock on the device: if the screen lock is removed, app lock turns itself off and says so.

## Backups and app-identity changes

New backups are portable files protected by a passphrase you choose (at least 12 characters). The passphrase is stretched with PBKDF2-HMAC-SHA256 (600,000 iterations or more) and the profile is sealed with AES-256-GCM, so the file can be restored on another phone or after reinstalling. Vezuna cannot recover a lost passphrase: without it, the backup can never be opened.

Backups made by earlier builds use the older same-device envelope. They open only with the **same device key** on the phone that created them, and the app labels them as same-device backups.

Where you save a backup file is your choice, and it then lives outside the app’s sandbox. A backup protects against device loss only if you saved it somewhere else and still know its passphrase.

Changing the application or bundle ID does not transfer the previous app’s sandbox or Android Keystore keys. A different ID creates a separate installation and does not migrate encrypted data.

## Deleting data

Removing the app deletes its sandbox, including the encrypted vault and downloaded models. PDFs, CSVs, backups, or other files already exported or shared elsewhere are not deleted. Shared PDFs and CSVs are plaintext.

## Website privacy

The marketing site and documentation do not provide an account, upload form, newsletter, analytics, or medical-record service. Do not submit health records through the website or public issues. Hosting and delivery are provided by Vercel, whose infrastructure may process ordinary connection data such as IP address and request logs under its own terms.

The repository’s technical privacy document is not a final publisher legal policy. See the current [privacy disclosure](/privacy) for that distinction and the missing publisher facts.

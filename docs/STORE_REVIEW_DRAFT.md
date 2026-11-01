# Store review draft

These are source-backed drafts, not submitted declarations. A signed release build, store processing, and physical-device tests are still required.

Owner-confirmed details on 2026-10-31: public support/privacy email `heitor.ramon@gmail.com`; intended price **US$9.99**; website `https://vezuna.app`, with documentation live at `/docs`. Publisher name Heitor Ribeiro was confirmed on November 1, 2026. The publisher approved docs/PUBLISHER_PRIVACY_POLICY.txt on November 1, 2026. Its exact copy is live at https://vezuna.app/privacy and saved in all three Apple policy URL localizations and Play Console. Play confirms the policy task complete. Google Play now has the approved USD 9.99 price saved, with generated regional prices. Apple USD 9.99 pricing is saved, with the United States as reference territory and automatically generated regional prices. Apple team `AAMZ7ZVM62` is confirmed, and `app.vezuna` is registered with HealthKit and its dedicated App Group.

## Listing copy

Name: Vezuna. Apple English (US): Vezuna: Health Records, approved by the owner after Apple rejected the shorter name as unavailable.

English subtitle: Health records on your device

English short description: Organize lab reports, medications, and household health records on your device.

English description:

Vezuna organizes health records for your household on your Android device or iPhone. Keep separate profiles, import laboratory reports from images or PDFs, and review extracted values before confirming them.

Compare confirmed laboratory results over time. Record medications and schedules, use local reminders, and mark doses as taken, skipped, or snoozed. Create PDF or CSV exports when you want to share information with a clinician.

With your permission, Vezuna can import heart rate, steps, and weight from Health Connect on Android or HealthKit on iPhone. App-owned code stores records in an encrypted local vault. Exports shared with other apps leave that vault's protection.

Vezuna organizes information you provide. It does not diagnose conditions, recommend treatment, or change medication doses. Check imported values against the original report. Backup restoration currently works only with the same device key. iPhone requires iOS 18.5 or later.

Do not add an AI or downloadable-model promise until a production model catalog, store delivery, licensing, and inference pass real device tests. The current catalogs are empty.

## Declaration evidence

| Topic | Implementation evidence | Required decision or verification |
| --- | --- | --- |
| Accounts and ads | No app-owned account, advertising, or analytics implementation was found. | Review the complete resolved SDK set and release runtime behavior before selecting final privacy answers. |
| Health access | Android manifest and `HealthConnectGateway.kt` request read-only heart rate, steps, and weight. `HealthKitImporter.swift` requests the corresponding HealthKit read types. | Explain each permission in the Health apps declaration. No write, background-health-read, or historical-access permission is requested by the Android manifest. Confirm the exact questionnaire against the release build. |
| Health permission policy | Android includes PermissionsRationaleActivity and its permission-protected Android 14 alias. Both display the bundled publisher policy. Three navigation/intent tests pass on a 16 KB emulator. | The approved generated policy is live at https://vezuna.app/privacy. Play Console confirms the policy URL was saved and the task is complete. See [Google's setup requirements](https://developer.android.com/health-and-fitness/health-connect/get-started#show-privacy-policy). |
| Data safety | The inspected merged debug APK has no Internet permission. ML Kit is present, along with Play delivery and licensing dependencies. | Google's [ML Kit disclosure page](https://developers.google.com/ml-kit/android-data-disclosure) describes diagnostic collection. Verify the exact versions, configuration, and release behavior. Do not automatically select "no data collected." |
| SDK-added Android permissions | The merged release manifest also declares foreground-service/data-sync, wake-lock, network-state, and signature-protected dynamic-receiver permissions. | Inspect the actual merged services and complete any required foreground-service declarations from that implementation. The source manifest alone omits these SDK additions. |
| Apple privacy | Health records stay local in app-owned code. Users can export through the system share sheet. | Review linked native SDKs and actual device archive. Apple's definition of collection concerns off-device transmission and includes third-party code. See [App privacy details](https://developer.apple.com/app-store/app-privacy-details/). |
| Encryption | `CryptoKitAesGcm.swift` uses Apple CryptoKit AES-GCM. Android uses Android Keystore AES-GCM. Llamatik links native dependencies. | The publisher must classify the complete distributed app. Do not set `ITSAppUsesNonExemptEncryption` or certify US export compliance by assumption. See [Apple export compliance](https://developer.apple.com/help/app-store-connect/manage-app-information/overview-of-export-compliance). |
| Rating and medical content | Records, medication tracking, OCR, and health import are implemented. No app-owned social feed, advertising, or user account system was found. | Answer the live rating questionnaire, including medical information and any configured AI feature. Do not infer an age rating from the absence of social features. |
| Price and ownership | README describes paid store distribution. Source and original artwork are MIT. Fonts and dependencies retain their licenses. | Publisher name, price, territories, paid-app agreements, tax status, public contact, and name clearance remain owner decisions. |

## Reviewer notes

No app-owned login is required. Use fictional records only. Create a household profile, import a synthetic laboratory image or PDF, inspect the unconfirmed OCR draft, and confirm the values manually. Compare confirmed results, create a medication schedule, test dose actions, and export a PDF and a CSV through the system share sheet.

Grant health permissions only on a test device with synthetic health data. Denied permissions must leave manual records usable. Record actual notification arrival over elapsed time, including reboot and timezone changes.

The production model catalog is not configured. Reviewer notes must disclose the final model and licensing behavior after those features are configured and tested. Records and exports must stay usable when AI licensing fails. Play's paid-app form can add installer protection that needs a separate review against this requirement.

For iOS, the minimum supported version is 18.5. The successful generic simulator compilation does not establish iPhone runtime behavior.

## Missing submission material

The prepared publisher privacy policy and support contact are implemented locally in the website and native apps. Policy publication is complete. Territories, final declarations, and release screenshots remain pending. Portuguese, English and Spanish Play short/full descriptions, the 512px icon, and the 1024×500 feature graphic are saved as drafts and verified after reload. Play still requires final release screenshots. Portuguese, English and Spanish iOS names, subtitles, descriptions, keywords, promotional text, support/marketing URLs, policy URLs, and copyright are saved and verified by the Apple API. The owner saved the private reviewer phone; its presence and the draft notes were verified without copying the phone into project files. Play public email and website are saved. Google Play price USD 9.99 is saved; generated local prices do not select distribution territories.

Use original assets from `brand/` for the listing icon. Capture phone screenshots from the actual release builds with synthetic data. Existing test screenshots and a working launcher icon do not establish compliant store screenshot dimensions or an accurate release listing. Never use private medical records.

## Device and processing evidence

| Required outcome | Status on 2026-10-31 |
| --- | --- |
| Signed Play AAB accepted by processing | Signed local AAB/APK 1.0 (1) pass signature, identity, actual DEX, bundle, and 16 KB packaging checks. Signed release APK cold launch and home UI pass on an isolated API35 ARM64 emulator with 16 KB pages, with no fatal/native-loading startup error. No upload or processing acceptance. Full runtime and physical-device checks remain pending. |
| App Store distribution IPA accepted by processing | Native archive and App Store IPA 1.0 (1) export succeeded. Exported IPA passes strict distribution signature, intended team, matching App Store profile/certificate/entitlements, arm64, iOS minimum 18.5 and SDK 27.1 checks. No upload or processing acceptance. The replacement November 1 IPA includes the offline policy, notices, app tracking declaration, and separate Compose SDK file-timestamp manifest. Apple aggregation/processing, full privacy review, production model delivery, and physical-device runtime remain pending. The Paid Apps agreement is now verified Active through January 28, 2027; banking/tax warnings remain owner actions. |
| Play internal testing and TestFlight | No upload performed. Explicit owner approval is required before uploads. |
| Storage and upgrades, OCR, health permissions, notifications, exports, backups | Physical-device release checks are pending. Emulator and unit tests cannot satisfy this row. |
| Store licensing, hosted model delivery, offline inference | Store and physical-device checks are pending. Local inference fixture verification does not configure store delivery. |

Localized listing drafts are maintained in [docs/store/metadata.json](store/metadata.json). All three language drafts pass the platform text-length checks. All three iOS localizations are saved and match the source through API verification. The approved Developer API key can verify metadata but its app-info modification request returned HTTP 403. Metadata was saved through the owner’s browser without widening key access. No App Privacy data-collection answers were published.

# Releasing Vezuna

Vezuna is native: Kotlin Multiplatform (Android app in `composeApp/`, shared code in `shared/`) and an Xcode iOS app (`iosApp/`). It is not React Native or Expo. Expo is used only for **EAS Submit**, which uploads binaries you built yourself. Nothing in this repository uploads anything automatically.

Never commit keystores, `.p8` keys, service-account JSON, passwords, `samples/` or `models/`.

Before submitting any build, complete the [physical-device validation](DEVICE_VALIDATION.md) checklist for the release candidate and attach the results table.

## Versions

| Platform | Name | Build number | Set with |
| --- | --- | --- | --- |
| Android | `-PVEZUNA_VERSION_NAME=1.0` | `-PVEZUNA_VERSION_CODE=2` (integer, must increase every upload) | Gradle properties |
| iOS | `VEZUNA_VERSION_NAME=1.0` | `VEZUNA_BUILD_NUMBER=2` (must increase per version) | env for `scripts/build-ios.sh` |

Defaults are `1.0` / `1`. `eas.json` uses `appVersionSource: local`.

## Android: signed release

Create a new upload key in your own terminal, or set `VEZUNA_UPLOAD_STORE_FILE` and `VEZUNA_UPLOAD_KEY_ALIAS` to an existing key. Never replace a registered upload key without following Play's key-reset process.

```sh
bash scripts/build-android.sh create-upload-key
```

The default path is `~/.local/share/vezuna/release/vezuna-upload.jks`. The script asks for a password twice and refuses to overwrite a file. Back up the key and password securely. Enable Play App Signing in Play Console before the first release.

Build the signed AAB and APK in your terminal:

```sh
export VEZUNA_LVL_PUBLIC_KEY='<Play licensing public key>'
VEZUNA_VERSION_CODE=1 bash scripts/build-android.sh
```

The script asks for signing passwords without echoing them. Passwords go to child processes through environment variables, never command arguments or repository files. It disables Gradle's configuration cache and daemon for the signing build. The Play licensing key is read from a Gradle property or the same-named environment variable. An empty key disables paid AI access in release builds.

If the environment variable is unset, the script also reads `~/.local/share/vezuna/release/play-licensing-public-key.txt` when present. This local file was populated from Vezuna's Play Console on 2026-10-31. Keep it outside Git.

For automation, supply all four `VEZUNA_UPLOAD_STORE_FILE`, `VEZUNA_UPLOAD_STORE_PASSWORD`, `VEZUNA_UPLOAD_KEY_ALIAS`, and `VEZUNA_UPLOAD_KEY_PASSWORD` values through the local secret store or CI secret environment. Do not place passwords directly in shell commands. `VEZUNA_VERSION_NAME` defaults to `1.0`; `VEZUNA_VERSION_CODE` defaults to `1` and must increase after the first accepted upload.

Outputs: `composeApp/build/outputs/bundle/release/composeApp-release.aab`, `composeApp/build/outputs/apk/release/composeApp-release.apk`.

If any of the four variables is missing, `bundleRelease`/`packageRelease` fail fast via `verifyReleaseSigning`; the build never silently produces an unsigned or debug-signed release. Debug builds are unaffected.

Verify signature: `apksigner verify --print-certs composeApp/build/outputs/apk/release/composeApp-release.apk`.

The AAB uses JAR signing. Check it with `jarsigner -verify -verbose -certs composeApp/build/outputs/bundle/release/composeApp-release.aab` and compare its signer certificate with the upload certificate registered in Play Console. APK signature verification does not validate an AAB.

Run the native-library check on the actual release bundle:

```sh
python3 tools/release/test_android_native.py
python3 tools/release/check_android_native.py composeApp/build/outputs/bundle/release/composeApp-release.aab
```

The checker requires 64-bit native libraries and checks every ELF `PT_LOAD` segment for 16 KB alignment. It reads APK, AAB, and AAR archives without extraction. Its success marker is `ANDROID_ELF_16KB_PASSED`. It does **not** validate signatures, bundle ZIP configuration, generated APK ZIP alignment, or runtime behavior. Use bundletool to inspect `PAGE_ALIGNMENT_16K`, generate device APKs from the signed AAB, and check those APKs with the installed build tools' `zipalign -c -P 16 -v 4`. Test on a 16 KB emulator or device as well.

As checked on 2026-10-31, [Google's target API requirement](https://developer.android.com/google/play/requirements/target-sdk) is API 36 for new phone apps and updates. This project targets 37. [Google's current page-size guidance](https://developer.android.com/guide/practices/page-sizes) requires 16 KB support and states that incompatible updates are blocked starting February 1, 2027. Check the current guidance and the app's own Play Console notices before each upload.

## iOS

### Deployment target: iOS 18.5

Every Xcode configuration and the `shared/build.gradle.kts` link flags use iOS 18.5. The Skiko ICU object (`libicu.icudtl_dat.o`, inside `org.jetbrains.skiko:skiko-iosarm64` / `-iossimulatorarm64` 0.150.2, pulled in by Compose Multiplatform) declares `minos 18.5` (checked with `xcrun vtool -show-build`), so a lower target produced the linker warning "built for newer 'iOS-simulator' version (18.5) than being linked (16.6)". The minimum was raised to 18.5 deliberately; iOS 16.6–18.4 is not supported, and the store listing must say so.

To support older iOS again, move to a Compose Multiplatform/Skiko release whose ICU object targets a lower minimum, then lower the Xcode and `shared/` values together.

### Simulator build (unchanged workflow)

```sh
bash scripts/build-ios.sh                       # Debug
CONFIGURATION=Release bash scripts/build-ios.sh
```

Prints `IOS_BUILD_PASSED`. Frameworks are stored per SDK: `iosApp/Frameworks/<Configuration>/iphonesimulator/` and `.../iphoneos/`, so simulator and device builds do not overwrite each other.

### Physical-device archive and IPA

Requires: paid Apple Developer Program membership, Xcode signed in to that account (or a distribution certificate/profile installed), the App ID `app.vezuna`, and your 10-character Team ID. The current entitlements request **HealthKit** and **App Groups** (`group.app.vezuna`). Inspect the signed entitlements and provisioning profile before enabling any further capability. Background Assets framework linkage and build settings alone do not establish a configured hosted-delivery capability.

```sh
export DEVELOPMENT_TEAM=AAMZ7ZVM62                       # verified Vezuna team; forks use their own
VEZUNA_BUILD_NUMBER=2 bash scripts/build-ios.sh device-archive
EXPORT_METHOD=app-store-connect bash scripts/build-ios.sh device-export   # also archives
```

`EXPORT_METHOD` is one of `app-store-connect`, `release-testing`, `debugging`, `enterprise`. Output: `build/ios/Vezuna.xcarchive` and `build/ios/export/*.ipa` (override directory with `OUT_DIR`). Signing is automatic (`-allowProvisioningUpdates`).

As checked on 2026-10-31, [Apple requires the iOS 26 SDK or later](https://developer.apple.com/news/?id=ueeok6yw) for uploads. This SDK requirement is separate from the approved minimum deployment target of 18.5. A simulator build is not an App Store archive or a distribution IPA.

`iosApp/Vezuna-Info.plist` supplies the hosted-asset flags, `UIAppFonts`, and `CADisableMinimumFrameDurationOnPhone=true`. Compose requires the frame-rate setting; omitting it caused a reproduced startup exception. The build script runs `tools/release/check_ios_metadata.py` against the actual simulator app or device archive before printing its success marker. The checker verifies these settings, the identifier, deployment target, SDK floor, permission descriptions, declared fonts, and Compose font/translation lookup paths. It does not validate signing or store delivery.

Compose resources stage into `iosApp/.ios-build/compose-resources/`. Xcode copies that entire folder, preserving the runtime's `compose-resources/composeResources/...` lookup path. Keep the Gradle sync output separate from `.ios-build/derived-data/`: the sync task deletes unrelated files inside its output directory. A forced sync preserved every file of the rebuilt simulator app after this separation. The corrected simulator app reaches Home; physical-device validation remains pending.

Before export, inspect the archive's privacy manifests, signature, entitlements, and provisioning profile. The November 1 IPA includes the app tracking declaration and a separate Compose SDK manifest using the vendor-recommended file-timestamp reason. Actual `stat` and `fstat` call sites are mapped to Skia and ICU wrappers using the matching dSYM. See [manifest evidence and limits](IOS_PRIVACY_MANIFEST.md). Apple aggregation/processing and full runtime privacy review remain pending. Hosted assets and the iOS 18.5 ODR fallback are not validated by compilation.

## EAS Submit

Before creating a release, refresh the license evidence and inspect changes:

```bash
./gradlew --no-configuration-cache --init-script tools/release/license_inventory.gradle :composeApp:writeVezunaLicenseInventory :shared:writeVezunaLicenseInventory
python3 tools/release/inventory_runtime_notices.py build/reports/licenses/android-artifacts.json --platform android --check
python3 tools/release/inventory_runtime_notices.py build/reports/licenses/ios-artifacts.json --platform ios --check
python3 tools/release/build_notices.py --check
```

The inventory commands resolve artifacts without compiling app code. A changed inventory requires review before refreshing its committed snapshot and the hashes in `third_party/notices-manifest.json`. Run `build_notices.py` without `--check` to regenerate the platform files after that review. The release scripts repeat these checks and verify the actual bundled text. They do not establish legal acceptance or approve uploads.

`eas.json` defines the `production` submit profile. The CLI expands `$VAR` in `serviceAccountKeyPath`, `ascApiKeyPath`, `ascApiKeyId`, and `ascApiKeyIssuerId`. `package.json` pins `eas-cli` 27.2.0. EAS requires Node 20+ and a linked project even for external binaries. This repository is linked to [@bloodf/vezuna](https://expo.dev/accounts/bloodf/projects/vezuna), project ID `ed7ac985-98fd-4cb7-813a-ad16b25e98ba`, through `app.json`. This metadata does not change the native architecture. Contributors must use their own Expo project for independent distribution.

```sh
npm install   # package-lock.json is committed; CI may use npm ci
npx eas login
```

`eas submit` only uploads an already built and signed binary; it never builds or signs.

### Google Play

Prerequisites: Play Console developer account; app created with package `app.vezuna`; a Google service account with release permissions, its JSON key downloaded (guide: https://expo.fyi/creating-google-service-account). [Expo's current Android guide](https://docs.expo.dev/submit/android/#first-time-submission), checked on 2026-10-31, supports the first release through EAS Submit after these prerequisites are complete. Use Play Console manually if the actual API response requires it.

```sh
export GOOGLE_SERVICE_ACCOUNT_KEY_PATH=$HOME/play-service-account.json
npm run submit:android -- composeApp/build/outputs/bundle/release/composeApp-release.aab
```

The profile targets the `internal` track with `releaseStatus: draft`; change in `eas.json` when promoting. Do not run the upload command until distribution checks pass and the owner explicitly approves the upload.

The owner's configured service account is `vezuna-eas-submit@vezuna-release-476913.iam.gserviceaccount.com`. It has no Cloud IAM roles and only Vezuna app-information/quality read access and testing-release permissions in Play. Play access expires November 30, 2026. The JSON key is stored outside Git with mode `0600`; `~/.local/share/vezuna/release/eas-submit.env` exports its path. Source that private file before a later approved upload. Google OAuth authentication and the pinned EAS profile resolver have passed; neither check uploaded a binary.

The paid-app creation form can enable automatic installer protection. Review its effect on access to existing records and exports, which Vezuna's own licensing must never gate. Do not change that protection or certify export-law compliance without the account owner's decision.

Health Connect privacy-policy intent handlers are implemented for Android 13 and Android 14 onward. Both open the offline publisher policy, also available from the Device menu. Three targeted tests pass on a 16 KB emulator. The publisher-approved generated website copy is live at https://vezuna.app/privacy. Use that same policy URL in Play Console before release, following [Google's Health Connect setup guide](https://developer.android.com/health-and-fitness/health-connect/get-started#show-privacy-policy). The canonical publisher text is `docs/PUBLISHER_PRIVACY_POLICY.txt`; the technical `PRIVACY.md` is separate. Run `python3 tools/release/sync_privacy_policy.py` after editing the canonical policy. CI and release scripts require `--check`, and both release scripts compare the actual bundled policy with the source. iOS exposes the offline policy in its native menu too. Publisher approval and exact live-copy verification were completed on November 1, 2026.

### App Store Connect / TestFlight

Prerequisites: app record for bundle ID `app.vezuna` in App Store Connect; an App Store Connect API key (`.p8`, issuer ID, key ID; guide: https://expo.fyi/creating-asc-api-key); an `.ipa` exported with `EXPORT_METHOD=app-store-connect`.

```sh
export ASC_API_KEY_PATH=$HOME/AuthKey_XXXXXXXXXX.p8
export ASC_API_KEY_ISSUER_ID=...
export ASC_API_KEY_ID=XXXXXXXXXX
npm run submit:ios -- build/ios/export/Vezuna.ipa
```

The verified App Store Connect app ID `6829336593`, Apple team `AAMZ7ZVM62`, and primary language `pt-BR` are configured in `submit.production.ios`. These are public identifiers, not signing credentials. Contributors distributing an independent app must replace them with their own app and team.

Apple's Developer role permits build uploads. [Team API keys cover all apps](https://developer.apple.com/help/app-store-connect/get-started/app-store-connect-api) and do not support app-only restrictions. An individual key inherits its user's access, so app-only isolation requires a user restricted to Vezuna. The owner approved this scope for the `Vezuna EAS Submit` Developer key. Its `.p8` is secured outside Git with mode `0600`; the private `eas-submit.env` exports its path, key ID and issuer ID. ES256 authentication, a read of the intended Vezuna app record, and the pinned EAS iOS profile resolver have passed.

## Local release state on November 1, 2026

- The owner created the Android upload keystore outside Git. The actual Play licensing public key is configured locally. Signed AAB/APK candidates pass signature, identity, bundle and 16 KB packaging checks. The signed APK also passes a startup smoke check on a 16 KB emulator.
- Apple team `AAMZ7ZVM62`, App ID `app.vezuna`, HealthKit, dedicated App Group, and App Store Connect record `6829336593` are configured. Replacement App Store IPA `vezuna-1.0-1-56e5de0f6f12.ipa` includes the startup fixes and passes distribution signature, provisioning, certificate, entitlement, architecture, Compose resource and bundle metadata checks. Sealed candidates and validation reports are under the ignored `build/release-candidates/` directory. The earlier IPA is superseded.
- Google submission credentials are configured and validated as described above. The owner-approved Apple Developer team key is also secured and validated. The local Expo session was verified as `bloodf` Owner; recheck before submission.
- Apple's Paid Apps agreement is verified Active through January 28, 2027. Banking/tax warnings remain owner actions. The current exported IPA includes both app and SDK privacy manifests; Apple aggregation/processing remains unverified. Collected notices are bundled and checked; remaining native source mapping and SDK terms need review. Required-reason API review, production model catalogs and hosted packs, publisher policy/declarations, and physical-device checks remain open. The owner reports no physical devices available now. The verified Qwen fixture is not part of either app. See [third-party notices](../THIRD_PARTY_NOTICES.md) and the [store review draft](STORE_REVIEW_DRAFT.md).
- No binary has been uploaded. Distribution readiness and explicit owner approval are required before Play internal testing or TestFlight uploads. Store processing, physical-device testing, review submission and approval are separate outcomes.

The corrected Debug app reaches Home on an isolated iOS 27.1 simulator. Native XCTest attempts on iOS 27.1 and 27.0 produced no executed-test result: Xcode logged SDK build-metadata parsing errors and stalled connecting to simulator Instruments services. These tests remain unverified. The attempts were stopped; no application test failure was inferred from that runner stall.

### November 1 privacy and pricing update

Publisher Heitor Ribeiro and public email heitor.ramon@gmail.com are confirmed. The canonical publisher policy and website/native readers are prepared. Android menu, legacy Health Connect rationale, and permission-protected Android 14 alias pass three emulator tests. The actual Debug APK and iOS simulator app contain exactly the canonical policy bytes. Website type checks, build, three existing content tests, and the rendered-policy comparison pass. Old signed Android candidates correctly fail the new policy check. Android still needs a later signed rebuild. The replacement signed iOS candidate includes policy readers and manifests; future model or SDK changes require another build.

Google Play price USD 9.99 is saved and the console confirms the change. Converted regional prices are generated; this does not select availability territories. Apple price USD 9.99 is also saved, using the United States as reference. The owner-approved policy was deployed to the existing Vercel project and its public rendered page and downloadable text match the canonical source exactly. The policy URL is saved in all three Apple draft localizations and Play Console; Play marks its policy task complete.

The current sealed iOS candidate is `build/release-candidates/ios/vezuna-1.0-1-56e5de0f6f12.ipa`. Its strict signature/profile/certificate/entitlement, metadata/resource, notices, policy, and manifest packaging checks pass. Its matching dSYM maps the current `stat` and `fstat` call sites to the same Skia and ICU wrappers. This does not establish store acceptance or physical-device behavior.

### November 1 policy publication and Apple listing update

The approved policy SHA-256 is `048efe03b16e8657b85ded9aa667fcfd160471e5d5f45ff638c5f34e9f81717f`. It is live at https://vezuna.app/privacy, with the same text at https://vezuna.app/privacy-policy.txt. Public support and docs routes also return HTTP 200. Deployment used a separate staging directory containing only website source, linked to the existing `hr-teconologia/vezuna` project. No native application data or release credentials were uploaded.

Apple’s USD 9.99 price and Portuguese, English and Spanish listing metadata are saved. The version remains Prepare for Submission. Names, subtitles, policy URLs, descriptions, keywords, promotional text, support/marketing URLs and copyright match `docs/store/metadata.json` through official API verification. The English app name is “Vezuna: Health Records”, approved by the owner after “Vezuna” was unavailable. The owner saved the private reviewer phone; only its presence is recorded in the validation report. The approved Developer key can verify metadata but its app-info edit returned 403, so edits used the owner browser without widening key access. Play confirms its policy task complete, and public email/website are saved. Extension input remained intermittent; native Chrome accessibility completed the Apple metadata. No App Privacy collection declaration, binary, or review submission was published.

Play’s Portuguese, English and Spanish text drafts and existing brand icon/feature graphic are saved. Each description was verified after a page reload. Final release screenshots are missing, so the listing is not complete or submitted. Android-specific descriptions in `docs/store/metadata.json` omit iPhone requirements.

Play Medical category and no-advertising answer are saved as pending review changes. The category matches the health-record/medication organizer; it does not determine regulated medical-device status. The no-ads answer matches the app source and current declared SDKs. Other privacy/health/rating/export decisions remain open.

# Releasing Vezuna

Vezuna is native: Kotlin Multiplatform (Android app in `composeApp/`, shared code in `shared/`) and an Xcode iOS app (`iosApp/`). It is not React Native or Expo. Expo is used only for **EAS Submit**, which uploads binaries you built yourself. Nothing in this repository uploads anything automatically.

Never commit keystores, `.p8` keys, service-account JSON, passwords, `samples/` or `models/`.

## Versions

| Platform | Name | Build number | Set with |
| --- | --- | --- | --- |
| Android | `-PVEZUNA_VERSION_NAME=1.0` | `-PVEZUNA_VERSION_CODE=2` (integer, must increase every upload) | Gradle properties |
| iOS | `VEZUNA_VERSION_NAME=1.0` | `VEZUNA_BUILD_NUMBER=2` (must increase per version) | env for `scripts/build-ios.sh` |

Defaults are `1.0` / `1`. `eas.json` uses `appVersionSource: local`.

## Android: signed release

One-time: create an upload keystore outside the repo.

```sh
keytool -genkeypair -v -keystore ~/vezuna-upload.jks -alias vezuna-upload \
  -keyalg RSA -keysize 4096 -validity 10000
```

Back it up. In Play Console enable Play App Signing (the key above is then the upload key).

Build (values come from Gradle properties or same-named environment variables):

```sh
export VEZUNA_UPLOAD_STORE_FILE=$HOME/vezuna-upload.jks
export VEZUNA_UPLOAD_STORE_PASSWORD=...
export VEZUNA_UPLOAD_KEY_ALIAS=vezuna-upload
export VEZUNA_UPLOAD_KEY_PASSWORD=...
./gradlew :composeApp:bundleRelease -PVEZUNA_VERSION_CODE=2   # Play AAB
./gradlew :composeApp:assembleRelease                         # signed APK
```

Outputs: `composeApp/build/outputs/bundle/release/composeApp-release.aab`, `composeApp/build/outputs/apk/release/composeApp-release.apk`.

If any of the four variables is missing, `bundleRelease`/`packageRelease` fail fast via `verifyReleaseSigning`; the build never silently produces an unsigned or debug-signed release. Debug builds are unaffected.

Verify signature: `apksigner verify --print-certs composeApp/build/outputs/apk/release/composeApp-release.apk`.

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

Requires: paid Apple Developer Program membership, Xcode signed in to that account (or a distribution certificate/profile installed), the App ID `com.bloodf.vitahearth` with the **HealthKit**, **App Groups** (`group.com.bloodf.vitahearth`) and Background Assets capabilities enabled (the entitlements file uses HealthKit and the app group), and your 10-character Team ID.

```sh
export DEVELOPMENT_TEAM=ABCDE12345                       # your Team ID
VEZUNA_BUILD_NUMBER=2 bash scripts/build-ios.sh device-archive
EXPORT_METHOD=app-store-connect bash scripts/build-ios.sh device-export   # also archives
```

`EXPORT_METHOD` is one of `app-store-connect`, `release-testing`, `debugging`, `enterprise`. Output: `build/ios/Vezuna.xcarchive` and `build/ios/export/*.ipa` (override directory with `OUT_DIR`). Signing is automatic (`-allowProvisioningUpdates`).

## EAS Submit

`eas.json` defines the `production` submit profile. The CLI expands `$VAR` in `serviceAccountKeyPath`, `ascApiKeyPath`, `ascApiKeyId`, and `ascApiKeyIssuerId`. `package.json` pins `eas-cli` 27.2.0. EAS requires Node 20+ and a linked project even for external binaries. This repository is linked to [@bloodf/vezuna](https://expo.dev/accounts/bloodf/projects/vezuna), project ID `ed7ac985-98fd-4cb7-813a-ad16b25e98ba`, through `app.json`. This metadata does not change the native architecture. Contributors must use their own Expo project for independent distribution.

```sh
npm install   # package-lock.json is committed; CI may use npm ci
npx eas login
```

`eas submit` only uploads an already built and signed binary; it never builds or signs.

### Google Play

Prerequisites: Play Console developer account; app created with package `com.bloodf.vitahearth`; a Google service account with release permissions, its JSON key downloaded (guide: https://expo.fyi/creating-google-service-account). **The very first AAB must be uploaded manually in Play Console** (Google API limitation); EAS Submit works from the second version on.

```sh
export GOOGLE_SERVICE_ACCOUNT_KEY_PATH=$HOME/play-service-account.json
npm run submit:android -- composeApp/build/outputs/bundle/release/composeApp-release.aab
```

The profile targets the `internal` track with `releaseStatus: draft`; change in `eas.json` when promoting.

### App Store Connect / TestFlight

Prerequisites: app record for bundle ID `com.bloodf.vitahearth` in App Store Connect; an App Store Connect API key (`.p8`, issuer ID, key ID; guide: https://expo.fyi/creating-asc-api-key); an `.ipa` exported with `EXPORT_METHOD=app-store-connect`.

```sh
export ASC_API_KEY_PATH=$HOME/AuthKey_XXXXXXXXXX.p8
export ASC_API_KEY_ISSUER_ID=...
export ASC_API_KEY_ID=XXXXXXXXXX
npm run submit:ios -- build/ios/export/VitaHearth.ipa
```

If EAS asks for the App Store Connect app ID or team ID, add `"ascAppId": "<numeric id from App Store Connect>"` and `"appleTeamId": "<Team ID>"` to `submit.production.ios` locally. These values are account-specific and deliberately not committed.

## Blocked prerequisites (not available in this repository)

- Android upload keystore and passwords.
- Google Play app, service-account JSON; manual first upload.
- Apple Developer team ID, App ID capabilities, distribution signing, App Store Connect app record and API key.
- Expo login with access to `@bloodf/vezuna` in the environment running submission.
- `VITAHEARTH_LVL_PUBLIC_KEY` Gradle property (Play licensing public key) for production builds; empty by default.
- Offline model packs (`models/`) are distributed separately and are not part of these artifacts.
- Physical-device checks, complete third-party/native-library attribution, model provenance, privacy/store declarations, and store approval remain release gates. See [third-party notices](../THIRD_PARTY_NOTICES.md).

# Development

## Requirements

- JDK 17
- Android SDK with API 37 (`compileSdk`/`targetSdk` 37, `minSdk` 26)
- Python 3 for `tools/`
- macOS with Xcode for iOS (deployment target 18.5)
- Gradle wrapper is included (`./gradlew`)

Do not commit `local.properties`, keystores, or `models/`.

## Checks

From the repository root:

```bash
python3 tools/localization/check_localization.py
python3 tools/branding/check_brand.py
python3 tools/lab_importer/test_importer.py
./gradlew :shared:jvmTest :composeApp:assembleDebug :composeApp:testDebugUnitTest :composeApp:assembleDebugAndroidTest
```

Device or emulator required:

```bash
python3 tools/release/stage_smoke_model.py models/qwen2.5-0.5b-instruct-q4_k_m.gguf
./gradlew :composeApp:connectedDebugAndroidTest
python3 tools/localization/check_test_results.py --instrumentation
```

The inference test needs the official Qwen fixture pinned in `third_party/qwen2.5-0.5b-instruct-q4_k_m/provenance.json`. Obtain that exact file from the pinned official repository revision. The staging command checks its size and SHA-256, then copies it and its license into `composeApp/build/generated/modelSmokeAssets/`. Gradle includes that directory only in the test APK. No model download happens during a build. Without staging, `LocalLlmInstrumentedTest` fails with `FileNotFoundException: smoke.gguf`. Never upload the test APK or commit the model.

iOS simulator build (macOS, Xcode):

```bash
bash scripts/build-ios.sh            # Debug; CONFIGURATION=Release for release
```

The script links the shared framework, syncs Compose resources, and runs `xcodebuild` with code signing disabled. It prints `IOS_BUILD_PASSED` on success.

## Private samples

`samples/` is git-ignored and, if present, is private. No build or test in this repository reads it: the Python importer tests and Android instrumentation tests use only synthetic data (`fixtures/lab_importer/`, `synthetic-lab.png`, and a PDF generated at run time in `NativeOcrInstrumentedTest`). Never publish private files or their names in tracked content.

## Premium and licensing in development

- Debug Android builds have `DEBUG_PREMIUM_BYPASS = true` and unlock AI features when `VEZUNA_LVL_PUBLIC_KEY` is empty. Release builds do not.
- Provide the key through the `VEZUNA_LVL_PUBLIC_KEY` environment variable or as a Gradle property, for example in your user-level `~/.gradle/gradle.properties`, never in the repository: `VEZUNA_LVL_PUBLIC_KEY=<your Play licensing public key>`.
- The model catalog (`shared/src/commonMain/composeResources/files/model-packs.json`, shared by both apps) is `[]`. To test inference locally, add a `ModelPack` entry with a real size and SHA-256 in a local change you do not publish.

## Release checklist

Not automated. Needs your own accounts:

1. Configure an upload keystore using [RELEASING](RELEASING.md). Use `app.vezuna` for the official app; a separate fork needs its own identifiers and store entries. See the [identity transition](ARCHITECTURE.md) before installing over an earlier development build.
2. Configure Play licensing key, Play Asset Delivery packs, App Store signing, Apple-hosted asset packs, and a signed model catalog.
3. Record each model's license in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and ship attribution for Apache-2.0 and OFL components.
4. Test on physical devices: HealthKit, Health Connect, camera, notifications over real elapsed time, OCR quality, model memory and speed, store purchase and download.
5. Update [CHANGELOG.md](../CHANGELOG.md), then tag.
6. Confirm no `samples/`, `models/`, keystores, or `build/` output is in the published tree.

## Git hygiene

`.gitignore` excludes private samples, models, generated frameworks, build outputs, Python and Kotlin caches, local credentials, and IDE state. Review design notes and every staged file before publication. Ignore rules do not remove files already committed to Git history.

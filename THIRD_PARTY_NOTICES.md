# Licensing and third-party notices

The Vezuna **source code** is licensed under the MIT license in [LICENSE](LICENSE) (Copyright (c) 2026 bloodf and Vezuna contributors). Nothing below is relicensed by MIT; each item keeps its own terms.

## Scope map

| Item | Location | Terms |
|---|---|---|
| Project source (Kotlin, Swift, Python, Gradle, scripts, docs) | `shared/`, `composeApp/` (except vendored code), `iosApp/` (except fonts), `tools/`, `scripts/`, `fixtures/` | MIT (`LICENSE`) |
| Noto Sans Regular/Bold fonts | `brand/fonts/`, `shared/src/commonMain/composeResources/font/`, `composeApp/src/main/res/font/`, `iosApp/Vezuna/Fonts/` | SIL Open Font License 1.1 (per `NOTO-LICENSE.txt`) |
| Google Play Licensing library (vendored) | `composeApp/src/main/java/com/google/android/vending/licensing/`, `composeApp/src/main/aidl/com/android/vending/licensing/`; license copy at `composeApp/third_party/play-licensing-LICENSE.txt` | Apache-2.0 (file headers: Copyright 2010 The Android Open Source Project) |
| `Base64.java` in the licensing library | `.../licensing/util/Base64.java` | Apache-2.0 header; derived from iharder.net Base64, whose original notice says "placed in the Public Domain" |
| Original Vezuna artwork: logos, marks, app icons, UI icons, social images, tokens, brand guide text | `brand/` (except `brand/fonts/`), `iosApp/Vezuna/Assets.xcassets/AppIcon.appiconset/`, `composeApp/src/main/res/{mipmap-*,drawable}/ic_*`, `shared/src/commonMain/composeResources/drawable/vezuna_*` | MIT, by owner decision (`brand/LICENSE`). Trademark rights are not granted; see [Brand](#brand) |
| Local AI model files (GGUF) | Never in the repository; `models/` is git-ignored | Unresolved; see [Models](#models) |
| Private medical PDFs | `samples/` (git-ignored) | Private, never published; see [Private and synthetic data](#private-and-synthetic-data) |

## Fonts

Noto Sans 400 and 700 are bundled for the Compose, Android, and iOS UIs under the SIL Open Font License 1.1. The OFL requires that each distributed copy carries the copyright notice and license text, that the fonts are not sold by themselves, and that modified versions do not use a Reserved Font Name.

The license file is `NOTO-LICENSE.txt` (byte-identical copies in `brand/fonts/`, `shared/src/commonMain/composeResources/files/`, `composeApp/src/main/assets/`, `iosApp/Vezuna/Fonts/`). It is a Debian-style `copyright` file for Noto packaging, citing upstream `https://github.com/googlei18n/noto-fonts`. It names Google Inc./Google LLC under OFL-1.1. It also lists Debian packaging files under GPL-3+; that covers the `debian/*` packaging, not the fonts. It carries no per-font version or Reserved Font Name statement. The exact upstream release of the bundled `.ttf` files is not recorded here and was not verified. The font files were not modified in this repository.

Keep these license files with any redistributed binary or source.

## Dependencies

Versions come from `gradle/libs.versions.toml` and `composeApp/build.gradle.kts`. The "POM license" column is the `<licenses>` element read from the resolved POM in the local Gradle cache (`~/.gradle/caches/modules-2/files-2.1`). That is publisher-declared metadata for the direct dependency only. The artifact-specific native and iOS checks recorded below are partial; this table is not a complete transitive license review. Re-run the check against the resolved dependency set before each release.

| Dependency | Version | Used for | POM license (as declared) |
|---|---|---|---|
| `kotlinx-coroutines-core`, `kotlinx-coroutines-play-services` | 1.10.2 | Concurrency, Task bridging | Apache-2.0 |
| `kotlinx-serialization-json` | 1.9.0 | JSON | Apache-2.0 |
| `kotlinx-datetime` | 0.7.1 | Dates | Apache-2.0 |
| Kotlin compiler/stdlib | 2.4.21 | Language | Not read |
| Compose Multiplatform `runtime`, `material3`, `material-icons-extended`, `components-resources` | 1.12.2; 1.9.0; 1.7.3; 1.12.2 | Shared UI | The Apache Software License, Version 2.0 |
| Compose `foundation`, `ui` | 1.12.2 | Shared UI | Not read |
| `androidx.core:core-ktx`, `exifinterface`, `activity-compose`, `lifecycle-runtime-ktx` | 1.17.0; 1.4.1; 1.12.4; 2.9.4 | Android platform | The Apache Software License, Version 2.0 |
| `androidx.health.connect:connect-client` | 1.1.0 | Health Connect read | The Apache Software License, Version 2.0 |
| `com.llamatik:core` (and `core-android`) | 2.1.0 | Local LLM inference | POM declares `Apache-2.0`; pinned source declares MIT. Both texts and the collected native-component notices are retained. Binary/source mapping remains partial; see the evidence below |
| `com.google.mlkit:text-recognition` (and `text-recognition-bundled-common` 17.0.0) | 16.0.1 | Android OCR | ML Kit Terms of Service (`https://developers.google.com/ml-kit/terms`); not an open-source license |
| `com.google.android.play:asset-delivery` (and `core-common` 2.0.4) | 2.3.0 | Model-pack delivery | Play Core Software Development Kit Terms of Service (`https://developer.android.com/guide/playcore/license`); not an open-source license |
| `com.tom-roush:pdfbox-android` | 2.0.27.0 | PDF text extraction | The Apache Software License, Version 2.0 |
| Test only: `junit:junit` | 4.13.2 | Tests | Eclipse Public License 1.0 |
| Test only: AndroidX Test `ext:junit`, `runner`, `core-ktx`, UiAutomator | 1.2.1; 1.6.2; 1.6.1; 2.3.0 | Tests | The Apache Software License, Version 2.0 |

Apple frameworks (SwiftUI, CryptoKit, HealthKit, StoreKit, PDFKit, Vision, UserNotifications) are used under Apple SDK and Developer Program terms and are not redistributed.

Before a release, review must verify each dependency's license text, any upstream `NOTICE` file, and the ML Kit and Play Core terms, and satisfy whatever they require. POM metadata alone does not establish those obligations. The Llamatik AAR ships no notices; this project supplies the collected notices separately.

## Bundled notices

The app contains a selectable notice reader under **Device**, then **Third-party notices** on Android, and the **…** menu on iPhone. Reader labels are translated into English, Portuguese, and Spanish; upstream license texts retain their original wording.

The [notice manifest](third_party/notices-manifest.json) pins every input by SHA-256. The [generator](tools/release/build_notices.py) groups identical texts and preserves their bytes inside the generated platform files. Release scripts compare the resolved dependencies with the reviewed inventories, then check the notices in the actual APK, AAB, iOS app, or IPA.

The recorded inventories cover [146 Android artifacts](third_party/android-runtime-inventory.json) and [52 iOS KLIBs](third_party/ios-runtime-inventory.json). Their 100 embedded notice entries reduce to five distinct payloads under `third_party/runtime-notices/`. This checks metadata and preserves supplied notices; it does not independently establish every prebuilt library's source or replace publisher review of SDK agreements.

The PDFBox Android artifact also contains Liberation Sans 2.1.5. Its name table declares SIL OFL 1.1 and Google/Red Hat copyright; the [pinned font license and artifact metadata](third_party/liberation-fonts-2.1.5/PROVENANCE.json) are retained. Bouncy Castle's [license text](third_party/bouncycastle-1.72/LICENSE.txt) was reconstructed from the actual JAR's `org.bouncycastle.LICENSE` bytecode without executing it.

The iOS JPEG implementation requires this acknowledgment: This software is based in part on the work of the Independent JPEG Group. The generated iOS notices include it. Skia also contains Adobe DNG code; [section 5 of its source-code license](third_party/skiko-0.150.2/licenses/dng_sdk/LICENSE.source_code) states commercial-distribution indemnification terms. Publisher review remains pending before any upload.

## Brand

Original Vezuna artwork is licensed under MIT by owner decision; see `brand/LICENSE`. Copies of those assets in the iOS, Android, and shared resource folders carry the same terms (they are synced byte-for-byte from `brand/` by `tools/branding/sync_assets.py`).

The MIT artwork license does not cover:

- **Fonts.** Noto Sans in `brand/fonts/` and its platform copies stays under the SIL OFL 1.1. It is not relicensed.
- **Trademark.** The owner reports purchasing `vezuna.app` and configuring it on Vercel on 2026-10-31. Domain ownership does not establish trademark or store-name clearance. No trademark clearance is claimed. MIT grants copyright permission only. Do not use the Vezuna name or logo to suggest endorsement or to present a fork or modified build as the official app. A fork that keeps the artwork should say it is unofficial and consider changing `app_name` and launcher icons.

The owner's statement that the artwork is original and owner-licensable was not independently verified from this repository.

## Models

The repository ships no model. `ModelCatalog.unconfigured()` is empty. Model packs are meant to arrive through Play Asset Delivery or Apple-hosted assets and be verified by SHA-256. Anyone who adds a pack must:

1. Record the model name, upstream URL, exact revision, license, and conversion/quantization source in the release notes.
2. Keep the model's license text and any required attribution with the distributed pack.
3. Check use restrictions. A GGUF conversion can carry terms from the base model and from the converter.
4. Not commit the file. `.gitignore` excludes `models/`.

The local `models/qwen2.5-0.5b-instruct-q4_k_m.gguf` was verified on 2026-10-31 against the [official Qwen GGUF repository](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/9217f5db79a29953eb74d5343926648285ec7e67). Its 491,400,032 bytes and SHA-256 `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` match the publisher's LFS metadata. The repository declares Apache-2.0. The pinned license text and provenance are in `third_party/qwen2.5-0.5b-instruct-q4_k_m/`. The publisher's model card does not identify an individual converter commit.

`tools/release/stage_smoke_model.py` verifies that exact artifact and stages it with its license in generated **Android test assets only** for `LocalLlmInstrumentedTest`. It does not add the model to the application, configure a production catalog, or assign store asset identifiers. Production delivery, device RAM requirements, and model behavior remain release gates. Never upload the test APK.

For Llamatik 2.1.0, the upstream `v2.1.0` tag resolves to commit `414d6190459bd64ee4d9863c4ab2a7f7761834e1`. Its [LICENSE](https://github.com/llamatik/Llamatik/blob/414d6190459bd64ee4d9863c4ab2a7f7761834e1/LICENSE) is MIT, while its publishing configuration and the resolved POM declare Apache-2.0. The cached Android AAR has SHA-256 `27dc1c0898d3385b5ace2423dc53171575ea9977662da6f9a01c23c3920c691b` and contains no license or notice entries. Its ARM64 libraries include `libwhisper.so`, `libparakeet.so`, and `libomp.so` as well as the llama/ggml libraries. Matching a release tag does not establish the provenance or redistribution obligations of each prebuilt library. That blocker remains unresolved for Android and iOS.

The [native attribution evidence](third_party/llamatik-2.1.0/PROVENANCE.md) records the source submodule revisions and preserves their top-level licenses. The AAR's ARM64 `libllama-common.so` embeds the matching llama.cpp revision prefix `620d2225c`. The pinned whisper.cpp build defines the `parakeet` target. Both packaged OpenMP libraries now match NDK r28c byte-for-byte after standard symbol stripping. Their [NDK provenance and unmodified notices](third_party/android-ndk-r28c/PROVENANCE.json) are preserved and included in the generated Android notices. The [vendor notice sources](third_party/llamatik-2.1.0/vendor-notice-sources.json) cover collected JSON, hashing, compression, image, regex and Unicode dependencies. The remaining binary/source mapping and publisher terms still require review.

For iOS Skiko 0.150.2, all 21 embedded Skia/dependency archives match the official `m150-a3f509bf52` release asset byte-for-byte after reproducing the pinned symbol-renaming task with LLVM 19.0.1. The [provenance](third_party/skiko-0.150.2/PROVENANCE.json), [archive hashes](third_party/skiko-0.150.2/ios-native-source-match.json), and [pinned license sources](third_party/skiko-0.150.2/license-sources.json) are preserved. This verifies that dependency mapping; it does not rebuild Skiko's separate bridge or establish a complete bundled notice set.

## Private and synthetic data

Test fixtures are synthetic: `fixtures/lab_importer/`, `composeApp/src/androidTest/assets/synthetic-lab.png`, and the PDF that `NativeOcrInstrumentedTest` generates at run time from fictional text. Separately, `samples/` is git-ignored and may hold private medical PDFs on a maintainer's machine; no build or test in this repository reads or packages it. See [CONTRIBUTING.md](CONTRIBUTING.md#synthetic-data-only).

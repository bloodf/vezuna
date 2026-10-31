# Licensing and third-party notices

The Vezuna **source code** is licensed under the MIT license in [LICENSE](LICENSE) (Copyright (c) 2026 bloodf and Vezuna contributors). Nothing below is relicensed by MIT; each item keeps its own terms.

## Scope map

| Item | Location | Terms |
|---|---|---|
| Project source (Kotlin, Swift, Python, Gradle, scripts, docs) | `shared/`, `composeApp/` (except vendored code), `iosApp/` (except fonts), `tools/`, `scripts/`, `fixtures/` | MIT (`LICENSE`) |
| Noto Sans Regular/Bold fonts | `brand/fonts/`, `shared/src/commonMain/composeResources/font/`, `composeApp/src/main/res/font/`, `iosApp/VitaHearth/Fonts/` | SIL Open Font License 1.1 (per `NOTO-LICENSE.txt`) |
| Google Play Licensing library (vendored) | `composeApp/src/main/java/com/google/android/vending/licensing/`, `composeApp/src/main/aidl/com/android/vending/licensing/`; license copy at `composeApp/third_party/play-licensing-LICENSE.txt` | Apache-2.0 (file headers: Copyright 2010 The Android Open Source Project) |
| `Base64.java` in the licensing library | `.../licensing/util/Base64.java` | Apache-2.0 header; derived from iharder.net Base64, whose original notice says "placed in the Public Domain" |
| Original Vezuna artwork: logos, marks, app icons, UI icons, social images, tokens, brand guide text | `brand/` (except `brand/fonts/`), `iosApp/VitaHearth/Assets.xcassets/AppIcon.appiconset/`, `composeApp/src/main/res/{mipmap-*,drawable}/ic_*`, `shared/src/commonMain/composeResources/drawable/vezuna_*` | MIT, by owner decision (`brand/LICENSE`). Trademark rights are not granted; see [Brand](#brand) |
| Local AI model files (GGUF) | Never in the repository; `models/` is git-ignored | Unresolved; see [Models](#models) |
| Private medical PDFs | `samples/` (git-ignored) | Private, never published; see [Private and synthetic data](#private-and-synthetic-data) |

## Fonts

Noto Sans 400 and 700 are bundled for the Compose, Android, and iOS UIs under the SIL Open Font License 1.1. The OFL requires that each distributed copy carries the copyright notice and license text, that the fonts are not sold by themselves, and that modified versions do not use a Reserved Font Name.

The license file is `NOTO-LICENSE.txt` (byte-identical copies in `brand/fonts/`, `shared/src/commonMain/composeResources/files/`, `composeApp/src/main/assets/`, `iosApp/VitaHearth/Fonts/`). It is a Debian-style `copyright` file for Noto packaging, citing upstream `https://github.com/googlei18n/noto-fonts`. It names Google Inc./Google LLC under OFL-1.1. It also lists Debian packaging files under GPL-3+; that covers the `debian/*` packaging, not the fonts. It carries no per-font version or Reserved Font Name statement. The exact upstream release of the bundled `.ttf` files is not recorded here and was not verified. The font files were not modified in this repository.

Keep these license files with any redistributed binary or source.

## Dependencies

Versions come from `gradle/libs.versions.toml` and `composeApp/build.gradle.kts`. The "POM license" column is the `<licenses>` element read from the resolved POM in the local Gradle cache (`~/.gradle/caches/modules-2/files-2.1`). That is publisher-declared metadata for the direct dependency only. **Transitive dependencies, native binaries inside artifacts, and iOS (klib) variants were not checked.** Re-run the check against the resolved dependency set before each release.

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
| `com.llamatik:core` (and `core-android`) | 2.1.0 | Local LLM inference | POM declares `Apache-2.0`. The `core-release.aar` contains no license or notice file. Provenance and attribution of its native libraries (`libllama*`, `libggml*`, apparently llama.cpp-derived) are **unresolved**. A separate upstream repository page showed a different license; do not rely on it for this artifact |
| `com.google.mlkit:text-recognition` (and `text-recognition-bundled-common` 17.0.0) | 16.0.1 | Android OCR | ML Kit Terms of Service (`https://developers.google.com/ml-kit/terms`); not an open-source license |
| `com.google.android.play:asset-delivery` (and `core-common` 2.0.4) | 2.3.0 | Model-pack delivery | Play Core Software Development Kit Terms of Service (`https://developer.android.com/guide/playcore/license`); not an open-source license |
| `com.tom-roush:pdfbox-android` | 2.0.27.0 | PDF text extraction | The Apache Software License, Version 2.0 |
| Test only: `junit:junit` | 4.13.2 | Tests | Eclipse Public License 1.0 |
| Test only: AndroidX Test `ext:junit`, `runner`, `core-ktx`, UiAutomator | 1.2.1; 1.6.2; 1.6.1; 2.3.0 | Tests | The Apache Software License, Version 2.0 |

Apple frameworks (SwiftUI, CryptoKit, HealthKit, StoreKit, PDFKit, Vision, UserNotifications) are used under Apple SDK and Developer Program terms and are not redistributed.

Before a release, review must verify each dependency's license text, any upstream `NOTICE` file, and the ML Kit and Play Core terms, and satisfy whatever they require. POM metadata alone does not establish those obligations. Gradle does not generate attribution, and the Llamatik AAR ships none. No bundled or in-app open-source notices file exists yet.

## Brand

Original Vezuna artwork is licensed under MIT by owner decision; see `brand/LICENSE`. Copies of those assets in the iOS, Android, and shared resource folders carry the same terms (they are synced byte-for-byte from `brand/` by `tools/branding/sync_assets.py`).

The MIT artwork license does not cover:

- **Fonts.** Noto Sans in `brand/fonts/` and its platform copies stays under the SIL OFL 1.1. It is not relicensed.
- **Trademark.** `brand/README.md` and `brand/SOURCES.md` say the name is a v1.0 graphic proposal and not a trademark registration, domain, or store-name clearance. No trademark has been registered or cleared. MIT grants copyright permission only. Do not use the Vezuna name or logo to suggest endorsement or to present a fork or modified build as the official app. A fork that keeps the artwork should say it is unofficial and consider changing `app_name` and launcher icons.

The owner's statement that the artwork is original and owner-licensable was not independently verified from this repository.

## Models

The repository ships no model. `ModelCatalog.unconfigured()` is empty. Model packs are meant to arrive through Play Asset Delivery or Apple-hosted assets and be verified by SHA-256. Anyone who adds a pack must:

1. Record the model name, upstream URL, exact revision, license, and conversion/quantization source in the release notes.
2. Keep the model's license text and any required attribution with the distributed pack.
3. Check use restrictions. A GGUF conversion can carry terms from the base model and from the converter.
4. Not commit the file. `.gitignore` excludes `models/`.

A local, untracked file `models/qwen2.5-0.5b-instruct-q4_k_m.gguf` exists on the maintainer machine. Its filename does not establish its upstream, license, or conversion provenance. No license metadata was found and no source file references it. Redistribution status is **unresolved**. Do not ship it or include it in any artifact.

## Private and synthetic data

Test fixtures are synthetic: `fixtures/lab_importer/`, `composeApp/src/androidTest/assets/synthetic-lab.png`, and the PDF that `NativeOcrInstrumentedTest` generates at run time from fictional text. Separately, `samples/` is git-ignored and may hold private medical PDFs on a maintainer's machine; no build or test in this repository reads or packages it. See [CONTRIBUTING.md](CONTRIBUTING.md#synthetic-data-only).

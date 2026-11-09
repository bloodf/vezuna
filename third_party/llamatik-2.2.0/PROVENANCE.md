# Llamatik 2.2.0 Android: partial attribution evidence

The resolved `com.llamatik:core-android:2.2.0` AAR is 83,718,561 bytes and has SHA-256 `8fb2020cceb5396917c0363cd5852fb52532a003bc0b9ad51539859acc2a3fc3`. Its POM declares Apache-2.0. The [2.2 source release LICENSE](https://github.com/llamatik/Llamatik/blob/10f3a99d5f74d727c43b3e1475b8931403e592d2/LICENSE) says MIT. Both observed declarations are preserved. On 2026-11-05, the [publisher clarified](https://github.com/llamatik/Llamatik/issues/184#issuecomment-6666093632) that MIT governs the library code and that the Apache-2.0 POM entry was a copy/paste error. [Commit e4b5e1a71b12e087f702902b0733fe3936afcd3b](https://github.com/llamatik/Llamatik/commit/e4b5e1a71b12e087f702902b0733fe3936afcd3b) and [PR #185](https://github.com/llamatik/Llamatik/pull/185) landed in published upstream release `2.2.2` with MIT POM metadata; published `core-android:2.2.2` metadata reports the same size and SHA-256 as this retained 2.2.0 AAR; this metadata comparison is not a fresh comparison of both artifact byte streams. This supplier clarification resolves the unanswered code-license question, not the complete notice requirements of every packaged binary.

The source release pins llama.cpp `620d2225cc436ab0b001e3200e590e3b286ef9ab`, whisper.cpp `d1be6fde11ac6e0407606b4e42fe72d34add8037`, and stable-diffusion.cpp `77b72a2efed78f55de26892c7e909cee001e8b83`. The first two revisions match the 2.1 source release. The 2.2 Llamatik and stable-diffusion top-level license texts match the retained 2.1 copies. Ten previously recorded stable-diffusion vendor notice-source paths also have identical Git blob IDs at the two pinned revisions. This path comparison is not an exhaustive attribution audit and does not prove the source of every binary.

The AAR packages `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64` libraries. Each packaged `libomp.so` matches the corresponding official NDK r29 library after the documented strip operation. See [android-ndk-r29/PROVENANCE.json](android-ndk-r29/PROVENANCE.json) for the archive identity, SHA-1 metadata limitation, exact hashes, and scope of that byte comparison. The remaining native-library source and notice map is not established by this match.

The stable-diffusion JNI implementation and pinned stable-diffusion revision changed in 2.2. The upstream Android target links stable-diffusion code into `libllama_jni.so`; the AAR has no separate stable-diffusion shared library. This observation does not establish the origin of every linked component or complete redistribution compliance. Native runtime behavior and final APK/AAB alignment require separate validation.

## Publisher build claims and remaining limits

The same response supplies native revisions, NDK `29.0.14206865`, a macOS 14 / Temurin 21 build environment, four ABIs and CPU configuration details. These are publisher statements, not an independently reproduced build or artifact-bound link map.

On 2026-11-06, the publisher [reconciled the recursive checkout configuration with codec exclusion](https://github.com/llamatik/Llamatik/issues/184#issuecomment-6681501282). While `actions/checkout@v4` with `submodules: recursive` initialized `thirdparty/libwebp` and `thirdparty/libwebm` on disk at pinned revision `77b72a2efed78f55de26892c7e909cee001e8b83` (evaluating `SD_SUBMODULE_WEBP` and `SD_SUBMODULE_WEBM` to `TRUE`), three independent guards prevented codec object code from reaching the Android native targets:
1. `thirdparty/CMakeLists.txt` declares both codec subdirectories with `EXCLUDE_FROM_ALL`.
2. The only `target_link_libraries(... webp ...)` and `target_link_libraries(... webm ...)` calls appear in `examples/cli/CMakeLists.txt` and `examples/server/CMakeLists.txt`; the core `stable-diffusion` library target has no dependency on `webp` or `webm`, and `src/` has zero references to WebP/WebM.
3. `core/src/commonMain/cpp/CMakeLists.txt` sets `SD_BUILD_EXAMPLES=OFF`, excluding `examples/` from the build graph and leaving the `EXCLUDE_FROM_ALL` codec targets unbuilt.

This reconciles the publisher’s codec-exclusion explanation with its recursive checkout statement for the identified 2.2.0 artifact. The publisher reports no codec linkage but has no retained original CMake cache. This is supplier build-graph evidence, not an independently reproduced build or artifact-bound link map.

The later [asset exception and NOTICE](https://github.com/llamatik/Llamatik/tree/52404abe5979ffba91c187c4f97365846c67ec17) distinguish library code from proprietary Llamatik artwork. They do not replace the pinned license copies or the transitive-component and compiler-runtime notices already collected here. No permission to redistribute Llamatik artwork is inferred. Complete native attribution and source-to-binary correspondence remain open; this clarification is not runtime or distribution acceptance.

## Upstream 2.2.2 qualification and adoption boundaries

Upstream release `2.2.2` published on Maven Central resolves the POM license discrepancy (declaring MIT for `core`, `core-android`, `core-iosarm64`, and `core-iossimulatorarm64`). Android and iOS updates require separate qualification before adoption.

### Published upstream coordinates and build identifiers

- **Upstream repository**: `https://github.com/llamatik/Llamatik`
- **Release tag**: `v2.2.2` (publisher commit `52404abe5979ffba91c187c4f97365846c67ec17`, PR [#185](https://github.com/llamatik/Llamatik/pull/185))
- **Build runner / toolchain**: GitHub Actions `macos-14`, Temurin JDK 21, Android NDK `29.0.14206865` (workflow `.github/workflows/publish-mavencentral.yml`)
- **Pinned native source submodule revisions**:
  - `llama.cpp`: `620d2225cc436ab0b001e3200e590e3b286ef9ab`
  - `whisper.cpp`: `d1be6fde11ac6e0407606b4e42fe72d34add8037`
  - `stable-diffusion.cpp`: `77b72a2efed78f55de26892c7e909cee001e8b83`
- **Maven Central public coordinates**:
  - Root: `com.llamatik:core:2.2.2` (`https://repo1.maven.org/maven2/com/llamatik/core/2.2.2/`)
  - Android AAR: `com.llamatik:core-android:2.2.2` (`https://repo1.maven.org/maven2/com/llamatik/core-android/2.2.2/core-android-2.2.2.aar`, 83,718,561 bytes, SHA-256 `8fb2020cceb5396917c0363cd5852fb52532a003bc0b9ad51539859acc2a3fc3`, ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`)
  - iOS Device (`ios_arm64`): `https://repo1.maven.org/maven2/com/llamatik/core-iosarm64/2.2.2/`
    - `core-iosarm64-2.2.2.klib` (60,775 bytes)
    - `core-iosarm64-2.2.2-cinterop-llama.klib` (28,237,149 bytes, SHA-256 `592e8a14ce9f747fcdb7ce8afb1ef35737033d57ad18dc3f59936fd6b302be43`)
    - `core-iosarm64-2.2.2-cinterop-stableDiffusion.klib` (28,232,794 bytes)
    - `core-iosarm64-2.2.2-cinterop-multimodal.klib`
    - `core-iosarm64-2.2.2-cinterop-whisper.klib`
  - iOS Simulator (`ios_simulator_arm64`): `https://repo1.maven.org/maven2/com/llamatik/core-iossimulatorarm64/2.2.2/`
    - `core-iossimulatorarm64-2.2.2.klib`
    - `core-iossimulatorarm64-2.2.2-cinterop-llama.klib` (28,304,853 bytes)
    - `core-iossimulatorarm64-2.2.2-cinterop-stableDiffusion.klib`
    - `core-iossimulatorarm64-2.2.2-cinterop-multimodal.klib`
    - `core-iossimulatorarm64-2.2.2-cinterop-whisper.klib`
  - iOS Simulator (`ios_x64`): `https://repo1.maven.org/maven2/com/llamatik/core-iosx64/2.2.2/`

### Concrete missing artifact request

Before iOS or Android 2.2.2 can be considered for repository admission, the following missing artifact evidence must be requested and verified:
1. **KLIB native static archive inventory**: Unpack each published cinterop KLIB across all three Apple ABIs (`ios_arm64`, `ios_simulator_arm64`, `ios_x64`) and document the exact archive filename under `default/targets/<target>/included/`, byte size, and SHA-256 digest (establishing whether the modular KLIBs package split archives or duplicate `libllama_merged.a`).
2. **Artifact-bound source and build link map**: Independent verification mapping the published static archive object symbols directly to the pinned submodule revisions (`620d2225c`, `d1be6fde`, `77b72a2e`), ensuring no unrecorded compiler flags or unvetted patches exist in the binaries.
3. **Native symbol safety audit**: Full symbol inspection of undefined and dynamic symbols in each iOS archive, specifically verifying the call-site destinations and privacy compliance for `stat`, `fstat`, and `sysctlbyname` under Apple Required Reason API policies, and confirming zero non-public Apple framework symbols.
4. **Transitive notice manifest**: Complete verified notice mapping for all third-party code compiled into the native submodules (nlohmann JSON, cpp-httplib, xxHash, stb, etc.), matching the expanded 2.2.2 dependency set.

### Reason cannot adopt yet

- **Android**: While published `core-android:2.2.2` metadata reports the same SHA-256 digest as the retained 2.2.0 AAR, adopting it provides no new functionality or binary fix; neither artifact packages notices, which remain locally curated.
- **iOS**: Upstream 2.2.2 replaces the reviewed monolithic 2.1.0 cinterop architecture with unvetted modular KLIBs. Without the concrete archive inventory, symbol safety audit, and notice manifest requested above, adopting 2.2.2 on iOS would introduce unverified native code into the release pipeline.
- **Policy constraint**: Pins in `gradle/libs.versions.toml` (`llamatik = "2.1.0"` and `llamatik-android = "2.2.0"`) remain unchanged. No redistribution clearance is claimed and the release veto remains in effect.

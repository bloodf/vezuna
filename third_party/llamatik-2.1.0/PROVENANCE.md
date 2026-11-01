# Llamatik 2.1.0: partial native attribution evidence

Checked on 2026-10-31. These files preserve pinned source licenses. They do not establish complete binary redistribution compliance, and their collected texts are now included in the generated app notices.

The resolved `com.llamatik:core-android:2.1.0` AAR has SHA-256 `27dc1c0898d3385b5ace2423dc53171575ea9977662da6f9a01c23c3920c691b`. It contains no license or notice entries. Its POM declares Apache-2.0, while the [source release LICENSE](https://github.com/llamatik/Llamatik/blob/414d6190459bd64ee4d9863c4ab2a7f7761834e1/LICENSE) says MIT.

The `v2.1.0` source tree pins these submodules:

| Component | Revision | Source license |
| --- | --- | --- |
| Llamatik | `414d6190459bd64ee4d9863c4ab2a7f7761834e1` | MIT |
| llama.cpp | `620d2225cc436ab0b001e3200e590e3b286ef9ab` | MIT |
| whisper.cpp | `d1be6fde11ac6e0407606b4e42fe72d34add8037` | MIT |
| stable-diffusion.cpp | `95b3090664d019fcf8fa032a44d1d4793ba244b8` | MIT |

The release tree is available through [GitHub's tree API](https://api.github.com/repos/llamatik/Llamatik/git/trees/414d6190459bd64ee4d9863c4ab2a7f7761834e1?recursive=1). `sources.json` records the exact license URLs and hashes of the unmodified local copies.

The AAR's ARM64 `libllama-common.so` contains the printable revision `620d2225c`, matching the pinned llama.cpp revision prefix. That provides evidence for this component; it does not prove every AAR library's source.

The pinned [Android CMake file](https://github.com/llamatik/Llamatik/blob/414d6190459bd64ee4d9863c4ab2a7f7761834e1/core/src/commonMain/cpp/CMakeLists.txt) builds llama.cpp, whisper.cpp, and stable-diffusion.cpp. The pinned [whisper CMake file](https://github.com/ggml-org/whisper.cpp/blob/d1be6fde11ac6e0407606b4e42fe72d34add8037/src/CMakeLists.txt) defines the `parakeet` target. This explains why `libparakeet.so` is present without assuming an unrelated speech-model license applies. No speech-model weights were found in the inspected AAR.


## iOS device dependency inspection

The resolved `core-iosarm64:2.1.0` dependencies were inspected separately. `ios-native-inventory.json` records the exact KLIB and embedded archive hashes. The llama, multimodal and stable-diffusion cinterop KLIBs carry the same 68,783,208-byte `libllama_merged.a`, SHA-256 `b5f94a6b8219e1d23987368e25543e509840b0e021c032e8be779a74a21ee517`. No license, notice or privacy-manifest entry was found in those five KLIBs.

The merged archive contains the printable revision prefixes `620d2225c` and `95b3090`, matching the pinned llama.cpp and stable-diffusion source revisions. Its undefined symbols include `stat`, `fstat` and `sysctlbyname`. A symbol identifies a review target; it does not establish the API's call-site purpose or an approved privacy reason. The final Release app must be inspected after linking.

The Android AAR's ARM64 and x86_64 `libomp.so` files now match Android NDK r28c `28.2.13676358` byte-for-byte after applying that NDK's `llvm-strip --strip-unneeded` to a copy. The ARM64 GNU build ID also matches before stripping. Compiler strings alone were not used as proof. Earlier hash-only comparisons differed because the official runtime retained symbols. The exact transformed hashes, commands and unmodified NDK notices are preserved in [android-ndk-r28c](../android-ndk-r28c/PROVENANCE.json). This resolves OpenMP binary provenance for both packaged ABIs; the notices are now included in the generated Android app notices.


The collected vendor notices are recorded in `vendor-notice-sources.json`. They include nlohmann JSON and its attributed snippets, cpp-httplib, SHA helpers, xxHash, Mozilla llamafile SGEMM, stb, miniz/ZIP, Darts, Oniguruma and utf8proc. Full files or extracted notice blocks are pinned to source revisions and hashes. Both published Apache-2.0 metadata and source MIT texts are retained, without a relicensing claim.

The following release work remains:

- Establish the terms applicable to the published Llamatik artifact despite the MIT and Apache-2.0 metadata conflict.
- Finish the remaining native-library source and notice map. Both packaged OpenMP libraries are now matched to the official NDK as recorded above.
- Inspect third-party code compiled into the pinned submodules, rather than treating their top-level MIT licenses as exhaustive.
- Inspect the final iOS device archive after linking. The dependency inspection above is partial evidence.
- Keep model notices with future production packs. Collected app notices are bundled and checked; Android signed candidates predating this integration require a rebuild.

Binary distribution remains blocked until those obligations are satisfied.

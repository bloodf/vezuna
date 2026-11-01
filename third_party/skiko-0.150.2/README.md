# Skiko 0.150.2 native dependency evidence

This directory preserves pinned source licenses and a byte-for-byte provenance check. It is not yet a complete bundled attribution set.

Skiko commit `caed3841b8725429bb9b9805ff162a40eb7bce47` pins Skia `m150-a3f509bf52`. The official iOS arm64 release archive was verified against its publisher SHA-256. All 21 Skia/dependency archives embedded in `skiko-iosArm64Main-0.150.2.klib` match that release after the pinned `PatchSkiaSymbolsTask` transformation.

The reproduction extracts global defined symbols with `xcrun nm -g --defined-only`, skips symbols already ending in `_skiko`, applies the upstream C++ namespace/C suffix mapping, and runs `llvm-objcopy --redefine-syms=<map>` on copies. LLVM 19.0.1 from official NDK r28c reproduces every archive hash. Homebrew LLVM 22 does not. Original caches and SDK files were not modified.

See `PROVENANCE.json` for revisions and toolchain details, `ios-native-source-match.json` for all archive hashes, and `license-sources.json` for URLs and hashes of unmodified license files. Skiko's separate native bridge was not rebuilt in this check. Review source-level embedded third-party notices before treating the root license copies as exhaustive.

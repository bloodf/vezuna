# iOS privacy manifest evidence

The app manifest declares no tracking. It does not supply a final App Store data-collection label. Publisher review of the complete release and its third-party components remains required.

`ComposeMultiplatformPrivacy.bundle/PrivacyInfo.xcprivacy` describes the statically linked Compose Multiplatform SDK separately from app-owned code. Its file-timestamp reason is `0A2A.1`, the SDK-wrapper reason recommended in [JetBrains' Compose privacy guidance](https://kotlinlang.org/docs/multiplatform/multiplatform-privacy-manifest.html#compose-multiplatform). [Apple restricts this reason to third-party SDK wrappers](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api). Do not copy it into the app-owned manifest or treat it as a reason for arbitrary app file access.

The previously validated release executable imports `stat` and `fstat`. Its matching dSYM identifies the actual call sites as Skia's `sk_fmmap` and ICU's `uprv_mapFile_skiko`. Both inspect file metadata before read-only file mapping. These are SDK helpers, not app-owned health-data collection. Pinned native source and binary matching evidence is recorded under `third_party/skiko-0.150.2/`. The SDK manifest is supplied by this integration according to JetBrains' guidance. It is not claimed to be an upstream-signed vendor manifest.

The inspected executable does not import `mach_absolute_time` or `NSUserDefaults`. No system-boot-time, preferences, or disk-space reason is added speculatively. Recheck the actual final executable after native dependency or model changes. The earlier simulator startup probe observed no file-API events; that limited observation does not prove the APIs are unused.

Xcode must retain the SDK resource bundle and both manifests in the built app. `tools/release/check_ios_metadata.py` checks their exact declarations and compares them with the repository inputs. Store processing and full device behavior remain unverified. If Apple does not aggregate the SDK resource bundle, fix packaging using Apple's response rather than placing an SDK-only reason in the app manifest by assumption.

# Contributing

Thanks for helping. Vezuna is an offline household health-record app (Kotlin Multiplatform + Compose Multiplatform, native Android and Xcode iOS). Read [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) to build and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for layout.

Vezuna source code is licensed under the MIT license in [LICENSE](LICENSE). Original Vezuna artwork in `brand/` is also MIT ([brand/LICENSE](brand/LICENSE)), without trademark rights. By contributing you agree your contribution is licensed the same way. Fonts and dependencies keep their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Synthetic data only

- Never commit, attach, paste, or screenshot real health data: lab reports, prescriptions, names, birth dates, ID numbers, addresses, phone numbers, emails, barcodes, verification tokens, or real report hashes. This includes issues and pull requests.
- Use made-up values. Tests and demos use `fixtures/lab_importer/*.txt` style text, `composeApp/src/androidTest/assets/synthetic-lab.png`, and generated records.
- `samples/` is git-ignored and may hold private medical PDFs on a maintainer's machine. Do not add files there to a PR, and do not copy its contents or file names into tracked files. No Gradle build, Android test, or Python test in this repository reads it.
- Run `python3 tools/lab_importer/test_importer.py`. It scans fixtures for identifier patterns (`tools/lab_importer/privacy.py`) and fails without echoing matched text.
- Do not send health data, even synthetic samples derived from real ones, to external AI or analysis services.

Also never commit: signing keys (`*.keystore`, `*.jks`), `local.properties`, the Play LVL public key from a private setup, store or account credentials, or model binaries (`*.gguf`, `models/`).

## Safety limits for features

The product does not diagnose, recommend treatment, change doses, or invent reference ranges. Keep it that way.

- OCR and import results are drafts until the user confirms them.
- Never gate records, backup, or export behind licensing or AI availability.
- Do not add analytics, telemetry, ads, or any network client for health data. Android must keep `INTERNET` removed from the merged manifest.
- Do not add claims like "secure", "private", "clinically validated", or "HIPAA/GDPR compliant" to UI, docs, or store text unless the code and review support them. Prefer exact statements, for example "AES-256-GCM key in Android Keystore".
- Localization never changes stored clinical values, units, or reference ranges.
- Do not change `applicationId`/bundle ID `app.vezuna`, storage keys, or file formats without a migration and discussion first.

## Workflow

1. Open an issue for non-trivial changes before large work.
2. Keep one change per PR. Match existing patterns: shared logic in `shared/commonMain`, platform code behind `expect`/`actual` or the existing bridge classes.
3. All user-visible strings go in the string catalogs for English, Portuguese, and Spanish (`shared/.../composeResources/values*/strings.xml`, `composeApp/src/main/res/values*/strings.xml`, `iosApp/Vezuna/*.lproj`). `tools/localization/check_localization.py` checks parity.
4. Add or update tests with behavior changes (`shared/src/commonTest`, `composeApp/src/test`, `composeApp/src/androidTest`, `tools/lab_importer/test_importer.py`).
5. Update `CHANGELOG.md` under "Unreleased" for user-visible changes.
6. Run the checks listed in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#checks) and say which ones you ran. Say what you could not run (for example iOS runtime or a physical device).

## Brand and assets

Do not edit `brand/` assets by hand or redraw the logo. Resource copies are synced from `brand/` by `tools/branding/sync_assets.py` and verified by `tools/branding/check_brand.py`. The visible name is "Vezuna". Do not add third-party fonts, icon packs, or images without a license entry in THIRD_PARTY_NOTICES.md. Do not contribute artwork you did not create or cannot license under MIT.

## Reporting security issues

Do not open public issues for vulnerabilities. See [SECURITY.md](SECURITY.md).

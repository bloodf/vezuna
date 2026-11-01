# README image provenance

The README banner is original artwork from `brand/social/github-1280x640.png`, licensed under `brand/LICENSE`.

The Android gallery consists of unmodified emulator screenshots produced by `AppVisualInstrumentedTest.capturesMainDestinationsDarkThemeAndLargeText` on 2026-10-31. The test runs against the `app.vezuna` development app and uses fictional profiles and records. It captures the actual Compose interface rather than a generated mockup.

| Repository file | Test capture |
| --- | --- |
| `android-home.png` | `/sdcard/ui-home.png` |
| `android-labs.png` | `/sdcard/ui-labs.png` |
| `android-settings.png` | `/sdcard/ui-settings.png` |

To refresh the gallery, stage the verified model fixture described in [Development](../DEVELOPMENT.md), run `:composeApp:connectedDebugAndroidTest`, inspect the screenshots for synthetic data only, and copy these three captures from the emulator. Never substitute real medical records. These images do not establish iOS runtime behavior, release signing, or store availability.

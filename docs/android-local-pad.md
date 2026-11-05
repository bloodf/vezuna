# Isolated Android delivery-test variant

`localPad` inherits debug behavior and installs as `app.vezuna.padtest`, with a visible test label in English, Portuguese and Spanish. The normal debug/release application ID remains `app.vezuna`. The existing FileProvider authority derives from the application ID, so this variant uses `app.vezuna.padtest.files`.

This variant explicitly uses a blank LVL key together with the existing debug-only premium bypass. It is only for local bundletool delivery tests; it does not test real Play licensing. Release keeps its original LVL key configuration and `DEBUG_PREMIUM_BYPASS=false`. Never upload this debug variant to a store.

Build `:composeApp:assembleLocalPad` for package/manifest inspection. Once the asset-pack module is integrated, use `:composeApp:bundleLocalPad` with the explicit verified model inputs documented in the asset-pack change. Keep the existing build semaphore, required Java/SDK and disk floor. No model download, catalog entry or inference is implied by selecting this variant.

Before any device installation, inspect the exact APK/APKS manifest and signing identity, confirm the intended test-package identity and an exclusive phone lease, and verify that the test installation has a distinct UID and data directory. A suffix in source alone is not runtime isolation proof. Use synthetic records only. Never uninstall or clear `app.vezuna` for local delivery testing. Any replacement/cleanup of the disposable test installation remains a separately scoped device operation.

Successful local delivery is separate from Play-served delivery, real license verification, model memory/quality/safety acceptance and native runtime obligations. This variant does not waive those release requirements.

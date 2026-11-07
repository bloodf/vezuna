# Android model asset pack

The `model_asset_pack` module publishes the on-demand Play Asset Delivery pack `vezuna_ai`. App bundles consume its assets only after the reviewed local staging tool verifies the supplied model and license. No model download occurs in Gradle. Generated binaries and sidecars stay ignored under `model_asset_pack/src/main/assets/`.

Supply absolute local paths as Gradle properties `VEZUNA_MODEL_SOURCE`, `VEZUNA_MODEL_METADATA` and `VEZUNA_MODEL_LICENSE`. Metadata follows [Android model staging](android-model-staging.md), including the pinned revision, exact size and SHA-256. The task always verifies the bytes even if paths have not changed. Missing inputs fail bundle assembly instead of silently publishing an empty pack. Ordinary source-only checks and APK assembly do not need the model pack.

Example after acquiring the existing build semaphore and selecting the required Java/SDK:

```sh
./gradlew :composeApp:bundleDebug \
  -PVEZUNA_MODEL_SOURCE=/absolute/owned/model.gguf \
  -PVEZUNA_MODEL_METADATA=/absolute/owned/metadata.json \
  -PVEZUNA_MODEL_LICENSE=/absolute/owned/LICENSE
```

The supplied metadata is an explicit local input, not a production model approval. Packaging acceptance still requires inspecting the final bundle's pack name, delivery mode, model bytes, license and provenance against the approved catalog. The production catalog remains empty until its resource and safety requirements are satisfied; this module alone does not make AI available in the app.

Local bundletool testing and an isolated debug application ID remain separate work. Do not uninstall or clear the owner's installed app to test a pack. Local delivery cannot establish real Play licensing or Play-served downloads. Release signing/version requirements, model/runtime attribution, independent source review, exact-tree Android gates and device acceptance still apply. No upload is part of this command.

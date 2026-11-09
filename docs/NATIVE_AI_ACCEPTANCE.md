# Native AI acceptance evidence contract

Physical Android and iOS acceptance is pending. Do not run inference on an emulator. The Android staging helper verifies the copied fixture against `third_party/qwen2.5-0.5b-instruct-q4_k_m/provenance.json`; its success does not prove a device run. The Android host integrity tests and instrumentation XML checker do not establish physical acceptance. The iOS XCTest host described in DEVELOPMENT.md skips the real runtime.

For each physical run, retain a machine-readable JSON report and its raw supporting evidence together. Use synthetic prompts only; exclude health records, model bytes, credentials and device identifiers. Record these required fields:

- `source_sha`: full Git commit, plus a hash of any uncommitted source patch used in the build.
- `platform`, `device_model`, `os_version`, and `physical_device`: platform, model, OS version and boolean device classification.
- `build`: native build configuration, app version, and SHA-256 of the installed build artifact; retain evidence connecting that artifact to the installation.
- `fixture`: SHA-256 and byte size of the actual model used, the pinned provenance file's SHA-256, and evidence connecting the verified bytes to the loaded model. The pinned fixture hash and size must match provenance; never include the model itself.
- `requested_settings`: the exact requested inference settings and their values, and `effective_settings` with evidence for values actually applied; use null for unknown values.
- `operations`: separate load, generation, cancellation, subsequent generation, app restart and offline generation observations. Each includes `status` (`pass`, `fail`, or `unknown`) and references to raw evidence from this same run. Record cancellation after partial output and the next generation outcome separately. Record the restart and reload outcome separately.
- `offline`: observed network state and supporting evidence covering the offline generation interval; use `unknown` if it cannot be established. A requested airplane-mode setting alone is not evidence of the interval.
- `started_at`, `finished_at`, and evidence hashes: timestamps and SHA-256 for each retained evidence file. Timestamps are operator observations, not independent freshness proof.

A report is incomplete if any field or evidence binding is absent or unknown. A pass requires all applicable operations to pass on that physical device with matching fixture and build identities; failures and unknowns remain visible. Do not infer performance budgets or a network state from a passing generation. Record actual measurements and their method separately if collected; no budget is defined here. This document specifies a contract, not an implemented report validator or a claim that either platform passed.

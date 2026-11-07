# iOS upgrade regression inputs

This local preflight is a prerequisite for #148, not an upgrade test. It accepts two explicit built simulator `.app` directories and a JSON manifest for each. The old app must be attested as source `b9415e6f65a1e21d99d59dbc4b4c04e4e01b622c`; the caller supplies the exact current source SHA. Neither app is discovered automatically. Use separately prepared artifacts and preserve them outside disposable build output.

Each manifest has exactly these fields, with lowercase hexadecimal values:

```json
{"source_sha":"<40-character source commit>","bundle_sha256":"<64-character bundle digest>"}
```

Calculate the digest after the bundle is complete, using the same function as the checker. For example, run the following with `APP` set to the explicit app directory, then put the printed value into its manifest:

```sh
APP=/absolute/path/Vezuna.app python3 -c 'import os, sys; from pathlib import Path; sys.path.insert(0, "tools/release"); from check_ios_upgrade_inputs import bundle_digest; print(bundle_digest(Path(os.environ["APP"])))'
```

The digest covers relative names, entry types, executable mode, file bytes in sorted order. All symlinks (including internal and dangling links) are rejected before metadata validation; supply a self-contained bundle with regular files and directories. It is this checker's bundle digest, not an archive or signing digest. Keep the bundle unchanged between hashing and preflight. The manifest asserts the source commit; a matching digest connects that assertion to the supplied bytes but does **not** independently prove which source built them. Retain separate trusted build records, toolchain/SDK details and artifact custody evidence. The checker does not validate a signature, full Mach-O correctness, installability, minimum runtime compatibility, or that a build is real. Record the selected simulator runtime and verify compatibility during the actual run.

```sh
python3 tools/release/check_ios_upgrade_inputs.py \
  --old-app /absolute/path/old/Vezuna.app --old-manifest /absolute/path/old.json \
  --current-app /absolute/path/current/Vezuna.app --current-manifest /absolute/path/current.json \
  --current-source-sha <40-character-current-commit>
```

The checker requires distinct explicit `.app` paths, `app.vezuna`, version and build fields, simulator metadata, and a thin arm64 executable declaring the iOS simulator platform. It rejects universal executables rather than guessing which slice will run. The current app also passes the existing built metadata validator, including its scene configuration. The old app need not have that configuration. Equal marketing and build versions are allowed: the historical regression uses 1.0/1. A successful invocation reports artifact and manifest digests, attested source SHAs, version, SDK and architecture, followed by `IOS_UPGRADE_INPUTS_PREFLIGHT_PASSED`. It does not inspect or print vault or key bytes. A failure is not a runtime result.

## Remaining gates

1. Obtain the real old and current artifacts with trustworthy build records. Establish exact old UI selectors and a permitted external UI driver. This preflight's synthetic header fixtures are neither signed nor installable builds.
2. After device discovery succeeds through the required device workflow, use a specifically owned disposable compatible simulator. Do not bypass the existing #106 device discovery failure through another route.
3. Install and launch the old app normally. Create and verify a uniquely named synthetic record through its UI. Capture vault relative paths, sizes and hashes without printing bytes or keys, and terminate normally to retain the scene session.
4. Install the current app over the old one on the same device without uninstalling or resetting it. Compare the saved vault files before launch. Launch normally, verify Today and the same record through accessible UI, then relaunch and repeat. Preserve bounded logs, artifact provenance and test results.
5. Demonstrate a runtime negative control against the historical scene failure if the selected OS reproduces it. Report any failure to reproduce as a limit. Physical upgrade, hardware key invalidation and locked-device access remain separate evidence.

No runtime runner or UI-test target is provided here. Preflight success does not complete #148 or replace the full project gate.

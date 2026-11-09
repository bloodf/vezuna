# Local CI checks

`scripts/check-ci-local.sh` runs the commands in `.github/workflows/manual-ci.yml` on the current checkout and adds Android host test validation and the EAS compatibility fixture. It stops at the first failure. The default `all` runs all three jobs and prints `CHECK_CI_LOCAL_PASSED` only after every stage succeeds. This is local evidence, not a replacement for required GitHub checks or the mandatory full `scripts/check-all.sh` merge gate. See [cloud build policy](CLOUD_CI.md) for automatic safety checks, manual hosted builds, deployment gating, and live-setting prerequisites. Product development remains on HOLD.

The workflow uses Ubuntu, Python 3.15, Temurin Java 25, Android SDK platform 37.0 and build tools 37.0.0, and Node 24.22.2 with pnpm 12.18.0. Install the matching prerequisites, including pnpm and the Android SDK, before running. The runner does not install system tools. Gradle may resolve its normal dependencies, and the release stage runs `pnpm install --frozen-lockfile`, which replaces this checkout's `node_modules`. A macOS pass does not establish Linux runner equivalence. CI setup actions and artifact environment are not reproduced.

Android host tests load Llamatik's `GenStream` class, which uses Java 21 bytecode (class-file version 65). Java 17 cannot run those tests even though the Android Kotlin output targets JVM 17. Use a compatible process-scoped `JAVA_HOME` for the entire Android Gradle stage, including coverage; matching the workflow's Java 25 is the default. A local Java 27 pass is separate evidence, not proof that every intervening JDK works. Record `java -version` and the effective `JAVA_HOME` with the results. Do not change global Java settings or exclude failing host tests to obtain a pass.

The standard Android coverage task graph also runs `:shared:testAndroidHostTest`. A prior filtered host pass with compatible Java does not make a later Java 17 coverage failure acceptable. For engine ownership changes, explicitly request the host task through the existing semaphore and retain its XML, including `AndroidEngineOwnershipTest`, `ModelManagerLifecycleContractTest`, and `ExportModelAndPromptTest`, alongside the full unfiltered JVM and app unit results. Match the expected test methods to the committed source and require zero failures, errors, and skips. The local results stage requires nonempty test cases for these three classes and rejects host failures, errors, skips, and inconsistent suite counters. It does not compare test method names to committed source; retain that manual comparison. These injected bridge tests do not execute native inference, and shared tests appearing on multiple targets are overlapping contracts, not additional distinct coverage.

For Gradle, set `SCRATCH` to the directory containing the existing inspected `build-slot.sh`. The runner calls it with `VEZUNA_BUILD_SLOTS=1`, daemon disabled, and a 3600-second timeout. The semaphore waits for at least 15 GB free and coordinates its slots; operators must also observe the global maximum of two heavy builds, including other projects, and one build invocation per worktree. Do not run while another invocation owns this worktree. If the semaphore or `timeout` is missing, the stage fails rather than running without it.

```bash
export SCRATCH=/path/to/existing/scratchpad
scripts/check-ci-local.sh
```

Select one stage to avoid repeating a heavy build. A selected stage reports only partial success:

```bash
scripts/check-ci-local.sh python   # seven Android Python checks
scripts/check-ci-local.sh gradle   # four workflow Gradle tasks plus the explicit Android host task
scripts/check-ci-local.sh results  # JVM, app unit, and Android host results plus debug APK ELF alignment
scripts/check-ci-local.sh website  # website frozen install, check, test, and build
scripts/check-ci-local.sh release  # pnpm install --frozen-lockfile, EAS version, compatibility fixture, iOS script syntax
```

The `results` stage reads existing outputs: it does not establish that they came from the current commit or a successful build. Run it after the local Gradle stage on the same checkout, and preserve the build and results evidence together. Assembling the instrumented test APK does not execute instrumented tests. `scripts/check-all.sh` remains a separate, broader merge gate; run it through the same semaphore and observe its required success marker. Required GitHub checks must still pass and must not be bypassed.

The release stage runs the committed EAS compatibility fixture with an external 30-second `timeout` and a 256 MB Node.js heap limit. It uses synthetic inputs and needs no credentials. These limits are not an OS network sandbox or an RSS memory limit. If `timeout` is missing, the stage fails. The fixture is an additional local check; the workflow does not currently run it.

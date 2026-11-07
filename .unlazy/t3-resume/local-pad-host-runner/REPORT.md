> Historical snapshot only. Current repository instructions and central policy supersede all operational directions below. Historical claims are not fresh acceptance. See the evidence retention map at `.unlazy/t3-resume/local-pad-host-runner/EVIDENCE_RETENTION.md` for intentional omissions and unresolved recovery proof.

# Experimental localPAD host watchdog

Authoring complete; independent review and all device/runtime gates remain HOLD. This is root-owned ignored experimental code with synthetic verification only. No self-approval or runtime acceptance is claimed.

## Scope and inputs

Only runner.py, test_runner.py, this report and synthetic evidence were created under `.unlazy/t3-resume/local-pad-host-runner/`. Existing baseline-audit.json is unchanged at SHA256 `94c5cce0e4893f90eeb5014cd91f51c7123b0d35943ed89861185e4c68d4877f`. No other author's four paths, source, refs or active outputs were modified or consumed. Ignore status was checked for the three assigned paths. No subagents, devices, adb, builds, model access, network, npm, GitHub, process termination or cleanup were used. Popen is mocked in synthetic tests.

The mandatory design hash matched. AUTHORING-CONTRACT.md SHA256 is `ce8f5c4f82152154bbeb39c33fc5265af0e14a2a04f5cd3c81d9a1c7c257cd84`. Historical files were read as text only and matched the baseline:

| Input under `.unlazy/t3-resume/` | SHA256 |
| --- | --- |
| `native-direct-boundary-evidence/physical-6e37c86/trial-runner-plan-supported-close.md` | `3230028ab19a805338ff525f0c234565247eeeede639cc2be533d17ffa1f0c7e` |
| `native-direct-boundary-evidence/physical-6e37c86/owned-trial-runner.py` | `52fe885182e0989c3b652311a428a1ad6da4b9b2ad731058d8357ab4c0c7c812` |
| `native-direct-boundary-evidence/physical-6e37c86/watchdog-close-help.txt` | `8dc1df75243b0e87430256d31716de3cd53a9d804bb077543e86b681c722861b` |
| `native-direct-boundary-evidence/physical-6e37c86/owned-trial-result.json` | `e3bda8aa59af48591b9e16851ae1be1743d79221ed1719cf6fc400bf8ef72707` |
| `review-local-pad-runtime-design.md` | `7527bf6ae86795965c0d00d8e4e12f8360457df8ecaa30e9c0aee894138306b4` |

The historical normal result completed in 6.318725 seconds without watchdog recovery. Its recorded target was a different experiment. It proves neither this controller nor recovery. Its dead bound command was never executed and is explicitly rejected by this plan validator. Prior run-as kill is prohibited, with no fallback implementation here.

## Controller behavior

`Controller(plan, adapter, monotonic=..., sleep=...).run()` is injectable. Its adapter starts asynchronous owned operations and returns nonblocking pollable handles. Blocking device I/O must execute in host children created by OwnedPopen. The controller never calls wait/communicate or waits on the instrumentation child. OwnedPopen stores its actual Popen object, signals it once, and exposes no arbitrary PID, process-group or kill API. PIPE output is refused; a later adapter must supply bounded owned evidence sinks. No executable device adapter is included or wired to the CLI.

One independent monotonic deadline starts before preflight and ends at 900 seconds. At 840 seconds the work deadline fails permanently, reserving 60 seconds for teardown within the same bound. Calls are capped at 5 seconds and by remaining global time. Identity acquisition is capped at 20 seconds. Host reaping polls at most 2 seconds within remaining time; there is no kill fallback. Scheduling delays cannot restore a deadline-expired result. These are control limits, not performance thresholds, stage allowances or model-memory budgets. Nonblocking start/poll/terminate and a functioning host scheduler are adapter obligations; this is not hard realtime or protection against a blocking in-process adapter.

Preflight requires an explicit verified manifest, lease and fresh binding, plus an independent complete absence snapshot for BOTH named packages. After one instrumentation launch, the first complete snapshot must provide the target execution UID and exact package/name/UID/PID/start identities. Missing or inconsistent identity fails closed. A failed identity/absence probe is sticky, is not retried and cannot later authorize close or lease release. Preexisting processes are unowned and prohibit launch and close.

On failure, one bounded diagnostic may run against only the captured experiment identities. It cannot replace the primary failure. Before supported close, verify the active lease, check all current package processes against captured identities, verify the lease again and obtain an immediate full identity recheck. No device operation intervenes between that final recheck and `supported_close` for ONLY app.vezuna.padtest. No stop of app.vezuna or separate stop of the test package exists. After successful close, independently enumerate both packages again; any remaining test/target process or uncertainty retains the lease. Close failure or timeout remains unresolved. No second close, retry or fallback exists. Forced termination remains failure even after confirmed absence.

Owned host children are terminated once and polled boundedly. Primary errors survive diagnostic, close, identity and host cleanup errors. `release_lease` is only a reconciliation eligibility result; the controller never releases a lease. Unresolved device or host teardown yields false. It starts no successor and queues no unload behind blocked native work.

Host exit zero sets only `transport_complete`. `semantic_acceptance` is always NOT_EVALUATED. Final exact method, markers, stage assertions, seven request identities/completions, actual JUnit status/counts and zero skips/assumptions have not been delivered. No current implementation contract is invented. Public generation/unload/sampler assertions and bounded output retention remain the instrumentation author's and final integration review's gates.

## Inert CLI and explicit inputs

The only CLI action is `plan`; `execute` is rejected. It parses explicit JSON inputs, validates boundaries and emits an inert plan without starting any subprocess. Method syntax is one fully qualified `app.vezuna.…Class#method`, with no wildcard, list or extra arguments. Runner is fixed to AndroidJUnitRunner, target/test fixed to app.vezuna.padtest and app.vezuna.padtest.test, and device serial fixed to Samsung RQGL1034B8Z.

```sh
python3 -B .unlazy/t3-resume/local-pad-host-runner/runner.py plan \
  --method "$NAMED_METHOD" --manifest "$VERIFIED_MANIFEST" \
  --lease "$EXCLUSIVE_LEASE" --binding "$FRESH_BINDING"
```

This example was not executed. No real values or evidence have been supplied. JSON schema is enforced by Plan.validate and illustrated only with explicitly SYNTHETIC fixtures in test_runner.py:

- Manifest requires serial, target, test, instrumentation_target, runner; target_uid, test_uid, app_vezuna_uid and execution_uid; owner; true verified/owned_install/pristine_install/no_shared_uid; apk_identity, installed_identity and ownership receipts. All UIDs must be positive, execution UID must equal target UID, and all three package UIDs must be distinct.
- Lease requires id, matching owner/serial, true exclusive/active, evidence receipt and expires_monotonic covering more than the entire 900-second run on this host's current monotonic clock domain.
- Binding requires source=device_open, true fresh, matching serial/lease_id, actual host_id/device_id, evidence receipt and command containing exactly one fresh absolute agent-device executable path. The historical dead binding is refused.

Receipts require a path/reference and exact SHA256. These are supplied, verified root attestations. Plan validation checks schema and consistency; it does not authenticate receipt contents, current installation or lease-service state. The adapter must independently attest the active lease and authoritative fresh process snapshots. Snapshot.complete must never be inferred from an empty failed command, a cached observation, incomplete PID listing or missing /proc access. Only the two package process identities and owned test diagnostics may be collected; no other-app logs/data are authorized. app_vezuna_uid is isolation identity metadata only.

## Package-close race and remaining preflight

Package close is not an atomic PID/UID/start-conditioned stop. A process or package can change after the immediate check and before close. Exclusive lease, no concurrent install/replacement/launch, pristine owned installation, stable device binding and exact identity are mandatory assumptions. Any observed mismatch or uncertainty prohibits close and leaves teardown unresolved. Synthetic tests cannot eliminate the residual race, prove a real snapshot mechanism, or establish that supported close stops a blocked native worker. Root and an independent reviewer must accept this limitation and the actual adapter before runtime.

Before any device launch, separately supply and verify final source/build review and integrated root gates; rebuilt app/test/APKS hashes, manifests, signing, instrumentation relation, provider authority, permissions, data separation, ABI/native hashes and model identity; actual distinct installed UIDs and execution identity; pristine ownership; complete target/test absence; fresh device_open/help-supported close binding; exclusive lease with no other session/Activity; bounded authoritative process/absence adapter and target-only diagnostics; final exact method, markers and zero-skip assertions. Cooperative cancellation has no supplied safe protocol, so none is invented; any future bounded cooperative path requires its own reviewed contract. No normal-success assertion relies on cancellation or host exit. No runtime/build/install/acquisition authorization is granted by this report.

## Synthetic verification and resources

Python 3.15.2, standard library only. Command, run from repository root:

```sh
python3 -B -m unittest discover -s .unlazy/t3-resume/local-pad-host-runner -p test_runner.py -v
```

Attempt 1: 28 tests, one failing fixture. Its process became visible before the intended missing-identity scenario; the fixture was corrected. The controller's uncertain-preflight second probe was also removed and given a regression case. Attempt 2: 31 tests passed. Attempt 3, final source: 34 tests passed in 0.081 seconds, exit 0. All raw attempt logs remain; attempts 1/2 represent intermediate source, not the final hashes below. Added final cases cover new subprocess ownership, remaining test-package process and wrong snapshot target.

The suite exercises normal completion, native hang, host hang after native exit, bounded diagnostics and snapshot/close timeout, PID reuse at the immediate close boundary, PID/UID/execution UID/start/package mismatch, unowned preexisting processes, lost lease, close failure, uncertain absence, late success at work/global deadlines, cleanup preserving primary failure, unreaped owned host, rejected missing evidence/dead binding, inert CLI and stat comm names containing spaces/parentheses. synthetic-traces.json retains ten simulated outcome/event traces. CLI help and plan-help were captured with no device invocation. Simulated names, UIDs, receipts and paths are explicitly fictional and never runtime evidence.

Initial verified free disk: 89054568448 bytes. Post-authoring report check: 86143934464 bytes. Both exceed 15,000,000,000 bytes; disk is shared and this is observation, not reservation. synthetic-after.json records the final disk observation, baseline recheck and every other owned artifact hash, including this report.

| Owned file | SHA256 |
| --- | --- |
| `baseline-audit.json` | `94c5cce0e4893f90eeb5014cd91f51c7123b0d35943ed89861185e4c68d4877f` |
| `runner.py` | `518fe29e8f1c7c3d0c2b186a4f5ccfb5387c792eb67c0278ea60ab3b9344d2eb` |
| `synthetic-before.json` | `e5fdb0c5541be531c3b03b5a835f52a63df02e2aade382d4995aa2096992cbd8` |
| `synthetic-cli-help.txt` | `7737823967d2b6dfd26bb4d9cf8d02d2502d84b655452cad4e7a85709f7bdbf0` |
| `synthetic-cli-plan-help.txt` | `e97a1c857ee9ad029aec1f9fbeef2e08716c6da6e03f098ef8501012f1b10825` |
| `synthetic-test-attempt-1.txt` | `6619eacf52ff5b1cd21e05955484aa81f2590acf81a64f429d0c4192a591ef49` |
| `synthetic-test-attempt-2.txt` | `0c4efa12e8560b709edadc58c7f2dd2b9771f4d57a755f9da79b9a659fbd9800` |
| `synthetic-test-attempt-3.txt` | `75e2097ba921b95ce68479d9a7ebf39321f6e9c03ef1c9d37cb1d2feea326cdb` |
| `synthetic-traces.json` | `4c48eece298352c46b7459fe5c60cefbe7283a55c1f8146a2d94032d90dd2c4f` |
| `test_runner.py` | `d947daa0d9d78330a170cfe73ad15f76f3f4bc432b6f225eee754d7ed95d1da8` |

Completion is synthetic authoring only. Independent review, executable adapter integration, real watchdog recovery, exact named zero-skip/runtime assertion acceptance and production/native84/distribution gates remain unrun.

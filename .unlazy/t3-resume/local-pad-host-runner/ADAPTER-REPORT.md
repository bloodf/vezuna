# Android adapter authoring report

Adapter authoring is complete. Independent review, frozen-controller approval, final artifact approval, executable/device preflight and runtime approval remain HOLD. This author does not approve the frozen controller or authorize execution. No real installation, binding, lease or runtime identity is asserted by the synthetic fixtures.

## Ownership and frozen inputs

Created only `android_adapter.py`, `adapter_worker.py`, `test_android_adapter.py`, this report and new `adapter-*` evidence under this directory. No frozen file, tracked source, ref, build output, phone lease or other author's lane was modified. All original input hashes and every entry in `root-candidate-hashes.json` were checked before authoring and after final tests. They match. `adapter-before.json` retains their exact paths and SHA256 values.

Read REPORT.md, ROOT-HOST-CHECK.md, the full SNAPSHOT-CONTRACT.md including its superseding streaming-bound update, the design review and AUTHORING-CONTRACT.md. Required design SHA256 matched `7527bf6ae86795965c0d00d8e4e12f8360457df8ecaa30e9c0aee894138306b4`. The frozen controller remains `518fe29e8f1c7c3d0c2b186a4f5ccfb5387c792eb67c0278ea60ab3b9344d2eb`; frozen snapshot remains `ef8d1b90f9c49157d523fb6c7e5e169500535decd92bbe486cddd204991a57ae`.

No subagents, device tools, adb executable, agent-device executable, models, builds, network, GitHub, settings or shared-resource cleanup were used. Executed children were local Python processes only. Device argv controls intercept the execution boundary with mocks; they do not invoke a fake adb executable. Two actual worker subprocess controls perform lease-receipt work only. The unchanged host regression tests also create and dispose of their own standard-library temporary files. New adapter controls retain their fixtures and outputs. No original receipts or other author's evidence were removed.

## Implemented behavior

`AndroidAdapter.start(operation, payload)` provides pollable `Completion` handles for lease, snapshot, instrumentation, supported close and diagnostics. Every blocking transport operation occurs in a Python worker child. Controller-side start/poll/terminate perform local bounded work and never wait for a device command. Host scheduling and local filesystem responsiveness remain assumptions, as in the frozen controller; these are not hard realtime guarantees.

The package pair is exactly `app.vezuna.padtest` and `app.vezuna.padtest.test`, on Samsung serial `RQGL1034B8Z`. Runner and exact single class#method syntax come from frozen Plan validation. No default acceptance method, generation markers, JUnit oracle or semantic PASS parser was invented. The class#method is shell-quoted for adb's remote-shell boundary, including legal Java `$` identifiers. Tests inspect both the complete host argv and the resulting remote argument value without invoking a shell or adb.

Instrumentation is one `adb -s RQGL1034B8Z shell am instrument -w -r -e class <quoted-exact-method> app.vezuna.padtest.test/androidx.test.runner.AndroidJUnitRunner`. It is never a broad suite. Close is exactly the fresh bound `agent-device` executable followed by `close app.vezuna.padtest`. No failed run-as path, adb force-stop, production-package stop, test-package stop, retry or fallback is implemented.

Snapshot and identity-only diagnostics reuse the frozen `inspect_snapshot` and `BoundedAdbReader` inside the worker. Diagnostics require captured owned identities, check them against the current complete snapshot and return only target/test identities. Unknown or changed identities fail. The frozen ps enumeration transiently sees process-list rows to establish completeness; there is no all-app logcat or unrelated app data capture. Device ps/package-manager/proc support is still unproved.

The frozen controller retains its immediate complete PID/UID/name/start check and mismatch refusal before close. The worker performs only local receipt/lease checks between that check and supported close; it inserts no device query. A new controller control demonstrates changed start identity still prohibits close. A successful close transport never establishes absence. The frozen controller must independently confirm both packages absent. Package-close identity checks remain non-atomic and depend on the exclusive lease and no concurrent launch/install/replacement.

Instrumentation command exit zero returns host transport completion with `semantic_acceptance=NOT_EVALUATED`. The execute CLI returns nonzero even after successful transport because no final semantic acceptance contract exists. Actual instrumentation status/counts, seven identified completions, zero skips/assumptions, stage assertions and clean native exit remain later gates.

## Receipt and preflight inputs

CLI defaults to an inert validated plan. Neither import nor the default CLI launches a child. Runtime construction requires `execute=True`; the CLI requires `--execute` plus explicit manifest, lease, binding, method, preflight, absolute adb path and an existing exclusively assigned evidence directory. The worker separately requires `--execute`, its exact controller parent PID and its own host session. No actual runtime command was executed in this assignment. `adapter-cli-help.txt` retains the inert CLI help.

All referenced receipts must be absolute regular files, at most 128 KiB, with matching SHA256. Symlink receipts and special files are refused. Manifest `apk_identity`, `installed_identity` and `ownership` receipt bytes are verified, not interpreted as fresh package inspection. Root must independently establish their content and provenance. Flags alone do not supply these receipts. Owner and binding host/device IDs must be actual nonempty string inputs, not booleans.

The current lease receipt must be a JSON object containing exact current `id`, `owner`, `serial`, `exclusive`, `active` and `expires_monotonic` fields matching the assigned lease, including types. It must cover the entire adapter global deadline on this host's monotonic clock. The worker checks these bytes before any transport operation and during capture/read progress. Removed, altered, mismatched, expired or inactive lease evidence fails. Deadline and termination are rechecked after the local lease read, so a slow lease read cannot restore an expired operation.

The binding receipt must contain matching `source`, `fresh`, `serial`, `lease_id`, `host_id`, `device_id` and `command`, plus `supported_close_argv` equal to the exact assigned command plus `close app.vezuna.padtest`. Historical dead binding is rejected. This is a supplied device_open/help preflight attestation, not a help probe or freshness claim generated by this adapter.

The explicit preflight JSON schema is `local-pad-adapter-preflight-v1`. Its common context fields are `serial`, `lease_id`, exact `method`, `manifest_sha256`, `binding_sha256`, `implementation_sha256` and absolute `adb`. Manifest/binding digests use the `digest()` canonical JSON helper. `implementation_sha256` is the exact four-file map returned by `implementation_hashes()` for adapter, worker, frozen runner and frozen snapshot. Path or implementation drift is refused.

Preflight also requires `review`, `final_artifact` and `lease_preflight`, each an explicit on-disk path/SHA256 receipt. Each is JSON containing the same common context, its exact `kind` and the external decision `APPROVED_FOR_ASSIGNED_RUNTIME`. This string is an input-schema decision, not an instrumentation completion marker or this author's approval. The existing bounded-authoring design approval cannot satisfy it. No real such runtime approvals were supplied or created.

SHA256 verifies receipt integrity and binding, not the truth or authority of its author. Lease freshness depends on the root lease owner maintaining the authoritative current receipt. This adapter does not authenticate a lease service, manufacture installed identity, infer authorization from flags, renew/release a lease or turn synthetic receipt contents into runtime evidence.

## Output, deadlines and owned host cleanup

Instrumentation and close stream stdout/stderr into separate exclusive evidence files with one shared 8 MiB limit, configurable downward only. The combined capture preserves at most that limit plus one detecting byte. Overflow fails instead of silently truncating or passing. Chunks are flushed to the owned sink before the next guard. Capture metadata retains exact argv, actual direct host return code, stream byte counts/SHA256, partial/overflow/error status, monotonic times and unevaluated semantic status. Timeout, nonzero exit and lease-loss controls preserve already captured output. Failure receipts do not claim uncaptured tail bytes were retained.

Worker diagnostic stdout/stderr has a separate shared 128 KiB plus one detecting-byte limit, with fixed work per controller poll. Request/result files are capped at 128 KiB. Snapshot reads retain the frozen per-command 1 MiB aggregate stdout/stderr bound plus its detecting byte. Successful decoded read payloads also share the configured 8 MiB operation budget; the last frozen-reader result can add up to 1 MiB while detecting aggregate overflow. This differs from the instrumentation stream's precise one-byte overshoot. Failed snapshot reads remain uncertainty, never complete absence.

The adapter's conservative monotonic end starts at construction, 900 seconds, before Controller.run. Instrumentation ends at 840 seconds, reserving 60 seconds within that end. Ordinary worker operations have a 2.75-second work cap. A frozen reader call is capped at 0.75 seconds, with its existing at-most 0.5-second direct host-child reap reserve. Parent worker timeout includes 0.5 seconds beyond the work cap, and TERM cleanup reserves at most 1.5 seconds of polling before host-group KILL. These fit the controller's five-second ordinary-call cap under a functioning scheduler. Late completion remains failure. No device command is retried, and no bound is extended to obtain a pass.

Each worker Popen creates its own host session. The worker catches TERM cooperatively, checks guards after bounded reader calls and explicitly terminates/kills/reaps its direct Popen command child. The parent owns only that worker session. A completed worker closes its receipt, sends a transport-ready notification and stays alive until the parent cleans the host group and reaps the worker. The worker deliberately exits by host SIGKILL during this handoff; the actual instrumentation/close command exit is separately retained. This host cleanup is not device forced termination, native exit or semantic acceptance.

The parent checks child exit without reaping using waitid/WNOWAIT and signals the owned group before reaping its pinned leader, avoiding group-ID reuse. TERM/KILL operations target exclusively that owned host group or direct Popen children, never any device PID. Signal failure is unresolved rather than silently accepted. The worker also detects controller-parent loss during work and while awaiting handoff, and self-cleans its verified own session on parent loss or the global bound. A real local descendant control demonstrates shutdown of a same-group Python descendant that ignores TERM; direct capture controls demonstrate TERM and KILL reap.

Host session cleanup covers descendants that remain in that session. Actual adb/agent-device helper behavior, including any detached host daemon/server, is unproved. Runtime preflight/review must establish supported executable paths and lifecycle behavior, including that no unauthorized new detached helper can escape this ownership mechanism. No attempt was made to start, inspect, stop or adopt an existing adb server/session. Any needed prerequisite change must be separately reviewed; this report supplies no workaround or runtime approval.

## Executed controls and retained failures

Python 3.15.2, standard library only. Final command executed from repository root:

```sh
python3 -B -m unittest discover -s .unlazy/t3-resume/local-pad-host-runner -p 'test_*.py' -v
```

Final execution: **106 tests passed, zero skips**, consisting of **53 new adapter controls plus the unchanged 53 frozen controls**, in 3.158 seconds, exit 0. These are synthetic/source-boundary and real local host-process controls only. They do not approve the controller or establish device behavior, runtime acceptance or native-process teardown.

The controls cover exact launch/close argv and remote method quoting, opt-in/inert defaults, receipt/path/hash/type/implementation drift, current and lost/expired lease, rejected binding, missing receipts, target-only diagnostics, failed/overflowed snapshots, controller identity mismatch refusal, normal capture, nonzero host exit, timeout, aggregate overflow, preserved partial data, TERM/KILL reaping, worker control overflow, nonblocking polling, receipt-ready handoff, host exit without a receipt, missing semantic acceptance, parent-loss guard and actual local descendant shutdown. No JUnit/runtime test count is implied.

All attempts and their source hashes remain:

| Evidence | Actual executed tests | Outcome |
| --- | ---: | --- |
| `adapter-tests-attempt-1.txt` | 40 adapter | 32 passed, 8 errors. Host group signal after leader exit returned EPERM; raw ResourceWarnings retained. |
| `adapter-tests-attempt-2.txt` | 48 adapter | Passed after live-leader receipt handoff and additional controls. |
| `adapter-tests-attempt-3-combined.txt` | 49 adapter + 53 frozen | 102 passed. |
| `adapter-tests-attempt-4-combined.txt` | 50 adapter + 53 frozen | 103 passed. |
| `adapter-tests-attempt-5-combined.txt` | 52 adapter + 53 frozen | 105 passed. |
| `adapter-lease-deadline-red.txt` | 1 focused adapter | Expected failure on the unchanged guard: slow lease read returned after operation expiry without refusal. |
| `adapter-tests-attempt-6-combined.txt` | 53 adapter + 53 frozen | 105 passed, 1 failed. Elapsed-time fixture declared lease loss before a delayed local child printed. |
| `adapter-tests-attempt-7-combined.txt` | 53 adapter + 53 frozen | Final 106 passed. Lease-loss control now waits for actual preserved output; its partial-output assertion remains. |

The deadline correction rechecks after lease I/O. The lost-lease control uses observed capture readiness rather than a sleep threshold; capture flushes each preserved chunk. Earlier failed runs remain failed evidence, not upgraded by the final pass. Each `adapter-controls-*` directory retains synthetic fixtures and local subprocess output; attempts have separate source-hash receipts. No blind retry or device run occurred.

## Final hashes, resources and handoff

| File | SHA256 |
| --- | --- |
| `android_adapter.py` | `ac8514daef2589975cc5c076eaf122b94c226513e90ebeb6bae514f934e08104` |
| `adapter_worker.py` | `cb913f740ce126947bbb600445543dc949693aefd9f55a54f5f19a42814d3cf2` |
| `test_android_adapter.py` | `39509ec8a3007e2f021864bad31b0a100b1520fa70c4b4d092adb56e04b5f227` |

Before authoring free disk was 81,484,800,000 bytes. After final tests/input checks it was 88,190,799,872 bytes. Both exceed 15,000,000,000 bytes. Disk is shared; this is observation, not reservation. `adapter-after.json` records the final post-report observation, unchanged-input checks and complete hash inventory of owned regular outputs, including this report. Synthetic symlink/FIFO fixture types are recorded without reading special files.

Root handoff: review these exact adapter/worker/test/report hashes and the failed/final evidence. Separately approve the frozen controller and final instrumented artifact, supply the actual strict method and semantic oracle, establish fresh executable/device/identity/absence and exclusive lease preflight, and assign any later runtime owner. This delivery grants none of those approvals. Production fit, numerical headroom, native84, clinical quality and distribution remain outside this authoring result.

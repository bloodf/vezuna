# Weight persistence and HealthKit contract, reconciled revised candidate

Refs #62 #59 #63. Document-only reconciliation. This document is not migration, design, runtime, merge or release approval.

## Exact comparison and verdict

PR187 head `a445c839bca1863b47a5a0285c82bbab263bed2d` supplies the revised proposal. PR188 head `9131f85e0ba7ee4dea046b6f56509fa3abb59cbb` preserves the original proposal. Both real PR bases are `8d13cbacb100c31378bb6efca2c24a871602ae33`, branch `main`. Remote main and the authoring checkout HEAD, separately verified at reconciliation time with `git ls-remote`, were `2e80c0f95d0da80015d6b50d5ed56eff42f88215`. PR documents were fetched with `gh api` at those exact heads; blob IDs and SHA-256 hashes are in the reconciliation receipt.

Preserve PR187's withdrawal of PR188's index-only downgrade fence, separation of provenance from authorization, and aggregate-only contract. Update its implementation status against current main. Main already implements stored envelopes, source-scoped weight reduction, transactional outcomes, Android paging, aggregate separation and replacement invalidation. Do not revert those implementations to PR188 or to PR187's former C0 recommendation. Those historical proposals are not alternate patches to apply wholesale.

The bounded source verdict is COMPLETE: the revised plan below matches the inspected main interfaces and identifies remaining acceptance decisions. Product completion remains unproven. Current source presence does not settle downgrade compatibility or provider acceptance.

## Current contract and remaining decisions

### Downgrade and schema

Main's `StoredVitals` uses storage-only `vitalsFormat = 1`, `commitId`, `revision`, `clinical` and `local`. Repository `StorageSchema.CURRENT` remains 4; portable backup versions 1–7 remain supported. These are distinct version spaces. Do not allocate repository schema 5 or backup version 8 from either historical plan.

`VitalsStore` accepts legacy clinical plaintext and the new envelope, refuses future formats, inspects previous generations before recovery/rotation, and preserves local state in legacy saves. It still uses `repository-vitals-$profileId-v1` and AAD `Vezuna vitals v1:$profileId`. New-reader refusal cannot repair distributed old readers. The historical index-only fence remains rejected: old or missing index generations, legacy snapshots, recovered profiles and already-open writers can bypass it.

Keep old-binary safety unresolved until the owner supplies an exact distributed-artifact inventory and approved support/exclusion/recovery policy. The former C0 recommendation is superseded as a description of main, because envelopes already exist. C1 requires enforceable exclusion of unsupported old writers; C2 requires a compatibility release plus an enforceable rollback policy; C3 requires a proven old-readable representation with atomic progress and old-writer invalidation. None is established by this reconciliation. Do not delete main's envelope or safety controls to force any choice. Reject a candidate that adds an index-only fence, a stale clinical mirror, or a second authoritative cursor file.

### Clinical state, provenance and atomic progress

Main persists `WeightLocalState` with clinical data in the same encrypted envelope. Provenance, unresolved IDs, continuation, access and coverage remain device-local. `WeightContinuation` distinguishes Snapshot, Replay, Steady and RebuildRequired. Portable clinical types must not acquire tokens, anchors or provenance.

`WeightChanges.reduce` validates upserts before replacement, preserves ambiguous collisions, requires matching continuity evidence for deletion, and records unresolved tombstones. The unresolved-evidence bound is the existing `MAX_UNRESOLVED_IDS = 1024`; overflow rejects rather than dropping evidence. The reducer preserves aggregates and `lastSync`. Explicit weight retention prunes provenance alongside retained weights. Missing values in a partial snapshot never authorize deletion.

PR188's instruction to erase ownership on every relink/token reset remains withdrawn. Durable provenance is distinct from a short-lived authorization context. Main's Android `WeightSourceSession` supplies in-memory evidence; cold starts and restored containers cannot establish provider-store continuity. Do not upgrade session evidence into a durable OS store identity. Main's conservative behavior resembles PR187 P0, but source behavior does not prove owner acceptance or complete legacy deletion repair. Keep #62's ambiguous historical-deletion scope open. Any P1 adoption workflow requires explicit policy and separate implementation review; global ID authority must not silently replace current collision controls.

Both repository implementations expose `readVitalsState`, `commitWeightPage`, `commitAggregateWindow` and classified `VitalsWriteOutcome`. Commits fence profile/generation and envelope revision, consume generation, and issue fresh commit IDs. Vault writes distinguish Rejected, Committed and Indeterminate. Indeterminate state blocks normal progress. Acknowledged clinical save followed by link bookkeeping failure remains committed without a usable next state. Cancellation after acknowledgement stops continuation without claiming rollback. Preserve these actual APIs rather than introducing the historical pseudocode API merely to match its spelling.

### Aggregate isolation

F3 is implemented for the transactional Android path. `DeviceVitals.ingestAggregates` operates on `AggregateWindow`, preserves weights, validates the completed window, retains the 31-day raw hourly cutoff, rolls stale stored hours into daily summaries and prunes 365-local-day aggregates. Aggregate commits copy clinical values while retaining the exact local weight state. Only a successful aggregate window advances aggregate `lastSync`. First/trailing windows remain the existing 30/seven local-day policy.

Preserve exact equality of weights and local state across aggregate refresh. Do not reintroduce mixed `VitalsBatch` replacement with empty weights. The mixed reducer still exists for legacy callers, including iOS; its existence does not negate the transactional path and its callers must be accounted for before changing it. Aggregates outside the reread window are not proven historically reconciled.

### Android continuation

The production chain is `AndroidHealthConnectImporter` implementing `WeightChangesSource`, then `DeviceVitalsSync.synchronizeOutcome`, then aggregate commit, then `WeightPaging.run`, then repository weight-page commit. Android reads aggregate steps/heart separately and maps ordered weight-only Changes. Expired payloads are discarded before mutation decoding. The permission set remains read-only steps, heart and weight; no new history/background permission is authorized here.

Main acquires a baseline before snapshot, fixes the snapshot range, chunks by seven elapsed days, requests 500 records, and limits paging to 20 counted calls or ten seconds. Those are existing algorithm limits, not measured performance or a hard provider-response memory bound. Access checks occur separately; do not claim the budget bounds every provider interaction. Snapshot page tokens normalize blank to terminal; repeated nonterminal tokens reject. Snapshot completion enters Replay; replay persists the current next token and uses `hasMore`. Invalid/expired chains rebuild without absence subtraction. For a blank Changes next token, main commits an empty RebuildRequired transition and discards that response's mutations; PR188 proposed committing valid mutations with rebuild state. Record this difference explicitly. Do not claim that proposed payload behavior is implemented or change it in this document-only task. No successful completion claim may hide unresolved evidence, blocked writes or incomplete access.

### iOS and HealthKit next implementation

Current `HealthKitImporter` remains a snapshot reader. It runs daily steps/hourly heart statistics and a body-mass `HKSampleQuery` with `HKObjectQueryNoLimit`. Its payload contains weights. `ComposeBridge.ingestHealthBatch` decodes `NativeHealthBatch`, calls legacy `repository.ingestDeviceVitals`, refreshes the dashboard and returns an error-only completion. Durable HealthKit anchored paging and an explicit native commit receipt are still absent from this production chain.

Preserve current one-shot expected context, captured zone, request invalidation, cancellation cleanup and late-callback fencing. Do not recapture a newly selected profile after a stale approval. The old claim that #93 interfaces are merely pending inspection is superseded by these integrated source references.

A later source task must introduce separate strict aggregate and anchored-weight bridge operations using the existing repository outcomes. Validate target and lifetime before each provider request and commit. Persist each body-mass page and its opaque anchor together, use only an acknowledged next state for another read, and stop on indeterminate/rejected results. An aggregate payload must reject weights, deletion IDs and anchors. A dashboard refresh error after committed save must remain a presentation failure.

Use a finite requested anchored-query sample limit and bounded invocation work, with exact SDK availability verified before adoption. Preserve a stable nil time predicate for the anchored chain and apply retention locally. Securely archive only HKQueryAnchor with secure coding and a validated size bound. Deletion-only pages must commit and continue. Only a successful empty response with a valid unchanged anchor may terminate; nonempty nonprogress responses, nil arrays, errors and invalid archives must not count as empty success. Read authorization completion and empty responses do not establish read access. Preserve values on denied, limited or locked reads; any reauthorization must invalidate old capabilities and rebuild conservatively without assuming expanded coverage. Keep the anchor encrypted in the vitals envelope, never UserDefaults or portable exports. Do not infer cross-array addition/deletion ordering; a same-ID conflict requires a specified platform-backed rule or reread before anchor advance. Invalid-anchor rebuild must preserve unknown values. Foreground-only reads and a finite sample limit do not prove complete historical deletion coverage or a total deletion-response memory cap. These remain native/provider acceptance gates, not claims established by this source plan.

### Restore and replacement

Main's `replaceVitals`/`restoreWithVitals` paths clear local continuation/provenance and aggregate `lastSync`, invalidate generation and block incomplete replacement. Preserve the existing guards for acknowledged replacement and unsupported transactional storage. Same-device portable restore also clears local state; matching IDs or values never reconstruct provenance. Keep pending replacement and uncertain-write controls through cancellation/recovery. Do not route restore through a legacy save that retains old local state.

## Assertion-source matrix

All references below are relative to current main `2e80c0f95d0da80015d6b50d5ed56eff42f88215`. Line numbers identify inspected entry points, not executed acceptance. Source manifest hashes pin the entire referenced files.

| Assertion and disposition | Exact source and production caller | Acceptance still required |
| --- | --- | --- |
| F1 index-only fence rejected; downgrade unresolved | `health/VitalsStore.kt:28,55,69,83` and key/AAD helpers; `data/VaultBackedRepository.kt:777,827,948` restore/recovery | Real old-reader/write recovery matrix, current/previous/missing/corrupt documents, legacy snapshot and active writers; owner policy |
| Envelope implemented; no schema allocation proposed | `health/StoredVitals.kt:8`; `data/StorageSchema.kt:18`; `security/BackupService.kt:182,243` | Released-format inventory and portable fixture gate |
| F2 scoped conservative reducer implemented | `health/WeightChanges.kt:87,137,172`; `composeApp/src/main/java/app/vezuna/health/HealthConnectGateway.kt:40,105` binds `WeightSourceSession` | Cold/reinstall/provider reset continuity and ambiguous legacy policy |
| Atomic outcomes and generation/revision fencing implemented | `data/VaultBackedRepository.kt:583,591,604`; `data/Repository.kt:345,355,360,366`; `health/VitalsStore.kt:99` | Crash/reload/protection failures, cancellation, receipt reuse and repository equivalence |
| F3 aggregate isolation implemented for transactional path | `domain/Vitals.kt:141`; repository `commitAggregateWindow`; `health/DeviceVitalsSync.kt:55` | Behavioral exact-weight/local equality, stale-hour rollover, omitted metrics, DST and failure cases |
| Android baseline/snapshot/replay chain implemented | `health/DeviceVitalsSync.kt:100,128,159`; `HealthConnectGateway.kt:110,112,120` | Permission restriction, token expiry/repetition, budget interruption and provider behavior |
| iOS anchor/receipt work still absent | `iosApp/Vezuna/HealthKitImporter.swift:51,78,89,153,170,183`; `iosApp/SharedBridge/ComposeBridge.kt:411` calls mixed ingest | Split bridge implementation, controlled native paging and lifecycle checks, physical provider acceptance |
| Replacement invalidates local progress | `data/VaultBackedRepository.kt:432,454,490`; `data/Repository.kt:298,318,386` | Restore interruption, portable sentinel exclusion, pending replacement and unsupported acknowledgement |

Paths beginning `health/`, `data/`, `domain/` and `security/` above are under `shared/src/commonMain/kotlin/app/vezuna/`. `HealthConnectGateway.kt` is under the full composeApp path in the provenance row.

## Exact next gates and evidence limits

The next executable gate is parent-owned independent consequential review of this corrected document and its exact source.patch hash against the pinned PR blobs and main. That review must decide whether the unresolved downgrade/provenance policy prevents the next iOS implementation slice; this author does not approve it. Revalidate main/head drift before integration. Apply only the document patch; do not import the PRs' duplicated tooling paths over current main.

After policy/review clearance, the next source slice is the iOS split aggregate/weight bridge described above, preserving the existing storage and context fences. Parent obtains a heavy-slot JSON before any build/test. Required integrated `bash scripts/check-all.sh`, security review, native lifecycle/storage checks and separate physical HealthKit/Health Connect acceptance remain unexecuted here. No gate is waived.

Existing parent's `parallel-20261106/cortex-jvm-verified/jvm-redgreen-v5/report.md` records a one-regression red/green and 56 focused JVM checks over a supplied manifest plus a two-line preservation patch. Main's `refuseFuture` checks top-level envelope fields but does not yet reject unknown fields inside `local` before permissive decoding. The captured preservation patch adds that nested-field guard. Keep this as a known source gap pending parent integration; do not describe all unknown-field refusal as complete. That captured result is context only: this worktree does not contain the parent's dirty patches and this reconciliation neither reruns nor transfers that pass to main or the document. Cortex network-dependent work remains paused.

No domain/runtime changes, tests, builds, installs, devices, network sample processing, push, merge, PR comments, deployment, lease release or subagents occurred. The only integration patch path is this document. Report, receipt, source.patch and manifest remain in this isolated worktree. No scratch, cache copies or node_modules were created. Retain the worktree until parent integration and cleanup conditions are satisfied.

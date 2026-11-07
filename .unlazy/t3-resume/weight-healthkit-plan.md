# Weight change persistence and HealthKit batching plan

> [!NOTE]
> **Historical Plan Preserved for Context Only**
> This original candidate plan (`132d15f`) is preserved for architectural history and comparison. It is **superseded** by the revised plan at [`.unlazy/t3-resume/weight-healthkit-plan-v2.md`](file:///Users/heitor/.t3/worktrees/vezuna/t3-15edeefa/.unlazy/t3-resume/weight-healthkit-plan-v2.md) (merged via PR #187).
> The proposed index-only downgrade fence (`StorageSchema 5` index barrier) was rejected/withdrawn and is not active; current `main` implements the private encrypted `StoredVitals` storage envelope (`vitalsFormat = 1`) without repository schema or backup version bumps, and separates provenance from authorization contexts. Refer to `weight-healthkit-plan-v2.md` and current `main` code for authoritative contracts.

Refs #62, #59, #63. Design candidate only. Independent consequential review is pending. No implementation, build, test execution, device boot, merge, push, or issue comment is authorized by this document.

## Scope and evidence boundary

Inspected 2026-11-04 in the T3-prepared worktree `/Users/heitor/.t3/worktrees/vezuna/t3-971ba905`, clean starting HEAD `ca90147520dacd224776edeaeb5653656ccb2cc5`. `git fetch origin` succeeded; `origin/main` remained that SHA. All proposed interfaces below are sketches, not existing APIs. Parent owns dispatch, integration, review, and release decisions.

Read HANDOFF_T3.md, .unlazy/t3-resume/PLAN.md and gates/leaf-3.md from the original checkout, global CLAUDE.md and its RTK.md include, all three named project workflow instruction files, and docs/DEVELOPMENT.md. No repository CLAUDE.md or AGENTS.md was found in the prepared tree. Applied installed architect grounding/sketch discipline, technical-writing, unslop, ADHD output guidance, and unlazy acceptance accounting. User prohibition overrides their delegation, implementation, and extra-ledger workflows. Only this file is owned; parent gate files remain untouched.

Live issue bodies were read with `gh issue view` for #62, #59, #63. Their cited code describes older implementations. Current main uses window replacement, not persistent tokens or anchors. This plan addresses retained old weights and bounded reads without claiming the old defects still exist verbatim. #63's same-window criterion is addressed by keeping first aggregate window 30 local days, subsequent trailing window 7 days bounded by the existing 30-day floor, raw heart retention 31 days, total retention 365 local days, and first weight target 365 days on both platforms. Actual readable history can differ with permission.

## Current architecture and exact pointers

Paths and line numbers in this table refer to starting HEAD unless marked preserved #93. Symbol names are the stable lookup when integration moves lines.

| Pointer | Existing contract and consequence |
| --- | --- |
| `shared/src/commonMain/kotlin/app/vezuna/domain/Vitals.kt:60` `ProfileVitals` | Serializable data shared by encrypted vitals and portable `ProfileData`; currently no cursor. |
| Same file `:89` `VitalsBatch`, `:139` `DeviceVitals.ingest` | Supports weight upserts and `deletedWeightIds`; one `replaceFrom` currently removes missing weights and replaces recent aggregate data. Incremental weight pages cannot use that replacement behavior. |
| Same file `:113` `VitalsWindow`, `:132` `DeviceVitals`, `:158` `retain` | Existing retention and rollup policy. Old hourly buckets are deliberately rejected before retention to avoid rolling them up twice. |
| `shared/src/commonMain/kotlin/app/vezuna/health/VitalsStore.kt:22,35,69` | Strict direct decode of `ProfileVitals`; one encrypted file per profile, ID index first; key `repository-vitals-$profileId-v1`, AAD `Vezuna vitals v1:$profileId`. Index is discoverability metadata, not the data transaction. |
| `shared/src/commonMain/kotlin/app/vezuna/data/Repository.kt:54,117,130` | Captured `VitalsSyncContext`, generation guard, restore clears `lastSync`; both memory and vault implementations must stay behaviorally equivalent. |
| `shared/src/commonMain/kotlin/app/vezuna/data/VaultBackedRepository.kt:402,410,416` | Save then update cache/generation then pin health link. Durable data save and later link bookkeeping are distinct failure points. |
| Same file `:518,710,771` | Restore loads vitals after profile/index; too-new repository schema is refused without fallback or quarantine. VitalsStore currently catches exceptions and quarantines, so it needs a distinct too-new path. |
| `shared/src/commonMain/kotlin/app/vezuna/data/StorageSchema.kt:19` | Repository document schema 4; migrations precede strict decoding. |
| `shared/src/commonMain/kotlin/app/vezuna/security/BackupService.kt:95,117,141,179,238` | Single/household creation, size estimate, opening and reserved backup versions. Default `Json` is strict with defaults encoded. |
| `shared/src/commonMain/kotlin/app/vezuna/domain/Models.kt:224` | `ProfileData.vitals` transports health values through backup/export. Keep local sync state outside this type. |
| `shared/src/commonMain/kotlin/app/vezuna/export/DoctorExport.kt:279` | `DoctorExportContent.deviceRows` selects clinical values, not a local persistence envelope. |
| `shared/src/commonMain/kotlin/app/vezuna/health/DeviceVitalsSync.kt:39` | Serializes one Android sync, captures context before read, commits bound result. |
| `composeApp/src/main/java/app/vezuna/health/HealthConnectGateway.kt:54,110` | Platform aggregates plus raw weights. `readWeights` retains all pages in memory and continues while token is non-null; empty string currently loops. Dependency is `connect-client:1.1.0` in composeApp/build.gradle.kts:106. |
| `iosApp/Vezuna/HealthKitImporter.swift:37,43,112,142` | Explicit authorization, parallel statistics and unbounded weight sample query, then JSON bridge persistence. |
| `shared/src/commonMain/kotlin/app/vezuna/health/NativeHealthBatch.kt:16` | Mandatory captured identity/generation/zone, currently no deletion IDs, local anchor, or page continuation response. |
| `shared/src/androidMain/kotlin/app/vezuna/security/GenerationalFileStore.kt:16,49` | Temp write and fsync, current-to-previous then temp-to-current rename; missing current reads previous. A logical encrypted blob keeps cursor and values together through recovery. Directory fsync failure is logged; source inspection is not power-loss proof. |
| `shared/src/iosMain/kotlin/app/vezuna/security/IosSecureVault.kt:70,137` | Protected atomic Foundation write. JVM vault `:32` writes bytes directly; fake/JVM success alone cannot establish mobile crash safety. |

### Preserved #93 interface inspection

Read-only inspection of `/Users/heitor/Developer/github.com/bloodf/vezuna/.worktrees/onboarding-options` found HEAD `9dd10a3b2353b4d46b441ca51d146bfbaa0a8e7f` and the preserved 19 modified files. These are a dirty snapshot, not an approved integration contract. No preserved files were changed.

- Repository.kt:116 adds `linkOnboardingHealthProfile(profileId, zone): VitalsSyncContext?` under the repository mutex. Preserve same-lock approval and the one linked profile.
- DeviceVitalsSync.kt:39 accepts `synchronize(expected: VitalsSyncContext? = null)`, validates expected profile/generation before read, and retains captured zone. Never replace it with post-authorization recapture.
- iosApp/SharedBridge/ComposeBridge.kt:187 uses `NativeActions.performHealth(context)`. At :376 `vitalsSyncWindow(expected, completion)` validates identity/generation; :410 `ingestHealthBatch` binds decoded context to commit.
- iosApp/Vezuna/HealthKitImporter.swift:23 stores `expected` and `requiresExpected`; :43 rejects consumed expected state, clears it with `defer`, and reads through the bound bridge window. Keep one-shot sheet lifetime and cancellation cleanup.

After #93 lands, re-read its exact committed result. New page commits increment generation, so returning the next context from the same commit is necessary. Recapturing a fresh context between pages could import into a new profile, hide A-to-B-to-A relinks, or weaken #93. Keep normal Connections and onboarding authorization entry points distinct.

## Compatibility inventory before selection

Treat every previously used number as reserved whether or not a public store release is proven. `git tag` and `git ls-remote --tags origin` returned no tags. docs/RELEASING.md records sealed iOS build 1.0/1 at `56e5de0f6f12`; this audit did not inspect binaries, store state, or release manifests. Therefore exact shipped-build-to-schema mapping remains UNKNOWN. This is a release verification requirement, not permission to recycle a number.

| Version space | Reserved numbers and source history | Compatibility rule |
| --- | --- | --- |
| Repository documents | 1 unstamped legacy; 2 introduced by `2551e64`; 3 health records/document lists in health-records history; 4 appointments at `389dd16` | `StorageSchema.CURRENT=4`. Reserve 1–4. Proposed next is 5, recheck all concurrent branches before implementation. |
| Portable backup payload | 1 legacy single profile; 2 vitals; 3 final single profile; 4 household; 5 vault documents/health records, `67e8597`; 6 appointments, `389dd16`; 7 omissions, `b638090`/`62796c8` | Reader accepts 1–7. No cursor-related bump or field. Do not reuse 5–7 as a generic new backup format. 8 is unassigned here and remains unsupported. |
| Vitals plaintext | Unstamped old `samples/dailySummaries`, then aggregate `ProfileVitals` in `31e777e`; current `-v1` key/AAD introduced with the separate store | `-v1` is a key/AAD namespace, not repository schema 1. Existing direct strict readers reject extra fields. |
| Cipher and portable wrapping | `CipherEnvelopeCodec`, existing AADs, passphrase codec, legacy backup AAD `Vezuna backup v1:` | No algorithm, key identity, AAD, or portable wrapper version change proposed. |

Strict compatibility matrix required in implementation: read legacy vitals and repository 1–4 with cursor absent; write the chosen local format only after downgrade fence; refuse future local version without quarantine or rewrite; open backups 1–7 as before; reject backup 8 as unsupported. Test with defaults encoded and with optional fields missing. Do not globally set `ignoreUnknownKeys=true` to conceal format changes.

### Alternatives and provisional selection

| Alternative | Assessment |
| --- | --- |
| Add `cursor` directly to `ProfileVitals` | Fewer lines but every serializer is affected, older direct readers quarantine it, and default encoding can put cursor fields into backups. Rejected. |
| Separate encrypted cursor file | Cannot atomically bind cursor advancement to data, duplicates old failure boundary. Rejected. |
| Private `StoredVitals` envelope holding existing `ProfileVitals` plus local state in the same encrypted blob | Chosen provisionally. Keeps portable clinical shape unchanged and makes one write the data/cursor transaction. Needs explicit downgrade fence and migration tests. |

Smallest compatible proposal: private storage-only `StoredVitals(schemaVersion=5, data: ProfileVitals, weightSync: WeightSyncState?=null)`. Reuse the vitals key and AAD; they identify the profile/file rather than plaintext layout. Read old top-level `ProfileVitals` only when no envelope discriminator exists, strictly decode/migrate it, and use null local state. No new field in `ProfileData` or `ProfileVitals`. No separate cursor store. Centralize the local version constant with repository schema 5, not a newly invented conflicting version 1.

Before the first envelope write, durably stamp the authoritative repository index at schema 5 under the repository mutex. All later index writers must stamp at least 5. If that fence fails, write no new-format vitals. An interrupted fence with old vitals is safe for the new reader and intentionally blocks downgrade. A released schema-4 reader must stop at `SCHEMA_NEWER` before opening the new vitals file. Add 4-to-5 repository migration with no clinical changes. New VitalsStore must recognize a newer envelope before its generic corruption catch and propagate `SCHEMA_NEWER`, preserving ciphertext and preventing fallback to embedded older values.

This fence adds a deliberate downgrade restriction. Parent must approve it independently and map actual released readers before selecting it for implementation. Test the real older reader with current index, previous index, interrupted index writes, and corrupt index paths. If any supported reader bypasses the fence, STOP and revise the storage compatibility design. Do not silently ship a parallel `v2` vitals key with stale `v1` data, overwrite an old-readable mirror, or weaken strict decoding. Index corruption plus fallback to an old schema must not cause an older reader to quarantine the new vitals envelope; downgrade/fallback compatibility is an explicit unresolved proof obligation.

*(Reconciliation note: This proposed index-only fence was withdrawn in PR #187 / `weight-healthkit-plan-v2.md` F1 because older readers with corrupt/missing indices or generational fallbacks bypass index-level checks and reach `VitalsStore.load` directly. `main` maintains `StorageSchema.CURRENT = 4` and isolates local sync metadata inside `StoredVitals(vitalsFormat = 1)`).*

`WeightSyncState` is a tagged Android or iOS state, not two nullable unrelated token strings. Persist provider, query-policy version, locally generated installation/health-connection epoch, and opaque continuation. Android rebuild also records fixed range/chunk progress and phase. iOS stores a securely archived anchor and query predicate identity. Local source-owned weight IDs are needed where restored/imported values cannot be proven to originate in this connection; portable values remain untouched by absence reconciliation. Track ownership in this envelope, prune it with retained weights, and clear it with local state. Tokens, anchors, state and IDs never enter logs or backup metadata. Existing legacy or restored IDs begin with unknown ownership; a positively read upsert in the current connection can establish ownership. Do not classify every old ID as current-device data on migration.

State variants are `AndroidRebuild(baselineToken, fixedStart, fixedEnd, chunkStart, chunkEnd, pageToken, revision)`, `AndroidChanges(token, revision)`, and `IosWeight(anchorArchive, predicatePolicy, revision)`, each scoped to provider/connection epoch. Null means no continuation. Raw-page token is nullable and normalizes empty to null. Persist rebuild phase explicitly; a baseline token alone never means the historical snapshot completed. A permission or connection change invalidates progress instead of extending its certified coverage.

## Android weight-only Changes API

The official [synchronization guide](https://developer.android.com/health-and-fitness/health-connect/sync-data) supports type-specific change tokens, explicit expiry responses, and ID-only deletion events. Scope tokens to `setOf(WeightRecord::class)` with the same origin filter throughout a connection. Do not consume step or heart tokens. Reuse aggregate reads and retention, without claiming historical aggregate deletion repair.

### API sketch and commit ownership

Proposed caller usage is `capture bound session -> read weight page -> commit page and continuation -> continue with returned session`. A page commit returns `CommitReceipt(nextContext, storedCount, continuation)` only after durable save. Sketch internal types:

```kotlin
// Design signatures only. No implementation in this task.
sealed interface WeightMutation {
    data class Upsert(val reading: WeightReading) : WeightMutation
    data class Delete(val recordId: String) : WeightMutation
}
data class WeightPage(val mutations: List<WeightMutation>, val next: WeightSyncState)
suspend fun captureWeightSession(expected: VitalsSyncContext?): WeightSession
suspend fun commitWeightPage(session: WeightSession, page: WeightPage, now: Instant): CommitReceipt
```

Repository owns context validation, provider/connection match, expected cursor comparison, mutation fold, retention, encrypted save, cache publication, generation increment, and receipt. Both repository implementations implement the same semantics. Source only reads/maps platform values. Swift and Android never persist continuations independently. Returning a receipt does not relax one-shot onboarding authorization; an internal session can continue only the originally approved transaction.

Do not append all upserts followed by all deletions. Preserve provider event order, including repeated IDs, across and within pages. Delete-then-upsert retains the final value; upsert-then-delete removes it. Reject unexpected record types. Invalid values count as skipped with no prior-value deletion; unknown deletion IDs are harmless. An upsert moved outside retention removes the old retained version by ID before discarding the new out-of-range value.

Separate weight merge semantics from aggregate window replacement. A weight-only page must never remove weights absent from that page or erase steps/heart values. `MERGE_ONLY` is useful for isolated deltas but does not make a mixed batch safe. Add an explicit weight merge/rebuild mode or use the dedicated commit above; leave aggregate `replaceFrom` behavior intact. Do not advance aggregate `lastSync` on a weight page, because a failure before aggregate reads must retry their original window. Successful aggregate synchronization advances its own existing timestamp; the weight continuation is independent.

### Initial read and expired-token rebuild

1. Capture #93-compatible expected profile/generation/zone and local state under the same mutex. Validate again before every platform request and commit. Unavailable provider, revoked permissions, cancellation, or stale context produces no page/cursor write.
2. With no token, or `changesTokenExpired=true`, abandon that expired chain without applying its payload. Obtain a new weight token BEFORE the snapshot. Fix end time and retention start from captured now/zone. Changes during snapshot are subsequently replayed. Restart an expired rebuild with a new token; never promote an incomplete snapshot to complete.
3. Enumerate retained weight history in bounded time chunks and record pages. Propose 7-day chunks, 500 records per requested page, and at most 20 provider calls or 10 seconds per invocation, whichever comes first. These are policy bounds, not SDK limits or measured performance. Commit each upsert page with its rebuild progress and baseline token in the SAME envelope. Resume later from committed progress; if a record page token becomes invalid, restart the current chunk and dedupe by ID.
4. A null OR empty raw-record page token ends that chunk. Do not request `readRecords` with an empty token. Detect repeated nonterminal tokens. Budget exhaustion means partial progress, never successful completion. Token expiry during chunk progress restarts baseline acquisition safely; prior upserts can be repeated.
5. After all accessible snapshot chunks complete, replay Changes from the pre-snapshot token in order. For each response, check expiry first; persist mutations with `nextChangesToken` and continue while `hasMore`. Empty Changes arrays do not imply completion when `hasMore=true`. For Changes, an empty next token is NOT a usable steady-state cursor: commit valid mutations with state requiring rebuild, end this invocation, report incomplete, and never call Changes with empty string. Repeated token with `hasMore=true` fails without claiming drain. A nonempty next token with `hasMore=false` is saved for the next sync, including no-change responses.

A rebuild uses bounded memory per page; it must not accumulate the whole retained history or all change pages. Snapshot progress and baseline token are local state in the same data write, so restart does not skip an uncommitted page. Compare the stored cursor/rebuild revision before commit as well as generation; duplicate concurrent importers cannot regress cursor state.

### Access and deletion rules

The official [raw-data guide](https://developer.android.com/health-and-fitness/health-connect/read-data#read-data-older-than-30-days) describes the access floor as 30 days BEFORE FIRST permission grant, including a new boundary after reinstall. It is not a rolling now-minus-30 cutoff. This work requests neither history nor background permission.

Desired rebuild range is the existing 365-local-day retention interval intersected with ACTUAL readable history. Do not invent a first-grant timestamp on upgrade or infer it from earliest returned data. Begin with conservative bounded queries, distinguish access errors from successful complete ranges, and retain unknown history. If the installed SDK/provider cannot establish complete visibility for a chunk, process visible upserts only and mark deletion coverage unknown. Permission checks alone do not certify a full interval. On a fresh known permission flow, a local observed grant time may provide a conservative usable lower bound; it does not reconstruct older grants or survive restore.

Apply explicit deletion IDs from valid type-specific Changes pages to weights owned by that connection. After expiry, missing data means deletion ONLY for a proven-complete accessible chunk, with unchanged permission/connection, completed pagination, matching source ownership, and successful replay. Never treat exception, partial page, clipped range, restored IDs, unknown first grant, access loss, or empty inaccessible query as deletion. Smallest safe first implementation uses upserts plus explicit tombstones and preserves uncertain missing weights. If full expiry deletion repair is required, the proof of interval visibility and source ownership is a blocking design gate. Report that limitation instead of claiming old deletions are all healed.

Retention is an independent local policy, so weights past 365 local days can expire even when platform access is lost. This is not evidence of platform deletion. No platform writes or deletions are proposed.

## Atomicity, lifecycle and portable boundaries

The transaction unit is one encrypted `StoredVitals` replacement containing data, ownership and continuation. The repository mutex protects read/validate/fold/save/publish. Do not hold the mutex across platform reads. Save failure leaves the old data AND old continuation authoritative; no success callback or cache publication. On process death after durable write but before acknowledgement, restart reads both new values and cursor. If the caller cannot determine whether a write committed, invalidate the in-memory session and reload the encrypted state before another commit. Do not reuse a stale cache.

Link pinning currently follows the durable save. Preserve one-target safety and handle its failure explicitly: return a committed result with bookkeeping failure or force repository reload; never pretend the data save rolled back. Onboarding's explicit link is already pinned before its authorized import. Ordinary default-link import still needs same-lock link validation. Cancellation before commit writes nothing; cancellation after commit cannot promise rollback. UI success counts durable retained values, not merely fetched samples or delete events.

Relink, A-to-B-to-A, profile deletion/recreation, restore/replacement, install/connection change, predicate-policy change, or invalid anchor/token invalidates the associated continuation. Clear a target's local state before reusing it under a different connection; never reuse old profile A's token after relinking back to A. Keep existing generation and captured-zone protection. Retention bounds progress-state size; prune stale ownership IDs. No cursor contains permission authorization and no token permits bypassing the user's link choice.

Portable boundary is structural: `StoredVitals` never becomes `ProfileData`. `readProfile`/`peekProfile` return only `.data`; backups, fixedOverhead estimates, previews and exports see that same clinical shape. Verify all single/household, omission and legacy backup paths. On restore, clear local cursor/anchor, rebuild phase, ownership and connection identity in the SAME vitals replacement, and clear `lastSync` as today. Apply this at the repository restore/replace boundary, including direct `replaceVitals` callers, not only the UI helper. Backup opening returns clinical data only and never accepts a token as an instruction. Unknown fields still fail strict decoding; do not add a permissive cursor import path. Same-device backups are also portable-boundary data, not cursor snapshots.

Backup versions remain 1–7 and byte shapes remain unchanged because `ProfileVitals` is unchanged. A synthetic sentinel token must be absent from decrypted single/household/omission payloads, estimates, JSON/CSV/PDF exports, previews, filenames and logs. Backup restore on the same or a different platform starts local reconciliation from null state and cannot delete restored weights by unproven absence.

## HealthKit weight batching and deletion reconciliation

Use one-shot finite-limit `.bodyMass` anchored queries, initially nil anchor, then the returned anchor. Remove the weight `HKObjectQueryNoLimit` path. Apple's [anchored-query contract](https://developer.apple.com/documentation/healthkit/hkanchoredobjectquery) supplies additions and deleted objects; [HKDeletedObject](https://developer.apple.com/documentation/healthkit/hkdeletedobject) identifies deletions by UUID and warns tombstones are temporary. Do not claim complete deletion recovery after an arbitrary offline gap. Background observation is a separate entitlement/lifecycle scope and still cannot justify inferring read permission.

Use a stable body-mass-only predicate for a cursor epoch. Prefer nil time predicate and filter to local 365-day retention during mapping. This prevents a moving retention predicate from invalidating anchor interpretation, but may scan much older history. Limit each query to 500 samples and each invocation to 20 calls or 10 seconds. Persist each acknowledged page and anchor before reading the next, so a large initial history remains resumable with bounded retained memory. Query `limit` is documented in samples, not as a hard cap on deletion objects or total response bytes. Hard deletion-array memory bounds are not proven by that parameter; inspect actual SDK behavior and document the residual provider allocation risk.

Stop only after BOTH additions and deletions are empty on a successful response. Do not stop because one array is empty, or because additions are fewer than the limit. Deletion-only and additions-only pages must continue. Error/nil result is failure, never empty success. Require a valid new anchor and detect nonprogress. Securely archive with `NSKeyedArchiver` requiring secure coding, unarchive only `HKQueryAnchor`, bound archive size, and persist opaque bytes inside the encrypted local envelope. [HKQueryAnchor](https://developer.apple.com/documentation/healthkit/hkqueryanchor) supports secure coding. No UserDefaults or separate Swift anchor file.

Swift sends weight mutations, candidate anchor, original bound session identity, expected previous continuation revision, and page mode through an internal bridge. Kotlin validates and commits them together, then returns receipt with the next authorized context to Swift. Preserve captured zone and #93 one-shot expected context. A failed bridge save does not update Swift's authoritative anchor. Cancel/stop in-flight query on task or sheet cancellation, resume its continuation at most once, discard late callbacks, and do not start another page after session invalidation. Validate archived-anchor decoding before issuing a query.

The official [authorization guide](https://developer.apple.com/documentation/healthkit/authorizing-access-to-health-data) says read denial is intentionally indistinguishable from some empty results. Current docs also describe limited-history access and an earliest-authorized-date API. Verify installed Xcode SDK symbol and OS availability before using that newer API; this plan does not assume availability at deployment target iOS 18.5. Neither authorization completion nor `authorizationStatus(for:)` proves read access. Never reconcile weights by subtracting IDs missing from a HealthKit query. Explicit returned tombstones are the deletion signal; empty, denied, limited, locked, reset or failed reads preserve existing retained weights. Invalid/reset anchor triggers a bounded nil-anchor merge rebuild, not a wipe. Lost tombstones remain an honestly reported reconciliation gap.

### Protect aggregate rollups

#59 weight pages do not carry steps or heart statistics. They cannot call the existing mixed replacement path with an empty recent aggregate window. #63 retains `HKStatisticsCollectionQuery` daily cumulative steps and hourly min/mean/max heart summaries for the existing recent window, then applies that window once. No raw all-history heart query is introduced. Fix each query's end time at session start and use captured timezone/calendar for parity.

Keep `DeviceVitals.ingest`'s `start >= now - RAW_DAYS` check. Replayed hourly buckets older than 31 days must never enter `retain`, which would add them to existing `heartRateDaily` again. An old step/heart deletion is not repaired by weight tokens or anchors. A future aggregate-reconciliation task must recompute and REPLACE affected daily summaries from complete platform statistics, with coverage proof, rather than adding old hourly summaries. Days crossing the raw cutoff need one ownership rule so hourly and daily contributions are disjoint. That broader historical aggregate correction is not selected here and #59/#63 must not be reported fully closed on weight evidence alone.

## Behavioral acceptance cases for later implementation

These are required oracles, NOT executed results. Use synthetic data and public repository calls; inject failures at durable boundaries. Parent must approve the design, then dispatch implementation and independent review.

### Storage and compatibility

| Case | Required observable outcome |
| --- | --- |
| Old unstamped vitals and repository 1–4 fixtures | Clinical values survive exact decode/migration; no continuation appears until a successful import. |
| Future local schema | Opens recovery as `SCHEMA_NEWER`; identical ciphertext preserved; no quarantine, embedded-data fallback or overwrite. |
| Older schema-4 app opens migrated vault | Refuses at index before loading vitals. Exercise current/previous/missing/corrupt index and interrupted fence writes. Any destructive fallback blocks schema selection. |
| Envelope save fails before commit | Restart exposes original values and original token/anchor, retries same page and stores once. |
| Crash after commit before acknowledgement | Restart exposes new values plus new continuation; no data-only or cursor-only state. |
| Pin-link failure after data commit | Receipt/reload reflects durable page; stale cache cannot regress cursor or import into another profile. |
| Backups 1–7 and unsupported 8 | Existing accepted shapes remain accepted; 8 remains unsupported. Sentinel cursor absent from all decrypted portable/export outputs. |
| Restore same profile, cross-platform restore, direct replace | Clinical weights retained; local cursor, ownership and `lastSync` cleared; late pre-restore page rejected. |

### Android

| Case | Required observable outcome |
| --- | --- |
| Old retained weight edited/deleted outside trailing 7 days | Explicit Changes upsert/delete updates by ID; unrelated weights and aggregates survive. |
| Delete then upsert; upsert then delete; duplicate replay | Final state follows event order; replay produces identical values without duplicate IDs. |
| Expired response carrying misleading payload | Ignores expired page and begins token-before-snapshot rebuild; never loops forever on expired token. |
| Multiple Changes pages including empty `hasMore=true` | Commits every page and keeps reading to `hasMore=false`; uses final usable token next time. |
| Raw pagination `null` or empty terminal | Does not call raw read again; repeated nonterminal token fails boundedly. Changes empty continuation ends incomplete with rebuild state. |
| Mutation while snapshot is paged | Pre-snapshot token replay supplies final update/delete, no lost concurrent record. |
| Unknown first grant or permission revoked mid-rebuild | Keeps inaccessible existing weights; no absence deletion; reports unknown coverage. |
| Permission initially granted months ago | Rebuild target is retained year intersected with actual grant-based access, never arbitrarily now minus 30 days. |
| Chunk/page budget exceeded, interruption and resume | Memory bounded to page plus retained data; resumes committed progress, no full-success marker. |
| Weight save succeeds, aggregate read fails | Aggregate `lastSync` stays unchanged and recent aggregate window retries. |
| Invalid upsert or updated weight moved outside retention | Invalid value does not erase prior value; valid out-of-retention move removes stale old retained version. |

### HealthKit and shared concurrency

| Case | Required observable outcome |
| --- | --- |
| 1,201 weights at batch size 500; equal sample timestamps | All IDs eventually stored once using anchors, no timestamp-offset loss or all-history materialization. |
| Addition-only, deletion-only, mixed, then both-empty pages | Continues each nonempty page, applies UUID deletions, terminates only at both-empty success. |
| Failure persisting page 2 | Only page 1's data/anchor durable; retry page 2 loses nothing; Swift does not advance its own cursor. |
| Denied/limited/locked empty read | Previously retained weights remain; no fabricated deletion or permission-success claim. |
| Invalid archive, reset store, unavailable API | Bounded merge rebuild or explicit failure; no destructive wipe or guessed API availability. |
| Same old hourly data replayed twice | Heart daily min/mean/max/count unchanged; old hourly data rejected, no rollup double-count. |
| Weight pages interleaved with one aggregate refresh | Weight page does not erase recent aggregates; aggregate refresh does not erase unseen incremental weights. |
| A-to-B, A-to-B-to-A, default shift, delete/recreate, restore | Pre-read and precommit fences reject stale session, do not recapture new authorization. |
| Cancellation before commit or late native callback | No stale write; only owned query stopped; exactly one completion. After durable commit, report committed state honestly. |
| Timezone/DST changes during page sequence | All pages use original captured zone, fixed retention boundary and aggregate window; next session may use new zone. |

Add negative mutation evidence after implementation: advance continuation before save, remove generation guard, interpret empty as deletion, stop on only one empty HealthKit array, remove old-hour cutoff, and retain cursor on restore. Each mutation must fail its behavioral oracle, then be restored before committing. Compiler failures are not behavioral failures.

## Phased ownership after #93

| Phase | Proposed single owner scope | Exit condition |
| --- | --- | --- |
| 0, parent | #93 exact integrated candidate and final interfaces; release/schema inventory; consequential design review | Independent approval of schema choice and explicit limitations, actual released reader matrix recorded. No concurrent repository/bridge authors. |
| 1, persistence owner | StorageSchema.kt, VitalsStore.kt, both Repository implementations, compatibility and fault tests, portable-boundary tests | Atomic envelope, downgrade safety, restore clearing and no cursor serialization proven locally. No platform token reads yet. |
| 2, Android owner after phase 1 | HealthConnectGateway.kt, DeviceVitalsSync.kt, weight reducer/session tests and Android mapping tests | Expiry, pagination, bounded rebuild and access uncertainty cases pass; no all-type token regression. |
| 3, iOS owner after shared API settles | HealthKitImporter.swift, NativeHealthBatch.kt, ComposeBridge.kt, HealthKit fake-query and native tests | Bounded body-mass additions/deletions and durable receipt flow; #93 lifetime retained; real permission behavior remains separate. |
| 4, parent | Integrated exact commit, independent security/data-integrity review, standard local gate and authorized device proof | Current-main ancestry, fresh review and full gate evidence. Device/provider gaps remain listed; no automatic issue closure. |

Each phase receives a fresh prepared isolated worktree and exact ownership from parent. No parallel edits to Repository, VitalsStore, NativeHealthBatch or bridge while #93 or another phase owns them. No dependency upgrade, Health Connect history/background permission, HealthKit background-delivery entitlement, UI redesign, or platform health write is part of this plan.

## Decisions and unresolved questions

1. Release/schema evidence is incomplete. Which actual distributed builds and previous-generation fallbacks must support downgrade? All known numbers remain reserved. The proposed index fence must pass real old-reader tests before approval.
2. Android expiry absence-deletion safety requires observable complete access and ownership. Does the installed 1.1.0/provider combination provide enough evidence? If not, accept upsert plus explicit tombstone reconciliation with unknown old deletion coverage, or keep that requirement blocked.
3. HealthKit finite limit does not document a total deletion-response memory cap. Inspect SDK and controlled fixture behavior before claiming hard memory bounds. New limited-history APIs need SDK/OS availability verification.
4. Foreground-only HealthKit can miss tombstones removed by the OS. A requirement for guaranteed historical deletions needs separately approved background lifecycle work and further evidence; it cannot be solved by treating absence as deletion.
5. Page budgets and connection identity are proposed policy. Validate UI partial-progress behavior, atomic link bookkeeping failure, cursor invalidation on ordinary relink, and archive-size bounds in the implementation review. Do not prompt permission again automatically to finish a backgrounded batch.

## Owned inventory and handback evidence

Before work: prepared tree clean at `ca90147520dacd224776edeaeb5653656ccb2cc5`; `df -k .` available 181,954,724 KiB, about 173.5 GiB. Inspected scratch build-slot.sh read-only. No build invoked, so no slot acquired. Global process/device enumeration was unnecessary for this no-build/no-device task.

Exact owned output inventory: this Markdown file only. Owned generated build output: none. Owned devices, emulator sessions, browser tabs, servers, temporary SDK downloads and scratch logs: none. No cleanup candidates created. Git fetch updates shared remote refs only; no branch deletion or original worktree modification. Evidence is retained in this document and the tool transcript. Keep candidate worktree until parent integrates; never remove a branch.

After document creation, `df -k .` available was 181,210,024 KiB, about 172.8 GiB. Machine-wide change is not attributed to this document. Final document scope check, commit SHA and latest after-disk receipt are supplied in the handback. G1 document/scope and G4 inventory have author evidence; G2 has source/doc inspection only, with no runtime or behavioral pass claimed. G3 independent review remains PENDING. Parent-owned ledger is unchanged. This candidate is not self-approved.

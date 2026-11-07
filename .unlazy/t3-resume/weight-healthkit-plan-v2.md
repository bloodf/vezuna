# Weight persistence and HealthKit contract, revised candidate

Refs #62 #59 #63. Design author submission only. NOT APPROVED. No implementation authorization or runtime success claim.

## Status and exact evidence

This document replaces the proposals identified below, without modifying the original candidate. All three findings in `review-weight-plan.md` block implementation. F1 and F2 require owner policy decisions and independent review. F3 has a corrected proposed interface, not an implemented fix.

Read the original checkout's `HANDOFF_T3.md`, `.unlazy/t3-resume/PLAN.md` through revision 7, candidate `132d15f66b3aaa17dcb241ca8849036266a9ddae`, and the independent review. The exact candidate blob SHA-256 is `b797cc74191b54413053428fc136013b2071eb29032e14a64501ba5b7a2d542b`. Source traces below use baseline `ca90147520dacd224776edeaeb5653656ccb2cc5`. The prepared author tree started clean on `t3/revise-weight-healthkit-plan`. No source or original-worktree files were changed.

Read selected integration interfaces at #93 candidate `6987e54228f7296454622d46b2bb99e442582c01`. That candidate remains unapproved. Its expected-context validation, one-shot native request lifetime, and invalidation are constraints to preserve, not approval inherited by this design. Re-read its final integrated commit before settling signatures.

Applied installed architect/how grounding and interface sketches, technical-writing, unslop, and ADHD output guidance. The direct no-subagents, one-file, document-only instruction overrides skill delegation, extra artifacts, and implementation phases. No skills were installed. The architectural trace and alternative analysis are recorded here.

## F1: withdraw the index-only fence

The schema-5 index stamp at candidate lines 67–73 is withdrawn. Reusing `repository-vitals-P-v1` for an envelope that the old strict `ProfileVitals` reader cannot decode is unsafe under the proposed fence. A new reader's too-new exception cannot change an already distributed old reader's broad quarantine catch.

### Recovery routes in the actual baseline reader

`VaultBackedRepository.restore:518–603` reads deleted-profile metadata first, selection/link, then index, profiles, vitals, repair writes, and the vitals sweep. `readDocument:710–753` distinguishes too-new from corruption. `VitalsStore.load:22–32` does not. These different boundaries matter.

| Entry or fault | Route to values or mutation | Why an index-only barrier fails |
| --- | --- | --- |
| Healthy current schema-5 index | `parseIndex` calls `StorageSchema.migrate`; too-new propagates and stops restore. | This one path refuses. It proves nothing about the others. |
| Corrupt current index, readable schema-4 previous index | `readDocument` calls `readPrevious`, quarantines the corrupt current index, accepts previous, sets recovery flag, reads listed profiles and recovered orphans. | A readable old profile reaches `VitalsStore.load`; new plaintext is quarantined, returns null, and embedded profile vitals can be saved over the active key. |
| Missing current index on Android | `GenerationalFileStore.read:49` silently serves `.prev`. A schema-4 previous index is accepted. | The repository recovery flag need not be set. Listed profiles still reach the same unsafe vitals load. |
| Listed profile current missing/corrupt | Android can silently serve previous; corruption can invoke repository `readPrevious`. | An old readable profile generation can reach the new vitals even if a current profile was separately stamped. A too-new current profile does stop, but a surviving old generation bypasses it. |
| Both index generations absent, legacy snapshot present | `readDocument(LEGACY_KEY)` then `migrateLegacy:697`, which persists embedded profiles, link, and index; the profile loop then loads vitals. Legacy snapshots have no `StorageSchema.migrate` barrier in `parseLegacy`. | Merely stamping current index/profile documents does not fence this route. Migration itself makes writes before vitals load. Legacy current/previous and Android silent fallback must all be inventoried. |
| Both index generations absent, no legacy snapshot | Empty listed set; normal orphan enumeration is not invoked. Restore still runs the vitals sweep. | Do not invent an immediate vitals decode here. Surviving profile files can preserve vitals through `keep`; missing profile files, tombstones, or later profile creation/restore can instead delete or replace them. Preservation is not guaranteed by an empty open. |
| Explicit previous-index recovery with unlisted P | `orphanIds:611` combines vitals-index IDs, key listing, and stored AAD candidates; `readOrphan` reads current/previous profile. | Every accepted old profile can reach vitals. Too-new profile errors propagate, but a readable old generation bypasses the proposed index fence. |
| Deleted metadata readable | Listed deleted IDs are skipped when index is recovered; orphan candidates exclude deleted IDs; sweep deletes vitals for deleted IDs. | Deleted metadata affects reachability and deletion. It is not a universal barrier. Stale deletion evidence must not destroy a migrated active file. |
| Deleted metadata missing, corrupt, previous, or reset | Missing means empty; corrupt may mean unknown; previous/reset suppress some recovery. `withVitals` candidates remain possible under the documented restrictions. A too-new deleted document stops restore. | Absence or corruption cannot be treated as universal refusal. This includes silent previous-file delivery on Android, which may not expose the repository recovery flag. |
| Vitals file missing/corrupt/current or previous | Old `VitalsStore.load` directly decrypts and strictly decodes. Android may supply previous bytes for a missing current. Generic errors quarantine current and return null. | It has no schema-aware refusal. `stored ?: data.vitals...save` can repopulate old embedded values; sweep/delete can also remove files. |
| Future create, restore, repair or save in an old process | The old binary can write schema-4 index/profiles and old-format vitals after opening a recovered state. | Migration must consider old writers and already-running processes, not just cold opens. An app-level mutex in the new process does not exclude them. |

On iOS, missing current files do not gain Android's silent `.prev` behavior from this file store. The repository's explicit previous-document path and legacy handling still need platform-specific fixtures. No universal claim about absent-index orphan recovery is made.

### Why a wider marker is not selected

Stamping current and previous indexes, profiles, and deleted metadata could block some routes while all those files remain readable. It is not a proven compatible migration. Each replacement has its own crash point, retained old generations may reappear, legacy parsing has a different contract, and missing barriers permit later old writes. A marker in a new key is invisible to old binaries. A schema field inside vitals reaches the quarantine catch too late. Altering cipher metadata, AAD, or app keys makes decryption fail and reaches the same catch. The binary cipher codec treats remaining bytes as ciphertext; it has no ignored authenticated extension slot for an encrypted cursor. Smuggling state into clinical samples, summaries, IDs, or timestamps changes their meaning and portable outputs.

Under the required immutable old-reader recovery behavior and preservation constraints, this author has no sound proof for an in-place incompatible vitals envelope. This is a bounded conclusion about the inspected contracts, not a proof that every future storage redesign is impossible.

### Concrete compatibility choices for the owner

| Choice | Concrete action and consequence | Required decision and evidence |
| --- | --- | --- |
| C0, recommended now | Defer durable tokens/anchors and the new storage format. Keep current old-readable storage. Preserve the old-weight reconciliation gaps as open blockers. Aggregate-only separation can be considered in a separately approved source task. | Owner accepts deferral, not reduced #62 acceptance. No storage implementation begins from this document. |
| C1, forward-only storage with enforceable old-writer exclusion | Use the single encrypted envelope below only after every unsupported old reader/writer is prevented from accessing the active vault. A minimum version checked only by the new app is insufficient for this offline app. | Owner explicitly removes old-binary downgrade from supported operation, specifies the actual exclusion mechanism and recovery procedure, and obtains independent proof it covers cold recovery, already-open writers, reinstall/rollback and restored containers. Without an enforceable mechanism, C1 remains blocked. Store distribution alone does not prove exclusion. |
| C2, compatibility release before cursor migration | First distribute a reader that recognizes the new local format and preserves/refuses future formats through every load/recovery/write path. Delay format activation until the supported-reader population and rollback policy are established. | Owner defines which older releases cease support and how access is prevented. A bridge release cannot retroactively repair baseline readers, so it cannot promise unrestricted downgrade. It also needs source/runtime review as a separate project. |
| C3, retain unrestricted baseline downgrade | Require the next design to keep baseline clinical decoding/writing compatible and atomically persist encrypted continuation without portable pollution or a stale mirror. No viable encoding is established here. | Keep cursor work blocked until a concrete representation and old-writer invalidation proof exist. A separate cursor file, unchecked metadata, or stale v1 mirror does not meet the requirement. |

Do not implement a second active vitals namespace plus an unchanged v1 mirror. Old edits and new cursor commits would diverge. A complete independent vault migration with an old snapshot also requires an explicit fork/conflict/recovery policy and is not a smaller compatible answer. Evidence copies can be preserved for recovery; they cannot masquerade as a live clinical mirror.

The owner must record C0, C1, C2, or a new proven C3 design against an exact candidate. No choice is assumed here. Schema 5 is a proposed reserved successor only; release inventory must establish all used numbers and formats before allocation. Repository schemas 1–4 and backup versions 1–7 remain reserved. Portable version 8 remains unsupported in the baseline contract.

## Conditional atomic storage contract

This section is a target contract conditional on compatibility acceptance. It does not select C1 or authorize migration.

The smallest proposed transaction is one per-profile encrypted blob containing `StoredVitals(format, commitId, revision, data: ProfileVitals, provenance, weightState)`. `ProfileVitals` and `ProfileData` remain clinical-only portable types. No second cursor file or authoritative Swift/UserDefaults anchor exists. Envelope format and repository schema are distinct version spaces even if a future allocation uses the same integer. The key/AAD choice is pending the compatibility decision; blindly reusing the old key is prohibited.

The repository mutex covers validation, reading the latest envelope, fold, encryption, write classification and cache publication. Platform reads run outside the mutex. All vitals writers, aggregate writes, restore/replacement and retention maintenance use this store. Each write compares the captured envelope revision and commits a fresh unpredictable commit ID with the full state. Weight changes never independently advance a cursor. Aggregate writes carry forward the exact current weight continuation and provenance. Previous-generation recovery rolls back the entire envelope together and reports recovery; replay may repeat a page but cannot skip a page whose data was not saved.

The weight-only fold preserves all steps, hourly/daily heart summaries and aggregate `lastSync` exactly. Validate values before replacing an existing value. Invalid upserts increment skipped counts without deleting the prior value. For a proven matching source ID whose valid upsert moves outside retention, remove its prior retained version before dropping the new out-of-range value. Process mutations in provider-supported order and deduplicate by source identity; repeated replay is idempotent. Apply local weight retention and prune matching provenance within the same weight transaction. Unknown/foreign collisions follow F2 rather than overwriting by bare ID. No missing weight on a partial page implies deletion.

A same-file replacement provides data/cursor co-location. It does not by itself prove power-loss durability. Android's two renames have a missing-current interval and directory sync failure is logged. iOS writes atomically and then sets protection, which can throw after replacement. Platform storage must supply the outcome and recovery behavior below before any caller can rely on a receipt.

### Session and operation sketch

Caller sequence: consume the originally approved context once, open an internal invocation, validate before a provider read, submit a page, inspect its outcome, and use only its returned capability for the next read. End the invocation on budget, cancellation, bookkeeping failure, stale state, or ambiguity. A later invocation needs a newly valid entry flow and loads durable progress without reusing an onboarding approval.

```text
beginInvocation(expectedContext, provider) -> Invocation
readPermit(invocationCapability) -> Permit or Rejected
commitWeightPage(capability, expectedRevision, orderedMutations, nextState) -> CommitOutcome
commitAggregateWindow(capability, expectedRevision, aggregateWindow) -> CommitOutcome
reloadAfterIndeterminate(operationId) -> ResolvedCommitted | ResolvedNotCommitted | StillBlocked
endInvocation(capability) -> terminal
```

`Invocation` binds profile identity plus incarnation, provider/source scope, authorization epoch, repository generation, captured zone and now, fixed windows, expected envelope revision, and single-use capability sequence. Authorization capability is memory-only. Durable rebuild progress does not authorize a new read. Every commit consumes its capability, including rejected and indeterminate results. Returned capabilities are valid only inside the same live invocation. Both repository implementations must have equivalent observable behavior.

#93 candidate `DeviceVitalsSync.synchronize(expected)` checks profile/generation before reading. `ComposeBridge.vitalsSyncWindow(expected)` returns the bound context. `HealthKitImporter` consumes `expected` and checks its invalidated flag. Preserve these constraints; do not recapture the latest target after a stale approval. Replace the old bridge's error-only `ingestHealthBatch` result for these new operations with an explicit outcome payload. Neither authorization completion nor a successful data fetch is a commit receipt. Normal Connections and onboarding remain distinct entry flows.

### Outcomes at the durable boundary

| Outcome | Required observable contract | Continuation |
| --- | --- | --- |
| `Rejected(reason)` | Validation/cancellation or a proven pre-replacement failure wrote no new envelope. Cache, data, cursor and revision remain old. A generic storage exception is not proof of rejection. | Original capability is terminal. A retry requires fresh validation and capability. |
| `Committed(receipt, bookkeepingStatus, continuationStatus)` | Authoritative write acknowledgement establishes the new full envelope. Receipt identifies operation, profile incarnation, commit ID, revision and durable counts. Cache/generation reflect that revision or repository is blocked pending reload. | Next capability only if same approved connection remains valid and bookkeeping/protection are acceptable. Cancellation after commit yields committed with stopped continuation. |
| `Indeterminate(operationId, reason)` | Replacement may have happened, acknowledgement/durability is uncertain, or mandatory protection failed after write. Neither rollback nor success is reported. Block the invocation and repository vitals operations against stale cache. | No provider reads, writes, or normal retry until authoritative reload resolves the state and any protection requirement. |

Known data commit followed by `persistHealthLink` failure is `Committed` with failed/pending bookkeeping and no next capability. Do not present it as a retryable data-save failure or fabricate a pinned link. Reload data and resolved link, then require a newly valid invocation. For a default target, this distinction is essential; #93's explicit onboarding link does not prove every caller was already pinned.

For iOS post-write protection failure, return `Indeterminate` and preserve bytes. Reload compares commit ID and revision. Seeing the new ID establishes that replacement occurred, but does not establish acceptable protection or power-loss durability. Keep further operations blocked until protection is verified or repaired through the reviewed storage path. Seeing a different unexpected revision is a conflict requiring fresh state, not permission to replay. If reload fails, remain blocked. Seeing the old ID permits `ResolvedNotCommitted` only after authoritative recovery proves the candidate is not active or pending and any platform durability ambiguity is resolved. Never infer this from cache alone.

Cancellation before the write's linearization point rejects if no mutation is proven. Cancellation during a write is indeterminate unless storage proves its outcome. After acknowledged commit, cancellation stops further pages and does not roll back. Record outcome/publication in a bounded cancellation-safe completion path; do not swallow cancellation to issue another read. Crash before callback is resolved by loading the full envelope at restart. Native callbacks complete at most once; late callbacks cannot revive a terminal invocation. No timeout is itself evidence of rollback.

## F2: durable provenance is separate from authorization

The original rule that clears ownership on every relink/token reset is withdrawn. There are two independent lifetimes.

| State | Meaning and invalidation |
| --- | --- |
| Durable local provenance | Per-weight evidence of provider, source-store scope, local installation lineage and profile incarnation, plus confidence. Written atomically with the value. It survives ordinary same-store relink, authorization renewal, token expiry and invalid-anchor rebuild. It is removed when the value is removed or local replacement/restore severs its history. It is not permission. |
| Authorization epoch and invocation capability | Short-lived approval for a specific target and native action. Relink including A-to-B-to-A, default shift, delete/recreate, restore/replacement, cancellation and lifecycle invalidation reject old capabilities. Nothing in provenance permits a read or commit. |
| Continuation/query state | A token/anchor tied to provider, source scope, predicate policy and rebuild phase. Reset when its validity is uncertain or link policy requires rebuild. Resetting it does not erase otherwise valid provenance. |

A random local lineage ID is useful only while the app can prove continuity. It is not an OS health-store identifier. If a restored app container, device migration, reinstall, provider reset, or account/store change cannot be detected or distinguished, mark scope uncertain and require a policy resolution. Do not certify current-store provenance merely because the same local ID was restored. This platform detection evidence remains a prerequisite.

A positively returned upsert can establish local provenance for a newly created local value in a proven source scope. It does not retroactively prove every matching imported value belongs to that store. Never infer provenance from UUID syntax, record-ID shape, platform name alone, or matching kilograms/time. On a collision with a foreign/unknown value, preserve that value and block or stage the conflicting mutation for an explicit decision. The existing ID-only clinical model cannot represent two conflicting values under one ID; changing it requires a separate approved contract, not silent overwrite.

### Tombstones and absence are distinct

For a received explicit tombstone with a matching, proven durable source scope and record ID, remove the local value and its provenance even after same-store relink or token/anchor reset. Replayed or unknown IDs are idempotent no-ops. Apply same-ID mutations in documented provider order. Android Changes ordering can be retained; Apple returns separate addition/deletion arrays, so do not invent cross-array ordering. If the same UUID occurs in both and the platform contract does not establish precedence, reject the page without anchor advance pending a specified rule or authoritative reread.

For foreign/restored/legacy-unknown values, explicit ID evidence identifies a provider object but does not establish that the stored value came from that provider/store. No algorithm can recover missing provenance from an ID-only tombstone. The legacy W deleted after token acquisition but before snapshot read remains unknown even though its tombstone is received. This is an unresolved product choice, not solved by token-before-snapshot.

| Owner policy choice | Legacy or restored W with received tombstone | Consequence |
| --- | --- | --- |
| P0, preserve ambiguous values | Retain W, record an unresolved deletion classification durably with the page or reject without advancing. If advancing is selected, retain bounded unresolved evidence and report partial coverage. | Protects foreign data, but existing-data #62 deletion repair remains incomplete. It is not accepted scope completion. Evidence overflow stops progress; never silently discard it. |
| P1, explicit per-value or provenance-cohort adoption | Owner/user explicitly designates identified legacy values as belonging to the current source scope before tombstones can remove them. Record that local decision atomically. Unknown or foreign values outside the designated set remain protected. | Can repair adopted received tombstones, including during rebuild. Adoption can delete wrongly classified imported values. Requires approved UX/policy and cannot use an implicit blanket migration. |
| P2, provider-ID authority for all matching unknown values | Apply tombstones globally to matching IDs. | Risks deleting foreign/restored values and fails the requested protection unless owner explicitly changes that requirement. Not recommended or silently selected. |

Recommended submission is C0 while the owner resolves P0 versus a concrete P1 workflow. No design here claims both complete legacy deletion repair and unconditional foreign-data protection. Keep #62's original deletion scope open if P0 is chosen. Same-device portable restore also loses provenance; calling it same-device is not proof sufficient for P1. Ordinary relink of newly proven local data is repaired without adoption because provenance persists. A legacy same-device relink still has the legacy ambiguity.

The minimal conditional version performs no absence deletion, on either platform. Inaccessible, denied, partial, empty, failed or clipped queries never subtract existing weights. Android token expiry and lost Apple tombstones can leave stale values until retention or explicit resolution; no arbitrary-offline completeness claim. A future absence-rebuild design needs certified interval visibility, persisted bounded candidate/seen evidence, replay completion and provenance. It is outside this selected minimal contract. Local 365-day retention is a separate policy, not platform deletion evidence.

## F3: aggregate-only reducer and commit

The instruction to leave the mixed `DeviceVitals.ingest` replacement behavior intact for aggregate calls is withdrawn. Its current `replaceFrom` filters weights, so default `weights=[]` erases recently committed incremental values. `MERGE_ONLY` would fail aggregate omission replacement.

Propose `AggregateWindow(fromLocalDay, throughInstant, steps, heartHourly, capturedNow, zone)` with NO weights, deletion IDs, token or anchor fields. A strict bridge decoder rejects such fields on this operation. `commitAggregateWindow` reads the latest envelope under the same mutex/revision fence and calls an aggregate-only reducer. Neither platform can route this type through `VitalsBatch`/the mixed reducer. Remove or migrate every production mixed aggregate caller in the later implementation; an empty-weight default is not an aggregate-only API.

The reducer has these exact postconditions:

1. `after.weights == before.weights`, including order, IDs, times and values. `after.provenance == before.provenance` and `after.weightState == before.weightState`. Do not call whole-vitals `retain` or `migrated` if either can change weights. Weight retention runs in a separate explicit maintenance or weight commit.
2. Steps and hourly heart entries in the requested recent window are replaced with validated platform statistics. Missing steps/heart in a successfully completed window removes that metric's old entries there. Reject entries outside the captured window and do not interpret an incomplete/failed query as an empty completed window.
3. Preserve the existing hourly acceptance cutoff `start >= capturedNow - 31 days`. Roll existing newly stale hourly buckets into daily summaries exactly once, then remove those hourly buckets. Never inject reread old hourly buckets into daily totals. Prune aggregate retention only, with the existing 365-local-day policy and captured zone.
4. Only a committed aggregate window sets aggregate `lastSync` to captured now. Weight pages never change it. If either required aggregate query fails, commit neither window nor `lastSync`. Counts refer to committed retained values, not fetched samples.

The exact-equality weight rule deliberately defers weight expiration during aggregate refresh. A later explicit maintenance transaction can expire weights and prune their provenance together. It cannot be hidden inside the aggregate reducer.

Android mapping: split `HealthConnectGateway` into aggregate statistics read and weight snapshot/Changes reads. `DeviceVitalsSync` acquires the #93-compatible invocation, reads steps/heart for `VitalsWindow.replaceFrom`, and submits `AggregateWindow`. Weight pages use only `commitWeightPage`. Sequential calls use returned capabilities/revisions; concurrent operations cannot reuse one capability. A stale concurrently fetched aggregate batch must reject or be rebound only within the same still-valid invocation under an explicitly returned capability. It must not recapture a different profile.

iOS mapping: keep daily cumulative steps and hourly heart `HKStatisticsCollectionQuery` reads in `HealthKitImporter`, submit a new strict aggregate payload through `ComposeBridge`/`NativeHealthBatch`'s eventual separate decoder, and call `commitAggregateWindow`. Body-mass anchored pages use a distinct weight payload and `commitWeightPage`. Both carry the same approved target/lifetime rules and explicit outcome. No aggregate payload uses legacy `ingestHealthBatch` with empty weights. Bridge dashboard-refresh failure after committed save is a postcommit presentation failure, not data rollback.

First aggregate window remains 30 local days, later trailing seven days bounded by the existing 30-day floor. Capture one now/zone per invocation, including DST boundaries. Daily summaries and surviving hourly buckets contain disjoint contributions. This does not repair historical step/heart deletions outside that window and cannot complete all of #59/#63 on weight evidence.

## Conditional platform continuation contracts

### Android

Use installed weight-only Changes API, unchanged origin filter, no history/background permission expansion. Obtain a baseline token before snapshot, fix the 365-day desired range, and persist distinct `Snapshot`, `Replay(currentToken)` and `Steady(token)` states. Snapshot stores chunk/page progress and baseline token. Replay persists its CURRENT token after every page, never the original token. Do not promote incomplete snapshot or replay to steady.

Proposed policy limits remain seven-day chunks, requested 500 records/page, and 20 provider calls or ten seconds per invocation. They are not measured limits or guarantees about provider allocation. Commit one page at a time. Null/empty raw page token ends the chunk; repeated nonterminal token fails. Expiry is checked before processing payload. Expired chains restart token-before-snapshot without deleting unseen values. `hasMore=true` with empty Changes still continues after acknowledged progress. Empty unusable next Changes token cannot be a steady cursor; a valid page may commit with explicit rebuild-required state and close the invocation. Repeated next token with more data rejects. Permission loss stops before the next request/write.

Actual readable history may start 30 days before first grant, not rolling now-minus-30. Do not infer grant time from earliest sample or certify coverage from a successful empty read. Unknown first-grant bounds remain unknown. Snapshot only upserts; received tombstones follow the chosen provenance policy. Budget expiry closes the native invocation with durable partial progress. No silent authorization prompt or resurrection of the onboarding importer.

### iOS

Use finite body-mass `HKAnchoredObjectQuery` reads with stable nil time predicate and local retention filtering. A moving time predicate cannot share the same anchor policy. Proposed sample limit is 500, with the same call/time budget. Remove the unbounded weight sample query in the eventual implementation. Securely archive/unarchive only `HKQueryAnchor`, with secure coding and a validated archive-size bound. Kotlin persists the opaque candidate anchor with mutations; Swift advances only from an accepted receipt.

Continue while either additions or deletions is nonempty. A successful empty response with a valid unchanged anchor may terminate. A nonempty response with nonprogress anchor rejects; nil arrays, error or invalid archive is not empty success. Deletion-only pages require commit and another read. The documented sample limit does not establish a total deletion-array or response-memory cap. A hard #59 memory guarantee remains unproven and blocked if required.

Read denial cannot generally be inferred from authorization completion, `authorizationStatus`, or an empty response. Preserve existing values on empty/limited/locked reads. Explicit reauthorization closes old capabilities, revalidates source scope and query policy, and uses a bounded nil-anchor merge rebuild if coverage may have expanded. Keep proven provenance while resetting the anchor. Unobservable permission changes remain a coverage limitation. New history APIs require actual SDK/OS availability evidence before selection. Temporary tombstones mean arbitrary-offline deletion repair cannot be promised. No new background delivery or platform write scope.

## Restore, exports and recovery

The local envelope never becomes `ProfileData`. Backup creation/opening, omission paths, size estimates, previews and doctor exports only see clinical data. Local continuation, provenance, unresolved tombstone evidence, local lineage IDs and authorization are excluded. A synthetic sentinel must be absent from decrypted portable outputs and logs, including single and household backup versions 1–7. Unsupported version 8 remains rejected; unknown fields remain strict.

Every direct replacement and restore boundary writes replacement clinical values, null continuation, unknown provenance, cleared unresolved evidence and null aggregate `lastSync` together in one vitals transaction. It invalidates all old capabilities before publishing the new state. Ordinary same-store relink retains proven local provenance; portable restore does not. Current `restoreWithVitals` is two calls, so a future implementation needs an explicit replacement barrier that prevents sync between profile and vitals operations, survives interruption, and resolves on reload. A per-profile envelope does not make household restore or profile-plus-vitals replacement globally atomic. Do not claim that proof from this design.

Too-new local formats must propagate refusal before generic quarantine in the NEW reader, including previous-generation recovery, export and direct replacement paths. Preserve ciphertext on refusal; no embedded-old-data fallback. This is necessary forward behavior, not a fix for old binaries. Preserve first failure evidence and stop on unresolved storage/protection state.

## Required fault cases and release proof prerequisites

All cases below are unexecuted acceptance requirements. No local tests, builds, device/provider experiments, or release inventory ran in this document task.

| Gate | Literal required oracle |
| --- | --- |
| Old-reader compatibility | Run every distributed supported reader against healthy/current/previous/missing/corrupt index and profile generations, legacy current/previous, tombstones absent/corrupt/previous/reset, vitals-index orphan recovery, missing profiles, sweep, and later create/restore/save. Fault after every migration write, combine routes, and include an already-open old process. No new vitals ciphertext may be quarantined, replaced, swept, or silently hidden as an empty successful open. Healthy-index-only tests are insufficient. |
| Storage outcomes | Inject before-write failure, each Android rename/directory-sync boundary, iOS replacement then protection failure, crash before callback, cancellation before/during/after write, cache-publication failure and link-pin failure. Assert whole old or whole new envelope, explicit outcome, revision and link state. Failed reload stays blocked. A second importer cannot submit the previous revision. |
| Provenance and received tombstones | Seed old-format same-device W; delete after token acquisition before snapshot read. Repeat for new proven W after ordinary relink, legacy relink, same-device restore, foreign/cross-platform restore, invalid-anchor reset and uncertain source-store continuity. Assert P0/P1 choice literally, including unresolved coverage. Foreign collision fixture survives. Ordinary proven owned tombstone removes W. |
| Aggregate interleavings | W then aggregate containing W's date preserves the exact weight list; reverse order also passes. Interleave multiple weight pages and aggregate windows, including explicit weight deletion. Empty successful aggregate window clears only recent steps/heart. Weight state/provenance stay equal. Aggregate failure leaves `lastSync` unchanged. |
| Rollup boundary | Replay old hourly input twice; literal daily min/mean/max/count remain equal. Verify 31-day boundary, 365-day aggregate retention, DST and no hourly/daily double contribution. Aggregate-only operation cannot expire, sort or migrate weights. |
| Android progress | Update/delete between snapshot pages, token expiry during snapshot/replay, restart in each phase, empty Changes with more, empty/repeated token, page invalidation, permission loss, and unknown grant boundary. New cursor exists iff its mutations/provenance exist. No absence deletion. |
| HealthKit progress | 1,201 additions, deletion-only, mixed and same-UUID ambiguous arrays, empty terminal unchanged anchor, nonempty nonprogress, invalid archive, nil/error, locked data, denied then granted, history expansion, delayed callback and cancellation. Assert one completion and no next query without valid capability/receipt. Do not infer total response-memory bound from sample count. |
| Authorization | A-to-B-to-A, delete/recreate, default target shift, restore, receipt reuse, duplicate page, dismissal and late callback reject before provider request and before commit. Budget completion cannot reuse a sheet approval. #93 exact integrated interfaces must be reviewed afresh. |
| Portable/replacement | Existing legacy/default-omitted clinical fixtures and backups 1–7 decode; unsupported future version refuses without mutation. Same/cross-device restore clears local state atomically per profile; interruption between current restore calls cannot allow sync. Sentinel absent in every portable/export/preview/log path. |

Before release or implementation approval, parent needs an exact released-build/schema/reader inventory with artifacts, not just tags or handoff recollection. Include sealed iOS builds, Android distributed artifacts, installation/rollback behavior, all supported backup formats, old active processes, and new source-scope continuity detection. Reserve historical version numbers even when distribution status is unknown. The owner chooses compatibility and provenance policies; an independent consequential reviewer accepts the exact revised document. Then #93 ownership must clear and its final interfaces must be reread. Source owners can subsequently implement in isolated trees, obtain independent review, run the exact integrated local complete gate, and separately document native/provider acceptance gaps. None of these future gates is green here.

## Author inventory

Only authored output is `.unlazy/t3-resume/weight-healthkit-plan-v2.md` in `/Users/heitor/.t3/worktrees/vezuna/t3-6531e950`. Original candidate and review remain unchanged. This document is the only intended commit path, with `Refs #62 #59 #63` and no attribution. No push, branch deletion, source edit, implementation, build, test execution, device operation, server, browser tab, downloaded model, sample, design asset, temporary file or build output was created. No cleanup was attempted. Author completion is not design acceptance; implementation remains blocked pending owner choices and independent approval.

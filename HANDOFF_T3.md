# Vezuna — T3 Code continuation handoff

> Continue the existing work from its exact preserved state. Do not restart the project, redo merged features, discard unfinished changes, or treat earlier partial checks as final approval.
>
> Snapshot: 2026-11-04. Verify live state before acting; all references below describe the last inspection, not an immutable environment.

## 1. Model routing and operating role

Configure T3 using the owner's requested model routing:

| Role | Requested model |
|---|---|
| Main orchestrator | GPT 6 Astra |
| Large features, complex architecture, security/data-integrity fixes and consequential review | GPT 6.1 Sol |
| Medium implementation and standard feature work | GPT 6.1 Terra |
| Small changes, file discovery, focused QA and fast review | GPT 6.1 Luna |

These are owner-supplied labels, **not verified API model IDs**. Resolve them through T3's actual provider configuration. Never invent provider identifiers or silently substitute models. Use Sol for consequential security/data-integrity approval; Luna fast review is not a substitute.

Use multiple subagents with narrow ownership and isolated worktrees. Main agent coordinates evidence, decisions, integration and merges. Independent authoring and approval passes are required.

## 2. Exact stopping point

The owner explicitly said **“stop everything you are doing.”** The previous session then:

- Deleted recurring job `2e9a5ff4`.
- Stopped all six reachable subagents.
- Cancelled active #106 full-gate task `byfno7x7r`.
- Preserved branches, worktrees, commits, uncommitted changes and logs.

The owner now wants this handoff used to **resume in T3 Code from that point**. Old agent/task handles are historical, not reusable T3 workers. Inspect remaining processes before starting new builds. Do not claim a previous loop remains active. Create a new loop using T3's supported mechanism.

### Repository

```text
GitHub: bloodf/vezuna
Root: /Users/heitor/Developer/github.com/bloodf/vezuna
Verified main HEAD: ca90147
Main status at last inspection: clean, tracking origin/main
```

Read these authoritative local instructions:

```text
/Users/heitor/.claude/CLAUDE.md
Repository CLAUDE.md / AGENTS.md, if present
/Users/heitor/.claude/projects/-Users-heitor-Developer-github-com-bloodf-vezuna/memory/vezuna-backlog-workflow.md
/Users/heitor/.claude/projects/-Users-heitor-Developer-github-com-bloodf-vezuna/memory/vezuna-local-verification.md
/Users/heitor/.claude/projects/-Users-heitor-Developer-github-com-bloodf-vezuna/memory/vezuna-development-skills.md
```

This handoff file itself is newly written in the repository root; account for it when checking status. Fetch remote before relying on the snapshot. Treat memory and agent reports as claims to verify.

## 3. Product and stack

Vezuna is an offline household health-records app:

- Kotlin Multiplatform and Compose Multiplatform shared code.
- Android app: `composeApp`.
- iOS app: `iosApp`, Swift shell plus shared Compose bridge.
- **Android and iOS only. No macOS app.**
- Root `package.json` pins EAS submission tooling. This is **not React Native**.
- Health data must not leave the device over the network.

## 4. Non-negotiable constraints

1. Never close issues or separately close PRs. Merge authorized completed work, comment its SHA, and leave issues open for the owner.
2. Never delete branches, local or remote. Never force-push.
3. Never modify PR metadata, repository settings, branch protection or GitHub Actions settings without specific direction.
4. Never bypass required checks or use admin merge.
5. Commit messages and issue references use **`Refs #n`**, never `Fixes` or `Closes`.
6. No AI attribution in commits, PRs, issue comments or other code-host output. No AI co-author trailers or generated-by footer.
7. Verification runs **locally**. Do not add or trigger GitHub CI as a substitute.
8. No paid external APIs, deployments or services without explicit approval.
9. Synthetic data only. Never touch `samples/` or `models/`.
10. Leave the owner's untracked `design/` and `.kimi-feedback.png` alone. Never commit design PNGs.
11. Work only in the assigned worktree. Do not use bare `git stash` or `git stash pop`; the stack is shared. Prefer a uniquely named WIP commit.
12. Never retry denied cleanup through another tool or agent. Inspect before deletion and preserve unfinished/untracked work.
13. Never touch the owner's `galaxy-s25` / `emulator-5554`.
14. Never `adb kill-server`, `./gradlew --stop`, `pkill`/`killall` shared build tools or simulators, or restart CoreSimulatorService.
15. Report failures, skipped checks and missing evidence honestly. A green subset is not a green complete gate.

## 5. Build discipline and environment

All Gradle and Xcode builds must use the existing semaphore:

```bash
GRADLE_OPTS=-Dorg.gradle.daemon=false \
  timeout 3600 bash "$SCRATCH/build-slot.sh" <command>
```

- Maximum **two heavy builds globally**.
- Minimum **15 GB free disk** before a job starts.
- **One build invocation per worktree at a time.** Two slots do not make shared outputs safe.
- Use background jobs with completion notifications for long commands. Do not repeatedly poll or send idle updates.
- Validate simulator creation and UDID before use.
- Compatible visual/test device: **iPhone 17 / iOS 27.0** where installed. iPhone 17 with iOS 27.1 creation failed previously.
- Avoid “Vezuna Lane” simulators for visuals: they rendered black.
- Concurrent native jobs need separate owned simulators.
- Shut down owned devices as soon as no active step needs them. Compiling a framework or waiting for a slot does not need a booted simulator.
- Delete disposable owned devices after use. Use bounded `simctl` commands.
- Android emulator executable must be the full path `~/Library/Android/sdk/emulator/emulator`; bare `emulator` resolved to a broken legacy binary.
- Use a distinct AVD/port and explicit `ANDROID_SERIAL`.
- Kotlin LSP was unavailable. Compiler/test results provide real diagnostics.
- Standalone Swift missing-module diagnostics outside Xcode are not an Xcode compile result.

### Scratchpad

```text
/private/tmp/claude-501/-Users-heitor-Developer-github-com-bloodf-vezuna/65c3c17e-fee0-451e-9941-1c854493c1b4/scratchpad
```

Set `SCRATCH` to that path if still present. It contains:

- `build-slot.sh`, `p3-brief.md`.
- `renovate-merge.sh`, `renovate-skip.txt` containing `132` and `143`.
- Detached integration worktree `wt-main`.
- `reviews/`, regression/gate logs and `shots/`.
- `open-issues-audit.json`.

The OS can wipe this directory on reboot. Recreate missing tooling from inspected requirements; keep durable decisions outside it. Inspect inherited scripts before execution. The owner explicitly authorized standard project gates and cleanup of their own generated worktree/temp output, **not unrelated files or previously denied cleanup**.

### Complete gate

```bash
scripts/check-all.sh
```

Required final marker:

```text
CHECK_ALL_PASSED: localization brand lab-importer ios-network gradle ios
```

The gate covers localization, branding, parser tooling, iOS network policy, JVM/Android builds and tests, lint/release/coverage, native shared iOS tests, app build, real launch check and XCTest.

Important distinctions:

- Android instrumented APK assembly is not instrumented test execution.
- Compile RED is not behavioral RED.
- Restore deliberate mutations before committing.
- A separate passing launch check does not turn a failed full-gate log into `CHECK_ALL_PASSED`.
- Final gate must cover the **final committed tree containing current `origin/main`**.

## 6. Merged work — do not redo

| Work | Merge |
|---|---|
| #97–#99 health records | `cdf77a4` |
| #148 iOS old-build upgrade black screen | `d7e1930` |
| #148 built-plist regression guard | `d9edc7b` |
| #101 appointments | `e88f73c` |
| #102 in-app emergency card | `8c2943d` |
| Profile reload race / stale post-save navigation | `63a1932` |
| UI test `awaitToday`, PT/ES website backup wording | `36efc2c` |
| #100 omission consent, retention, restore conflict preflight | `e93c1f4` |
| Health sync profile/generation and timezone safety | `ca90147` |
| Renovate #152 UiAutomator 2.4.1 | `a3d9922` |

### Main's health safety contract

`ca90147` already implements atomic capture and validation of linked profile, in-memory generation and timezone. Relink, A→B→A, effective default changes, deletion/recreation, restore/replacement and successful sync invalidate stale contexts. All asynchronous Android/iOS/migration callers are bound; the unsafe unbound ingest signature was removed. Captured timezone reaches decoder/mapping and commit. No cursor, storage schema or backup-format change.

Review and full integrated gate passed before merge. Remaining explicit test gaps: cancellation immediately before commit and failure of link bookkeeping after durable save.

## 7. Resume lane A — #106 iOS vault lifecycle

**Assign Terra or Sol.** Security approval remains independent.

```text
Worktree: .worktrees/ios-key-lifecycle
Branch: test/ios-key-lifecycle
HEAD: e0d1b968aa9742169dabcfba394463e1d2158b94
Feature commit: 33840f4305fbbbd18b41ef1e4dd82e4a840077d7
Contains main ca90147
Tree: CLEAN at final inspection
```

Exactly three intended files:

```text
shared/src/iosMain/kotlin/app/vezuna/security/IosSecureVault.kt
shared/src/iosTest/kotlin/app/vezuna/IosVaultKeyLifecycleTest.kt
iosApp/VezunaTests/KeychainKeyStoreTests.swift
```

Implemented:

- Internal injectable key-store constructor; public constructor/production bridge behavior unchanged.
- Three isolated lifecycle tests using real Foundation temp files, injected memory key store and test AES.
- Real Swift Keychain suite uses a UUID disposable service, never live app keys.
- Archive test reads actual archived bytes.
- Quarantine test checks exact envelope bytes through the public vault reader.

Verified targeted evidence:

- Lifecycle **3/3 passed**.
- Real Keychain **4/4 passed**.
- Deliberate missing-key guard mutation failed the expected safety test; restored code passed.
- Temporary `KeyFileVaultCore.kt` mutation was removed. It is not part of the intended patch.
- Independent review found no production defect; its only requested improvement was the exact quarantine-byte assertion, now added.

Full-gate status:

- Two full runs failed only at fresh simulator clone boot: `launchd failed to respond`, `bootstatus` timeout 300 s.
- Owned base reboot completed in 5 s.
- Separate clone launch diagnostic subsequently printed `IOS_LAUNCH_PASSED`.
- **No final full-gate pass.**
- Final attempt `byfno7x7r` was cancelled when owner said stop.
- Log: `scratchpad/ios-key-required-final.log`.
- Last inspected log ended at `:shared:iosSimulatorArm64Test`, not success.

Latest owned base, state must be rechecked:

```text
022A6ED7-D670-4F51-A809-491943133A60
```

Next actions:

1. Inspect any surviving own processes/device after cancellation.
2. Verify clean intended diff, no mutation, latest-main ancestry.
3. Run standard complete gate with a validated compatible owned simulator.
4. If boot fails again, use bounded diagnosis, not endless full rebuilds or shared service restart.
5. Inspect final assertion change and obtain narrow approval if needed.
6. Merge when complete gate passes, comment #106 with honest coverage, remove worktree/devices after use.

Do not claim these tests prove integrated Keychain+CryptoKit or physical locked-device/hardware invalidation. They do not.

## 8. Resume lane B — #93 onboarding security fixes

**Assign Sol. Highest-priority unfinished implementation. Do not merge current branch.**

```text
Worktree: .worktrees/onboarding-options
Branch: feat/onboarding-options
Committed feature: e608f5196776ddffee0adf336812ed2ba8c4c8cc
Committed merge: 9dd10a3b2353b4d46b441ca51d146bfbaa0a8e7f
```

### Critical preserved state

Latest security-review fixes are **UNCOMMITTED across 19 files**. Preserve them. The exact final inspection showed:

```text
composeApp/src/main/java/app/vezuna/AndroidMainActivity.kt
composeApp/src/main/java/app/vezuna/VezunaAndroidApp.kt
iosApp/SharedBridge/ComposeBridge.kt
iosApp/Vezuna/AppShellModel.swift
iosApp/Vezuna/ContentView.swift
iosApp/Vezuna/HealthKitImporter.swift
shared/src/commonMain/composeResources/values/strings.xml
shared/src/commonMain/composeResources/values-pt/strings.xml
shared/src/commonMain/composeResources/values-es/strings.xml
shared/src/commonMain/kotlin/app/vezuna/data/Repository.kt
shared/src/commonMain/kotlin/app/vezuna/data/VaultBackedRepository.kt
shared/src/commonMain/kotlin/app/vezuna/health/DeviceVitalsSync.kt
shared/src/commonMain/kotlin/app/vezuna/localization/Localization.kt
shared/src/commonMain/kotlin/app/vezuna/localization/ResourceMappings.kt
shared/src/commonMain/kotlin/app/vezuna/ui/more/ProfileController.kt
shared/src/commonMain/kotlin/app/vezuna/ui/nav/RouteContext.kt
shared/src/commonMain/kotlin/app/vezuna/ui/today/OnboardingScreens.kt
shared/src/commonTest/kotlin/app/vezuna/ProfileOnboardingTest.kt
shared/src/commonTest/kotlin/app/vezuna/VitalsSyncContextTest.kt
```

### Requirements

Short first-run name/privacy flow, optional notification permission and optional device-health connection. Skipping/denial/unavailability never blocks records. No automatic permission prompt merely from launch/display/skip. Preserve one linked health profile. Android/iOS, EN/PT/ES, accessible controls.

Committed feature adds deferred completion, optional screens, explicit iOS `REMINDERS`, and removes authorization prompts from passive reconciliation while preserving explicit Care/appointment actions.

### Independent review blockers

1. **HIGH: non-atomic link check/save.** Onboarding checked A then separately saved A; concurrent B could be overwritten.
2. **HIGH: stale cached link approved native action.** Reload could publish old link A after B persisted. Native import then captured B after authorization. Main's commit fence does not protect a wrong context captured only after stale approval.
3. **MEDIUM: disclosure missing.** Permission denial/cancel retains explicit selected link; copy did not say so.
4. **MEDIUM: unavailable iOS HealthKit advertised actionable.** Unsupported device could persist link before showing unavailable sheet.

### Latest uncommitted fixes to verify, not assume

- Repository conditional-link operation checks/persists under same mutex in both implementations and returns exact captured sync context.
- Request-bound profile/generation/zone survives Android permission callback and iOS authorization sheet.
- Expected context validated before platform read and again at commit.
- `NativeActions.performHealth(context)` has no unsafe normal-action fallback.
- Missing bound callback means unavailable and no link side effect.
- Shared `supportsOnboarding(action)` used by UI and action boundary.
- iOS availability requires actual HealthKit support plus installed handler; check initialization order.
- Android pending context cleared on callback/cancel; lost context after recreation rejects instead of capturing current target.
- iOS separate sheet-bound importer clears context after success/failure/dismissal and rejects one-shot reuse.
- Normal Connections `HEALTH` flow remains separate.
- Localized pre-tap disclosure explains saved link survives denial/cancel while no data is imported; completion remains available.

### Evidence and limits

- Earlier committed version: 59 JVM tests passed.
- Corrected targeted runs: **26 passed**, Android compilation passed.
- Latest logs inspected ended `BUILD SUCCESSFUL`:
  - `onboarding-review-final-targeted.log`
  - `onboarding-review-effective-targeted.log`
- Also inspect `onboarding-review-green.log` and XML timestamps/counts. Verify they cover the exact current diff.
- Some initial REDs were compile-only. Do not claim behavioral proof for them.
- An earlier real test exposed stale cached-link native dispatch; it was fixed with saved-result guarding.
- Fixture uses a second profile date in 2099 to preserve deterministic default ordering; suspended race bounded to 5 s.
- Source assertions about native authorization are not runtime OS-dialog proof.
- Old full gate timed out `exit 124` in native tests. Latest uncommitted review fixes have no full-gate approval.
- Corrected atomic/native handoff still requires independent security review.

Next actions:

1. Read complete diff and every affected caller; preserve unfinished code.
2. Trace expected context across actual native authorization, sheet lifetime and dismissal.
3. Verify targeted XML/log evidence; add missing real interleaving/absence/cancellation tests as needed.
4. Commit coherent fixes, `Refs #93`, no attribution.
5. Sol independent review of atomic link, expected native context and availability initialization.
6. Integrate current main, run standard complete gate.
7. Capture actual optional UI and record gaps honestly.
8. Merge only after approval and final full success.

Owned simulator last known deliberately shut down:

```text
2A90AFD3-47A9-4283-AFD0-B4A439B8ED2F
```

Do not repeat prior worker behavior: ad-hoc framework-search-path overrides or partial builds are not substitutes for standard gate. No actual tool denial was reported; earlier worker inferred a blanket prohibition from cleanup commands. Inspect actual script scope, honor user authorization, never bypass a real denial.

## 9. Next confirmed backlog work

### #62 Android weight Changes API

- Current trailing-week sync misses old retained weight edits/deletions.
- Installed Health Connect `connect-client:1.1.0` supports `getChangesToken`, `getChanges`, `changesTokenExpired`, `hasMore`, `nextChangesToken`, upserts and deletion IDs.
- Existing domain supports weight ID upserts/deletions.
- Pre-rework code `31e777e^` contains reusable token/page logic in `HealthConnectGateway.kt` and `DeviceVitalsSync.kt`.
- Do not restore old separate cursor store: cursor and data need atomic encrypted persistence.
- Start weight-only. Aggregate steps/heart summaries lack source-ID-to-day mapping; token consumption does not solve their historical deletions.
- Bounded expiry rebuild within existing access/retention. Inaccessible queries must not imply deletion.
- Health access boundary can be 30 days before first grant, not a simplistic rolling now-minus-30.
- Normalize empty pagination token as terminal.
- Device-local cursor must be stripped/cleared in backup/restore/export.
- Strict decoding/schema compatibility needs explicit design before new fields. Do not reuse released schema numbers blindly.
- Avoid simultaneous repository/bridge edits with unfinished #93.

### #59/#63 HealthKit

Old deletions outside trailing window remain stale. Weight query uses `HKObjectQueryNoLimit`; bounded batching is missing. Implement reconciliation carefully; do not replay old hourly aggregates through a path that double-counts rollups.

### #95 accessibility

Generate measured contrast evidence from existing `tools/branding/check_brand.py`; token pairs do not prove every rendered state. Run representative 200% font checks on Android/iOS with existing visual-test tooling. Scrollable off-screen controls are not necessarily clipping. Manual TalkBack/VoiceOver remains separate.

### #148 upgrade regression

Manual simulator upgrade old build → fixed build passed with vault files intact. Automated built-plist guard exists in `tools/release/check_ios_metadata.py`. Full automated old-binary runtime upgrade test remains missing. Physical upgrade remains owner validation.

### #106 additional Android evidence

Missing-alias coverage incomplete; physical hardware invalidation separate. Missing tests do not by themselves prove production defects.

## 10. Audit and owner decisions

Read-only audit reconciled **104 unique non-epic issues / 238 checkbox criteria**, five disjoint batches:

| Range | Issues | Criteria |
|---|---:|---:|
| #90–110 | 18 | 33 |
| #60–89 | 25 | 63 |
| #30–59 | 27 | 61 |
| #3–29 | 24 | 59 |
| #111+ | 10 | 22 |

Saved input: `scratchpad/open-issues-audit.json`. Many initial `UNVERIFIED` statuses were incomplete tracing, not confirmed gaps. Deeper traces confirmed parser pt-BR, leading-zero, range, migration, revisions, AAD, zero-length, schema and corruption-fallback test sources.

Prior actual run evidence includes 49 Android instrumented tests passed with local-model class skipped, and several 37–42 XCTest full-gate results. These are timestamped lane results, not fresh proof of every UI path. Read logs rather than trusting optimistic issue comments.

### Binding owner decisions

- One chosen device-health-linked profile.
- App lock optional, off by default.
- Passphrase-only backups.
- #21 birth date only; relationship requirement superseded.
- #39 approved conversion table; creatinine 88.4.
- #56 counts only, no adherence percentage.
- #73 medication names shown by default, optional hiding.
- #86 retain LVL and clean it up.
- #97–102 all built, not deferred.
- #102 in-app card only, no lock-screen card.
- #100 explicit agreement, retain records/tags, omit bytes, preserve matching local copies.
- Remove untouched auto-created Me after restore.
- CI requirements superseded by explicit local-only workflow.

### Owner-only/external blockers

Verify exact issue scope and keep durable list:

- #80 hosted/signed model catalog; real Play licensing/model delivery.
- #149 physical release timing; #108 per-RC physical checklist.
- #148 physical iOS upgrade.
- #95 manual TalkBack/VoiceOver.
- #101 actual appointment reminder delivery.
- Physical key invalidation and locked-device iOS key access.
- Cross-device Android/iOS restore evidence where missing.
- Native model generation overlap after profile switch remains device-dependent question.
- Renovate #132 skipped because main already has datetime 0.8; closing owner-only.
- Renovate #143 waits for TS7 tooling support.

## 11. Safe cleanup

Owner wants old worktrees, dist, temp and build junk cleared. After merge, confirm no build uses lane, preserve meaningful gate/review evidence, shut/delete owned devices, remove clean merged worktree, retain branch. Delete only inspected owned generated outputs within authorization. No global Gradle/Xcode cache or another session's cleanup.

Preserved old material:

- `.worktrees/redesign-health` at `602ed94` has untracked `.skill-sources/`, `AUDIT.md`, `GATES.md`. Do not delete blindly.
- `.worktrees/redesign-L3-entry` is generated leftover, not registered worktree.
- `scratchpad/scene-old` detached at `b9415e6` remains.
- Earlier destructive cleanup of `scene-old`, `dd-old`, `scene-guard`, `smoke` was denied. Do not silently retry or use another tool to bypass.

## 12. Skills and framework preferences

Owner requested applicable use of no-ai-slop, unslop, Visant, security-audit, unlazy, caveman, ponytail, pstack, Goldie, humanizer, stop-slop, and Effect where appropriate. Inspect installed skills and remote instructions before execution; do not claim everything is installed or used. No paid services or settings/hooks changes just because a skill suggests them.

Use explicit acceptance gates and mutation/negative evidence where useful. Reuse existing code/native platform features; minimize diffs. Do not migrate native app to TypeScript or add Effect to EAS tooling without concrete need. Model roles in T3 use Astra/Sol/Terra/Luna routing above.

## 13. Autonomous continuation loop

Create a real T3 scheduling/background mechanism; a prose promise is not a loop. Confirm actual lifecycle and renewal limits. Previous cron was session-only and expired after seven days; it is now cancelled.

Each iteration:

1. Inspect git/worktrees/logs/build holders.
2. Verify returned work against actual commits, diffs and test outputs.
3. Independently review; return concrete findings to author.
4. Run final full gate on tree containing current `origin/main`.
5. Merge/push approved work and comment SHA; leave issues open.
6. Clean completed worktrees/devices/generated output safely; retain branches/evidence.
7. Check Renovate, skip #132/#143, validate others before merge.
8. Start next narrow confirmed gap while independent work continues.
9. Maintain two-build limit, one invocation per worktree, disk floor.
10. Persist decisions/blockers outside disposable scratchpad.
11. Report meaningful milestones or new blockers, not every idle notification.
12. Never repeatedly full-build known-broken commits while a review fix is pending.
13. Replace stalled/noncompliant workers only after preserving work.
14. Stop immediately on owner instruction.
15. Never stop merely because queue goes quiet; all issues remain owner-controlled.

## 14. First dispatch in T3

1. **Astra:** inspect live repository/process state and create new loop.
2. **Sol:** continue preserved uncommitted #93 security fixes; get independent approval.
3. **Terra/Sol:** complete #106 full gate on clean branch and merge when passed.
4. **Sol planning:** design weight-token persistence/schema and HealthKit follow-up without colliding with #93.
5. **Luna:** narrow evidence reconciliation, file discovery, cleanup inventory; no unapproved deletion.
6. Main integrates results and merges automatically when gates pass.

**Never declare the backlog finished from this handoff. Resume the actual remaining work.**

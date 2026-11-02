# Source audit for the Vezuna redesign

Audit date: 1 November 2026. This is a read-only review of shared Kotlin and native Android/iOS source. It describes the app before the redesign. The images are design references, not evidence that their proposed behaviors already exist.

Read [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) for visual rules and the new navigation. Read [IMPLEMENTATION_GUIDANCE.md](IMPLEMENTATION_GUIDANCE.md) for behavior requirements, state copy, and acceptance checks. Export document rules are specified separately in this package.

## Classification

| Label | Meaning |
| --- | --- |
| Existing | Implemented behavior that the redesign must preserve. A new location or layout does not make it a new capability. |
| Proposed | New screen, interaction, or capability. Implement it explicitly, or disable the unfinished control with an honest explanation. |
| Native system | The operating system owns the picker, permission, authentication, camera, or share interface. Do not rebuild it from an image. |
| Conditional | Available only when a real model catalog, store activation, supported device, permission, or data condition exists. Never populate it with pretend production values. |

Source pointers below are relative to the repository root. Line numbers refer to the audit snapshot and can move during implementation. Abbreviated `ui/`, `domain/`, and `security/` paths are under `shared/src/commonMain/kotlin/app/vezuna/`.

## Current navigation and proposed destination mapping

The current `AppScreen` has ten destinations. The bottom bar exposes Home, Labs, Import, Medications, AI, and Settings. Result detail, Trends, Models, and Vitals are nested. Both apps embed this Compose UI. See `ui/AppController.kt:75` and `ui/VezunaApp.kt:85`.

The redesigned bottom navigation is locked to **Today, Records, Care, Trends, More**, in that order. Import is an action in Today and Records. Assistant and model management belong in More.

The revised visual direction is Living Color. Use a pine header, vivid mint for records, coral for Care, lavender for Trends, citron for device activity, and warm paper surroundings. Keep clinical data on opaque, readable backgrounds. The design should feel energetic without introducing invented health scores, streaks, or clinical claims. The pale first Today concept is superseded.

| Current destination | Redesign location | Classification |
| --- | --- | --- |
| Dashboard | Today | Existing content, proposed arrangement |
| Labs and report rows | Records | Existing lists; search, filters and report detail are proposed |
| Import/review and manual entry | Today/Records add action, focused task screens | Existing operations; unified sequence is proposed |
| Result detail/edit/history | Records detail | Existing content, proposed arrangement |
| Medications and Today dose actions | Care, with next dose also on Today | Existing operations; detail and history pages are proposed |
| Lab Trends and Vitals | Trends with Labs/Measurements selection | Existing data, proposed shared destination |
| Settings/export/backup/security | More, grouped destinations | Existing operations, proposed decomposition |
| AI chat and Models | More > On-device assistant | Conditional existing operations |

## Full screen and state inventory

These audit identifiers group behavior. They are not image filenames. One reusable layout can represent several reason-specific states, provided the implementation retains every reason and recovery action.

| Audit ID | Workflow | Existing screens and states | Main source |
| --- | --- | --- | --- |
| A01 | Startup | Opening encrypted storage; loading data; encrypted storage unavailable; startup error | `ui/VaultRecoveryScreen.kt:42`; `iosApp/Vezuna/ContentView.swift:9` |
| A02 | Vault recovery | Corrupt data; invalidated key; unavailable key; temporary storage failure; retry; start-empty confirmation; restore from backup; restore failed after quarantine; previous-generation notice | `ui/VaultRecoveryScreen.kt:77` |
| A03 | App lock | Locked screen; automatic native authentication; retry after failure/cancel; enable/disable; unavailable device authentication; suspended lock | `ui/AppLockUi.kt:37`; `ui/AppLockUi.kt:71` |
| A04 | Today populated | Profile context; next dose and Taken/Skip/Snooze; later reminders; 30-day adherence; pending review count; recent confirmed results with previous change | `ui/Screens.kt:91`; `ui/Screens.kt:254` |
| A05 | Today empty | No confirmed results; no upcoming dose; no dose events; add medication; auto-created first profile named Me | `ui/Screens.kt:91`; `ui/AppController.kt:125` |
| A06 | Household | Profile manager/switcher; create name; rename dialog; delete with typed-name confirmation; current and busy states; last-profile deletion disabled | `ui/Screens.kt:91`; `ui/ManagementDialogs.kt` |
| A07 | Records list | Confirmed observations by collection date; numeric and text values; reference; date; details action; empty state; import/trends links | `ui/Screens.kt:304` |
| A08 | Report rows | Imported and manual reports; source filename or manual label; import date; delete-report confirmation | `ui/ReportList.kt:34` |
| A09 | Result detail | Original result/unit; normalized value where different; reference; range assessment; previous comparison; source report ID; unavailable result; no previous result | `ui/Screens.kt:444` |
| A10 | Result edit | Numeric or text result; unit; reference bounds/text; date; validation; save/cancel; previous revisions in reverse chronological order | `ui/ResultEditing.kt:40`; `ui/ResultEditing.kt:80` |
| A11 | Source selection | Camera, photo/image, PDF; picker canceled; missing native integration; unreadable/empty/oversized input | `composeApp/src/main/java/app/vezuna/importer/AndroidSourcePicker.kt:45`; `iosApp/Vezuna/NativeImportView.swift:26` |
| A12 | Extraction | Offline processing; embedded PDF text vs OCR; Android page-by-page extracted text; no text; no structured candidates; camera unavailable; extraction failure | `composeApp/src/main/java/app/vezuna/VezunaAndroidApp.kt:145`; `iosApp/Vezuna/NativeImportView.swift:145` |
| A13 | Review queue | Pending candidate count; no pending candidates; successful import count; duplicate import produced no new rows | `ui/Screens.kt:356`; `ui/AppController.kt:196` |
| A14 | Candidate review | Draft/confidence; edit code/name/value/unit/reference; numeric/text toggle; choose collection date; apply date to report; confirm/reject | `ui/Screens.kt:390` |
| A15 | Review warnings | Missing date; ambiguous OCR number with original token; missing required fields/unit; duplicate clinical result; already reviewed/unavailable candidate; saved confirmation | `ui/AppController.kt:299`; `ui/LabResultInput.kt` |
| A16 | Collection date | Calendar dialog; choose/cancel; future-date restrictions; optional apply-to-all report action; iOS native graphical calendar sheet | `ui/CollectionDateField.kt:47`; `iosApp/Vezuna/NativeImportView.swift:91` |
| A17 | Manual result | Code/name; numeric/text choice; value/unit; range bounds/text; required date; save; validation and saved state | `ui/ManualResultForm.kt:26`; `ui/AppController.kt:363` |
| A18 | Lab trends | Test selector; compatible confirmed numeric series; chart; dates/units; value/reference rows; qualitative timelines; no confirmed data | `ui/Screens.kt:487` |
| A19 | Medications | Current list; active/paused/stopped states; next schedule; strength/instructions; schedule; no medications | `ui/CareScreens.kt:79`; `ui/CareScreens.kt:150` |
| A20 | Medication forms | Add/edit name, strength, instructions, daily reminder time; invalid time; missing name; save/cancel | `ui/CareScreens.kt:196`; `ui/AppController.kt:480` |
| A21 | Medication management | Pause/resume; stop confirmation; delete confirmation; stopped schedules; busy controls | `ui/CareScreens.kt:79`; `ui/AppController.kt:520` |
| A22 | Dose actions | Taken/Skip/Snooze from Today and native notifications; 10-minute snooze; failed/stale action; next-dose and adherence refresh | `ui/Screens.kt:254`; `ui/AppController.kt:553`; native notification coordinators |
| A23 | Assistant unavailable | Empty catalog; invalid catalog; no loaded model; selected but not loaded; no confirmed records | `ui/CareScreens.kt:225` |
| A24 | Assistant configured | Confirmed-record context selection; empty conversation; user/assistant messages; question field; busy send; errors; loaded model identity | `ui/CareScreens.kt:225`; `ui/AppController.kt:603` |
| A25 | Model management | Catalog list; true size/RAM/context; install; verifying/loading; loaded; switch/load selected; delete; action failed; missing native loader | `ui/CareScreens.kt:307` |
| A26 | Store model delivery | Android download progress, waiting Wi-Fi, unavailable, failed; iOS loader errors; store activation required | `composeApp/src/main/java/app/vezuna/VezunaAndroidApp.kt:109`; `iosApp/Vezuna/StoreModelPackLoader.swift` |
| A27 | AI activation | iOS inactive, verified, offline-until date, failed verification, refresh App Store, refreshing | `iosApp/Vezuna/LicenseView.swift:3` |
| A28 | Export setup | Active-profile explanation; CSV/PDF actions; device/English/Portuguese/Spanish language; preparing; generated data/ready state | `ui/CareScreens.kt:367`; `iosApp/Vezuna/NativeExportView.swift:11` |
| A29 | Export completion | Android ready dialog with Share/Save/Close; native share/save; saved; preparation/share/save failure; cancellation | `composeApp/src/main/java/app/vezuna/VezunaAndroidApp.kt:132`; `iosApp/Vezuna/NativeExportView.swift:48` |
| A30 | Create backup | Passphrase and confirmation; too short/fair/strong; mismatch; warning; encrypting; native destination; saved/error | `ui/BackupUi.kt:99` |
| A31 | Restore backup | Native file picker; unreadable/too large/not backup/version/content errors; portable passphrase unlock; wrong-passphrase; legacy same-device restriction; opening | `ui/BackupUi.kt:177`; `security/BackupService.kt` |
| A32 | Restore decision | Profile summary; new profile confirmation; collision replace or restore as new; restoring; success; partial vitals failure; recovery-specific confirmation | `ui/BackupUi.kt:256`; `ui/AppController.kt:651` |
| A33 | Device-health link | Current linked profile; link this profile; explain one-profile ownership; view measurements | `ui/VitalsScreens.kt:38` |
| A34 | Measurements | Heart-rate daily average; daily steps; weight; latest value; chart; retention; no values; different linked profile and switch action | `ui/VitalsScreens.kt:55`; `domain/Vitals.kt` |
| A35 | Health authorization | Native Health Connect/HealthKit read authorization; unavailable/update needed/denied; reading; import failed/completed; iOS batch counts | `composeApp/src/main/java/app/vezuna/AndroidMainActivity.kt:170`; `iosApp/Vezuna/HealthImportView.swift:4` |
| A36 | Reminder permission | Android notification permission and enabled/denied state; saved schedules when permission denied; native notification actions | `composeApp/src/main/java/app/vezuna/AndroidMainActivity.kt:204`; native notification coordinators |
| A37 | Privacy and notices | Offline privacy document; third-party licenses; unavailable-document state; close/back | `iosApp/Vezuna/PrivacyPolicyView.swift`; `iosApp/Vezuna/ThirdPartyNoticesView.swift`; Android corresponding activities |
| A38 | Global feedback/adaptation | Dismissible error/success; loading; native integration messages; system light/dark; enlarged text; reduced motion | `ui/VezunaApp.kt:172`; `ui/Theme.kt`; native shell files |

## Proposed screens and explicit new work

| Proposed design | Existing foundation | Required new behavior |
| --- | --- | --- |
| Welcome and first-profile setup | Startup currently creates Me automatically | A short optional introduction and first-name entry; support upgrade users without replacing existing profiles |
| Records search/filter/segmented views | Confirmed observations and imported/manual report lists | Local query/filter state, empty search, clear filters, accessible result count |
| Report detail and source viewer | Source documents have encrypted file keys; observations have report IDs | Safe local source retrieval/viewing; grouped results; missing-source state; no fabricated attachment after portable restore |
| Focused import journey | Existing extraction and candidate confirmation | One entry sequence on both platforms; preserve pending drafts; useful progress; resume review; optional source/review pane |
| Medication detail and dose history | Stored medication schedules and dose events; current reconciliation may persist MISSED | Dedicated routes and truthful event list; presentation must not invent event states. Display persisted MISSED without claiming the dose was not taken. |
| Advanced recurrence editor, if shown | Domain has daily/weekly/every-N-days | Current UI only writes one daily time; richer recurrence requires explicit implementation and validation |
| Export wizard/preview/template selection | Current export prepares one full-profile doctor document | Selection state, preview, real template generation and locale; use the PDF spec for scope |
| Preferences/help/open-source pages | System theme/language, existing notices/privacy | Local preferences if included; help content and verifiable repository links; no account or remote sync |
| Tablet list/detail or source/review | Shared Compose app | Adaptive native layout; not a new web application |

Birth date exists in the profile domain but has no current editor. Treat any new demographic fields as proposed. Do not silently add medical assumptions or required sensitive fields.

## Platform parity findings

| Finding | Evidence | Redesign requirement |
| --- | --- | --- |
| iOS shared Import does not extract candidates | `NativeImportView.swift:251` returns `PickedSource` with an empty candidate list; `ui/AppController.kt:197` rejects empty candidates | Connect every import entry to the native extractor, then use the same review process |
| iOS has a second preliminary review | Native menu opens `NativeImportView`; it edits extracted candidates before adding them to shared unconfirmed review | Consolidate the flow without marking extracted values confirmed automatically |
| Android extraction opens a technical text dialog | `VezunaAndroidApp.kt:145` shows page OCR contents | Keep extraction details optional; make result review the primary next step |
| iOS shared Settings prepares but does not share | Native `NativeExportView` presents `ActivityViewController`; shared Compose path has no equivalent export observer | Every export entry must reach the same preview and native share/save action |
| Platform integration menus differ | Android Device menu vs iOS top-right menu in `ContentView.swift:18` | Move discovery to More > Connections/Privacy/Assistant while retaining native integrations |
| App-lock copy overstates iOS protection | Shared `app_lock_description` promises blocked screenshots; iOS `AppLockSupport.swift` only covers inactive scenes/app-switcher snapshots | Use platform-specific copy. Do not promise iOS screenshot prevention |
| Native model states are fragmented | Android delivery dialog; iOS shell error banner and activation sheet | Present one coherent model-status journey while preserving real native delivery/activation state |

## Capability boundaries verified in source

- Records and exports work without a licensed or loaded model. The committed model catalog is empty. Show the unavailable state by default. Example model names, sizes, downloads or conversation answers in designs are conditional demonstrations.
- The current backup service creates portable passphrase-encrypted `.vhbak` files and opens legacy same-device envelopes. This supersedes stale README backup wording. See `security/BackupService.kt:51` and `security/PassphraseBackupCodec.kt`.
- Backups serialize one `ProfileData`. Encrypted original source files live separately. `domain/ProfileRestore.kt:40` removes unavailable source-file references during restore. Do not claim portable backups include original PDF/image bytes.
- Device health is read-only and belongs to one linked profile. Metrics are heart rate, steps and weight. The first ordinary window is 30 days, the trailing refresh window is 7 days, weight requests can reach 365 days subject to OS permission, hourly heart-rate retention is 31 days, and longer summaries retain 365 days. Do not represent these as guaranteed OS history availability.
- The app has no account, app server, cloud sync, appointments, clinical diagnosis, treatment recommendations, insurer integration, or existing web UI. These are outside this redesign's truthful existing scope.

This audit did not run the native apps, permission dialogs, stores, or physical-device notifications. It establishes source behavior and gaps. The implementation acceptance matrix requires runtime verification.

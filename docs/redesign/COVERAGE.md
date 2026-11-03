# Redesign coverage report (lane V, 2026-11-02, updated after the L9 merge and the PDF gaps lane)

Verification close-out for the Living Color redesign (#121, accessibility #95). Source of truth per image is
[ledger.csv](ledger.csv); the table at the end is generated from it. Reference images live in the local pack
(`design/redesign-2026-11-01/images/`, not committed).

## Summary

| Status | Count | Meaning |
|---|---|---|
| done | 106 | Implemented and verified (tests and/or Android screenshots or sample PDFs compared with the reference) |
| implemented | 26 | Implemented and tested in code; not reachable from the design fixture (lock, vault, restore, export transient/error states) or not shot |
| partial | 22 | Real but incomplete; each gap is named in the table |
| system | 8 | OS-owned UI (permissions, biometric prompt, notification, share/save sheets); the real native interface is invoked |

Total: 162 image IDs.

## What was verified

**Android emulator** (`emulator-5560`, AVD beans-store-demo, 1080x2400 @420dpi, animations off), debug build
with the in-memory design fixture (synthetic Ana Silva household, `--ez fixture true --es route <Route>`),
captured with `scripts/redesign-shots.sh`. Screenshots are kept outside the repo (scratchpad `shots/V/`):

| Set | Variant | Routes |
|---|---|---|
| `android/` | EN, light + dark | Dashboard, Welcome, FirstProfile, Profiles, ProfileEdit, Labs, ReportDetail, SourceViewer, ResultDetail (numeric + text), ImportReview, ImportSource, ImportExtract, CandidateReview, ManualEntry, ResultEdit, ResultHistory, Medications, MedicationDetail, MedicationForm (add + edit), DoseHistory, Trends, TrendDetail (numeric + text), Vitals, VitalsDetail (steps + weight), Connections, Backups, LockSettings, DataPrivacy, More, Settings, Appearance, Language, About, PrivacyPolicy, Notices, Help, AiChat, Models, Activation, ExportHub (42 routes, 84 images) |
| `android-fixed/` | after V fixes | Labs, Medications, TrendDetail, MedicationForm, FirstProfile, ResultEdit |
| `android-font2/` | font scale 2.0, EN | Dashboard, Labs, Medications, Trends, More, ResultDetail, CandidateReview, MedicationDetail |
| `android-es-font2-320dp-v2/`, `-v3/` | ES, font scale 2.0, 320dp wide | Dashboard, Labs, Medications, Trends, More, MedicationDetail, TrendDetail |
| `android-pt/`, `android-es/` | PT / ES | CandidateReview, ImportReview, Medications, TrendDetail, Backups, MedicationDetail |
| `android-tablet/` | 2560x1600 @320dpi (1280dp, rail) | Labs (list/detail), ImportReview, Dashboard, Trends |
| `android-final/` | after all fixes | Dashboard, Medications, Labs |
| `android-l9/` | EN, light + dark (after L9 merge) | ExportHub, ExportOptions (lab history, CSV), ExportSelection, ExportPreview (lab history PDF, CSV, medication record) |
| `android-l9-fixed/` | after the status tag fix | ExportPreview, ExportSelection, ImportReview |

Each `android/` screen was compared side by side with its reference image.

**iOS simulator** (closeout lane, 2026-11-02): the earlier black frames came from the lane devices, whose
runtime is iOS 27.1 and supports only the dual-screen "iPhone Duo" type; `simctl io screenshot` then needs
`--display=` and the app renders into a mis-sized frame. A fresh "Vezuna Closeout" iPhone 17 on the iOS 27.0
runtime renders normally. Shots (light and dark, Debug fixture build) were viewed for Dashboard, Labs, Medications,
Trends, More, ResultDetail, ReportDetail, SourceViewer (missing file), MedicationDetail, MedicationForm, DoseHistory,
Profiles, ManualEntry, Settings, Backups, ExportHub, AiChat, Models, Vitals, plus large text (accessibility XXXL,
EN and ES). Screenshots stay outside the repo. Focused screens that drew their own page title (Vitals, Vitals detail, Connections, Add/Edit medication, Dose history, Create export, Export options, Choose results) now pass it to the task header, matching Backups and Profiles. Defect found and fixed: the Compose host stopped at the top safe
area, leaving a white status-bar strip and a doubled header gap (`ContentView.swift`). Other differences from the
reference images are shared-UI design gaps already named per row, not iOS-only. VoiceOver and AX5 passes remain manual.

**iOS visual pass** (lane ios-visual, 2026-11-02, same "Vezuna Closeout" simulator): 51 fixture routes shot light and dark (102 shots) and viewed against their reference images: every AppScreen route the fixture can reach, with ids Dashboard, Welcome, FirstProfile, Profiles, ProfileEdit, Labs, ReportDetail, SourceViewer, ResultDetail (numeric and text), ImportReview, ImportSource, ImportExtract, CandidateReview, ManualEntry, ResultEdit, ResultHistory, Medications, MedicationDetail, MedicationForm (add and edit), DoseHistory, Trends, TrendDetail (numeric and text), Vitals, VitalsDetail (steps, weight), Connections, Backups, LockSettings, DataPrivacy, More, Settings, Appearance, Language, About, PrivacyPolicy, Notices, Help, AiChat, Models, Activation, ExportHub, ExportOptions (lab history, CSV), ExportSelection, ExportPreview (lab history, CSV, medication record). ES at accessibility XXXL: 21 routes. Landscape (iPhone 17, rail layout), end of scroll (Records list, Candidate review, Today) and the feedback banner were shot with temporary uncommitted patches (a launch-argument orientation request, a scroll-to-end effect, a fixed notice), all reverted. Not reachable: the software keyboard on a form (no input injection: Simulator.app is absent from this Xcode and `axe` cannot load SimulatorKit), the native Activation, privacy policy and notices readers, real-file source viewer, empty/search/filter Records states, Import review end of scroll (the list fits one screen), and the extracting/error import states (no session).

## Defects found and fixed in lane V

| Defect | Where | Fix |
|---|---|---|
| Literal backslashes in EN copy ("household\'s", "report\'s", "can\'t") on 10 strings | shared EN catalog | Plain apostrophes; `check_localization.py` now fails on escaped apostrophes in shared catalogs |
| Unselected segment label light-on-mint in dark mode ("Results", "Device") | `SegmentedControl` | Inactive colours follow the surrounding content colour |
| Care reminder text overlapping the bell illustration | `CareHome` reminder panels | Art clearance on title and intro |
| Trend line running through the latest-value label | `LineChart` | Opaque backdrop behind the value label |
| "Every 1 days" option when editing a daily medication | `ScheduleDraft.from` | Keep interval default unless the schedule repeats every N days (unit test) |
| Tab labels breaking mid-word at large text ("Recor/ds", "Trend/s") | bottom bar, rail | One line, shrink to fit |
| Root titles and panel text breaking mid-word at 200% on 320dp ("Registr/os", "Vitami/n D") | headers, category panels | Art hides from font scale 1.5 and returns its padding; display titles cap at 1.3x |
| "All" range chip pushed off screen at large text | `RangeChips` | Wraps instead of horizontal scroll |
| Status bar text black on the pine header in light appearance (iOS) | `ContentView.swift` | Root hosting controller reports a light status bar |
| Pine headers stopped short of the screen edge and the rail doubled the start inset in landscape (iOS) | `VezunaApp`, `RootHeader`, `FocusedTaskScaffold`, `RootScaffold` | Side cutout insets moved from the Scaffold into the pages; bands bleed to the edge |
| Candidate name broke one letter per line beside the status tag at accessibility XXXL | `ListRow` | `stackTrailing`: the tag stacks under the text from font scale 1.3 (review queue, Care medication row) |
| Security panel title broke mid-word at very large text ("dispositiv/o") | `SecurityPanel` | Art yields its width like other panels |
| Care dose row squeezing the medication name next to the time | `CareHome` dose row | Time stacks above the name at large text |
| Focus jumped to the top after closing a dialog/sheet | profile switcher, delete/stop/reject/clear confirmations | `Modifier.returnFocusAfter(open)` |
| Jargon in user copy (SHA-256, inference, OCR) | models notice, review source label | Plain EN/PT/ES copy |
| Dark-mode warning tag unreadable on the lavender export panel (light text on lavender) | `StatusTag` | Tag sits on an opaque surface under its tint |
| "N pages" disappeared after saving; preview with no valid draft exported the whole history; preview not keyed on profile (L9 review LOWs) | `ExportPreviewScreen` | ReadyFile remembered; back to options; keyed on profile |
| Unreachable legacy export/settings wall, `ExportOptionsPanel`, `NativeAction.EXPORT`, 25 unused iOS export strings | shared UI, iOS catalogs | Deleted; localization check green |
| Export hooks held the destroyed activity | `AndroidMainActivity` | Cleared in `onDestroy` unless a newer activity replaced them |

Ledger rows lost in the lane merges (56 rows reset to not-started) were restored from each lane branch.

## Sample PDFs

Produced by the shared PDF engine on main (`PdfSampleWriterTest`, `VEZUNA_PDF_SAMPLES_DIR`, synthetic data),
checked with `pdfinfo`/`pdftotext` and rendered page 1 of three samples:

| File | Pages | Paper | Notes |
|---|---|---|---|
| vezuna-lab-history-a4.pdf | 3 | A4 | Title, profile, period, scope, "How to read the graphs", dated chart, first/previous changes, tables |
| vezuna-lab-history-letter-grayscale.pdf | 4 | Letter | Grayscale; reference band stays readable |
| vezuna-lab-history-pt.pdf | 3 | A4 | Portuguese |
| vezuna-lab-history-es.pdf | 3 | A4 | Spanish |
| vezuna-lab-history-hemoglobin.pdf | 2 | A4 | Single test |
| vezuna-lab-history-sparse.pdf | 1 | A4 | Sparse period |
| vezuna-lab-history-one-day.pdf | 1 | A4 | One collection day |
| vezuna-lab-history-long.pdf | 16 | A4 | 120 readings, paginated with repeated headers |
| vezuna-health-summary.pdf | 2 | A4 | Design fixture; HGB/LDL summary graphs with first/previous changes, latest results table, medication |
| vezuna-medication-record.pdf | 2 | A4 | Design fixture; medication with end date and seven-day schedule strip, dose timeline, dose table with counts |
| vezuna-device-measurements.pdf | 3 | A4 | Design fixture; step bars, heart-rate and weight dot graphs with latest value and dated changes, plus tables |
| vezuna-lab-history.pdf | 3 | A4 | Design fixture, lab history family |

Text is selectable (pdftotext extracts it). "Marker" does not appear. Compared with `images/pdf/`: lab history
first/continuation pages, single test, sparse period, Portuguese and Letter grayscale match the reference
structure.

PDF gaps lane (`ExportFamiliesSampleWriterTest`, design fixture, period 25-31 Oct where the reference uses it;
samples in scratchpad `pdf-out-gaps/`, rendered with `pdftoppm` and compared with `images/pdf/`):

| File | Pages | Paper | Notes |
|---|---|---|---|
| pdf-01-health-summary.pdf | 2 | A4 | Two half-width graphs (HGB 13.0/12.8/13.2, LDL 146/140/132 with the dashed 130 reference), +0.2/+0.4 g/dL and -14/-8 mg/dL with baseline dates; the table continues on page 2 |
| pdf-06-07-medication-record-dose-history.pdf | 2 | A4 | End date "Not recorded"; seven dated days 01-07 Nov at 08:00 labelled "Planned times from the schedule, not dose history"; day timeline 25-31 Oct Taken/Skipped/Missed with check/bar/cross glyphs; no percentage |
| pdf-08-device-measurements.pdf | 3 | A4 | Step bars 5870-6240, heart-rate and weight dots (weight only on 25/28/31), +370/+140 steps, -1/-1 bpm, -0.4/-0.2 kg; "Days with device data in this export: 7" |
| pdf-09-source-appendix.pdf | 3 | A4 | Health summary plus the appendix page: six reports with laboratory and confirmed counts, October review document under "Not included" with 3 unreviewed values and none of their values |
| letter-grayscale-medication-record.pdf | 2 | Letter | Grayscale; timeline glyphs stay distinct at 150 dpi |
| pt-medication-record.pdf, es-device-measurements.pdf | 2, 3 | A4 | Portuguese and Spanish |

Differences from the images: summary and device pages paginate (the spec prefers more pages over smaller type,
and device graphs keep their exact tables as the text alternative); no tinted panels; the schedule strip is dated
from the export day instead of Mon-Sun; the fixture stores no originals, so the appendix says "Original not stored
on this device" where the image says "Original available", and the "Not included" row shows the document's import
date instead of a date read from its unreviewed values.

## Test results (check-all after the L9 merge, 2026-11-02)

`scripts/check-all.sh` -> `CHECK_ALL_PASSED: localization brand lab-importer gradle ios` (rerun after the L9 merge)

PDF gaps lane (feat/pdf-gaps, merged with origin/main): `CHECK_ALL_PASSED: localization brand lab-importer gradle ios`;
`:shared:jvmTest` 505 tests, 0 failures (11 new in `ExportGapsPdfTest`); koverVerify passed (export package 98% lines);
localization 1086 shared keys; iOS XCTest 27 tests, 0 failures.

- Localization catalogs: passed (1085 shared, 75 Android, 62 iOS keys in EN/PT/ES).
- Brand and contrast check: passed.
- Lab importer tests: passed.
- `:shared:jvmTest`: 494 tests, 0 failures. `:shared:koverVerify` passed.
- `:composeApp:testDebugUnitTest`: 59 tests, 0 failures. assembleDebug, assembleDebugAndroidTest, lintDebug and
  assembleRelease (throwaway key) passed.
- iOS: build and XCTest, 27 tests, 0 failures.

No Compose semantics tests were added: the project has no Compose UI test dependency (`ui-test`) and the lane
rules forbid adding heavy dependencies. Semantics behaviour is covered by code review and the Android shots.

## Remaining prerequisites (honest)

- **Exports:** merged and screenshot-verified on Android. All twelve PDF layouts are built; the source appendix
  options toggle was not screenshotted; CSV numbers are written as 146.0; preview has no zoom or
  thumbnails; export transient/error states were not screenshotted.
- **Model catalog:** the default catalog is empty, so assistant/model-ready states (101-112, 136) cannot be
  reached in any build. Play Asset Delivery and store packs were not exercised.
- **Tagged PDF:** not possible with Android `PdfDocument`/iOS `UIGraphicsPDFRenderer`/PDFBox as used; text stays
  selectable and CSV is the accessible alternative.
- **Manual screen reader passes owed:** TalkBack and VoiceOver passes for headings rotor, live-region
  announcements, focus return after dialogs (code in place, not heard on a device), locked-state tree, and AX5
  Dynamic Type in PT/ES on iOS.
- **iOS visuals:** routes not listed above were not shot; use an iPhone type on the iOS 27.0 runtime (not 27.1).
- **Device-only states:** lock, unlock failure, vault recovery/reset, notification permission and the reminder
  notification itself need a physical device; Health Connect/HealthKit permission sheets are OS UI.
- **Large text edge:** fixed and rechecked on Android at 320dp + font scale 2.0 (ES) in the closeout lane.
- **Store assets:** not part of this pass.

## Per-image status

| ID | Screen | Lane | Status | Remaining gap |
|---|---|---|---|---|
| 001 | today | L1 | done | dose panel uses CARE coral and review uses RECORDS mint (RESEARCH.md category rule wins over raster) |
| 002 | welcome | L1 | done | Open source footer omitted (no offline target); device shots blocked |
| 003 | first-profile | L1 | done | no person illustration asset, uses ill_foliage; device shots blocked |
| 004 | today-empty | L1 | done | device shots blocked |
| 005 | profiles | L1 | done | |
| 006 | profile-edit | L1 | done | device shots blocked |
| 007 | profile-delete | L1 | done | dismiss reads Cancel (shared ConfirmDialog), body keeps existing full-deletion warning |
| 008 | startup | L6 | implemented | Lock/startup states need a real vault (not reachable from the in-memory design fixture): device screenshots not taken |
| 009 | locked | L6 | implemented | Not reachable from the fixture; locked-tree removal proven by code (A11Y_SPIKE section 4); manual VoiceOver/TalkBack pass owed |
| 010 | unlock-failed | L6 | implemented | Not reachable from the fixture; manual device check owed |
| 011 | vault-recovery | L6 | implemented | Not reachable from the fixture; manual device check owed |
| 012 | vault-reset | L6 | implemented | Not reachable from the fixture; manual device check owed |
| 013 | recovered-notice | L6 | partial | image shows a Today banner (L1 surface); shell still shows a dialog. Kept: the dialog guarantees acknowledgement and is not reachable in the fixture, so a Today banner could not be verified |
| 014 | records | L2 | done | iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 015 | records-empty | L2 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 016 | record-search | L2 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 017 | search-empty | L2 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 018 | record-filters | L2 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 019 | report-detail | L2 | done | iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 020 | source-viewer | L2 | done | no real-file device test |
| 021 | source-missing | L2 | done | iOS iPhone 17 sim (iOS 27.0) shot supports rendering only: the SourceViewer fixture route shows the original-file-not-available screen but the missing-source state was not set up deliberately; VoiceOver/AX5 pass owed |
| 022 | report-delete | L2 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 023 | result-detail | L2 | done | iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 024 | qualitative-detail | L2 | done |  |
| 025 | numeric-edit | L3 | done |  |
| 026 | qualitative-edit | L3 | done |  |
| 027 | result-validation | L3 | done |  |
| 028 | revision-history | L3 | done |  |
| 029 | result-unavailable | L2 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 030 | add-record | L3 | done |  |
| 031 | camera | L3 | done | system UI, not redrawn |
| 032 | photo-picker | L3 | done | system UI, not redrawn |
| 033 | file-picker | L3 | done | system UI, not redrawn |
| 034 | camera-permission | L3 | done | system UI, not redrawn |
| 035 | extracting | L3 | done | page-by-page progress not reported by extractors |
| 036 | import-failed | L3 | done |  |
| 037 | import-no-results | L3 | done |  |
| 038 | import-too-large | L3 | done |  |
| 039 | review-queue | L3 | done |  |
| 040 | review-numeric | L3 | done |  |
| 041 | review-text | L3 | done |  |
| 042 | ambiguous-number | L3 | done |  |
| 043 | collection-date | L3 | done |  |
| 044 | duplicate-result | L3 | done |  |
| 045 | reject-candidate | L3 | done |  |
| 046 | review-complete | L3 | done |  |
| 047 | manual-numeric | L3 | done |  |
| 048 | manual-text | L3 | done |  |
| 049 | unsaved-changes | L3 | done |  |
| 050 | trends | L5 | done | no sparkline in overview cards (text summary only, chart lives on detail with its table) |
| 051 | marker-trend | L5 | done | "Export this marker" not wired (L9 owns exports) |
| 052 | text-trend | L5 | done | reference assessment line not shown (domain TextEvent has no reference) |
| 053 | trends-empty | L5 | implemented |  |
| 054 | incompatible-units | L5 | implemented |  |
| 055 | care | L4 | done | Header follows O1 (profile chip, no gear/lockup); iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 056 | care-empty | L4 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 057 | medication-detail | L4 | done | iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 058 | medication-add | L4 | done | Form keeps multi-schedule/recurrence editor from fix/reminders (tested by ScheduleDraftTest); iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 059 | medication-edit | L4 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 060 | medication-validation | L4 | done | Save stays enabled so submit can focus the first invalid field (a11y checklist 9); iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 061 | medication-paused | L4 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 062 | medication-stop | L4 | done | Dialog, not a sheet; iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 063 | medication-delete | L4 | done | Dialog, not a sheet; iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 064 | dose-actions | L4 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 065 | dose-snooze | L4 | partial | 15/30/60 options not built: invariant keeps one 10-minute snooze in-app and from the notification; iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 066 | dose-recorded | L4 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 067 | dose-history | L4 | partial | Per-day dot strip not built; rows and counts carry the same information; iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 068 | reminder-setup | L4 | done | iOS asks automatically on first reconcile, so the intro panel is Android-only; iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 069 | notification-permission | L4 | system | Physical-device manual check; iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 070 | reminders-denied | L4 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 071 | reminder-notification | L4 | system | Physical-device manual check; iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 072 | vitals | L5 | done | steps drawn as dots/line, not bars |
| 073 | vital-detail | L5 | done |  |
| 074 | vitals-empty | L5 | implemented |  |
| 075 | health-link | L5 | done |  |
| 076 | health-profile-mismatch | L5 | implemented |  |
| 077 | healthkit-permission | L5 | system |  |
| 078 | health-connect-permission | L5 | system |  |
| 079 | health-importing | L5 | partial | no full-screen importing page for health sync; the reading-progress screen (035) covers lab file import only, health sync keeps native progress/feedback |
| 080 | health-unavailable | L5 | implemented | state shown as native message, not a full-screen page |
| 081 | health-denied | L5 | partial | no Open settings button: HealthKit hides read denial and has no Health-access deep link; Android needs an action slot in FeedbackHost (not built) |
| 082 | more | L7 | done | Hub uses category panels per image; V lane to shoot |
| 083 | privacy-storage | L6 | done | no Encryption detail screen; row is static |
| 084 | backups | L6 | done | Today nudge not added (L1) |
| 085 | backup-create | L6 | implemented | none known |
| 086 | backup-validation | L6 | implemented | none known |
| 087 | backup-saving | L6 | implemented | none known |
| 088 | backup-ready | L6 | implemented | single Save action (native save/share sheet); no separate Share button |
| 089 | restore-passphrase | L6 | implemented | file name not shown: picker returns bytes only |
| 090 | restore-wrong-passphrase | L6 | implemented | none known |
| 091 | restore-invalid | L6 | implemented | none known |
| 092 | restore-preview | L6 | implemented | none known |
| 093 | restore-collision | L6 | implemented | none known |
| 094 | restore-success | L6 | implemented | none known |
| 095 | restore-failed | L6 | implemented | none known |
| 096 | app-lock-settings | L6 | done | none known |
| 097 | app-lock-unavailable | L6 | done | Open settings action needs a NativeAction |
| 098 | appearance | L7 | done | Theme chooser is a radio list, not image preview cards; system bars follow theme |
| 099 | language | L7 | done | OS per-app language hand-off (Android 13+ app locale settings, older Android system locale settings; iOS Settings app); no in-app override |
| 100 | assistant-unavailable | L8 | done |  |
| 101 | assistant-empty | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 102 | assistant-chat | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 103 | assistant-generating | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 104 | assistant-error | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 105 | models-empty | L8 | done |  |
| 106 | models-list | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 107 | model-detail | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 108 | model-download | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 109 | model-verifying | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 110 | model-ready | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 111 | model-error | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 112 | model-delete | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 113 | activation | L8 | done | Activation fixture route shows the Models page on iOS; the native LicenseView was not reached in the simulator |
| 114 | activation-error | L8 | done | Activation fixture route shows the Models page on iOS; the native LicenseView was not reached in the simulator |
| 115 | export-hub | L9 | done | Profile card is static (switch profile from the root header) |
| 116 | export-options | L9 | done | Period is chips + custom ISO date fields, not a date picker |
| 117 | export-selection | L9 | done | Rows list tests (canonical code) rather than per-report groups |
| 118 | export-preview | L9 | done | No zoom controls; page thumbnails strip not built |
| 119 | export-preparing | L9 | implemented | transient/error state not reachable by deep link: not screenshotted in V |
| 120 | export-ready | L9 | done |  |
| 121 | csv-preview | L9 | done | Preview is a row list, not a 4-column table; CSV writes numbers in Kotlin Double form (146.0 for a report value of 146), so preview and file show the trailing .0 |
| 122 | export-empty | L9 | implemented | transient/error state not reachable by deep link: not screenshotted in V |
| 123 | export-failed | L9 | implemented | transient/error state not reachable by deep link: not screenshotted in V |
| 124 | share-ios | L9 | system | OS-owned UI; not screenshotted (simulator capture black) |
| 125 | share-android | L9 | system | OS-owned UI |
| 126 | save-file | L9 | system | OS-owned UI |
| 127 | save-failed | L9 | implemented | transient/error state not reachable by deep link: not screenshotted in V |
| 128 | about | L7 | done | Real version/build from platform; repo, MIT license, contributors open the browser; notices and privacy use native actions; data-deletion explainer (#94) |
| 129 | privacy-policy | L7 | partial | Screen explains and opens the existing native offline reader; no shared in-app reader |
| 130 | notices | L7 | partial | Screen explains and opens the existing native offline reader; no shared in-app reader |
| 131 | help | L7 | done | Static help with in-app links and GitHub links; no offline guides content exists so none is shown |
| 132 | today-dark | L1 | done |  |
| 133 | records-dark | L2 | done | iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 134 | result-dark | L2 | done | iOS iPhone 17 sim (iOS 27.0) shot viewed light+dark 2026-11-02, renders correctly; VoiceOver/AX5 pass owed |
| 135 | form-dark | L3 | done |  |
| 136 | assistant-dark | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a real catalog + Play asset delivery to exercise |
| 137 | more-dark | L7 | done |  |
| 138 | preview-dark | L9 | done |  |
| 139 | portuguese-review | L3 | done | none (Android PT); iOS PT not taken |
| 140 | spanish-care | L4 | done | none (Android ES) |
| 141 | large-text | L1 | partial | fixed: icon tiles hide at font scale >=1.5 (ListRow, More PanelRow) so long names reflow; verified Android 320dp, font 2.0, ES (Exportaciones whole, focused-screen titles wrap in the header) and iOS ES at AX XXXL; TalkBack/VoiceOver pass owed |
| 142 | tablet-records | L2 | done | iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot; tablet landscape not captured |
| 143 | tablet-review | L3 | partial | source/review split pane not built (proposed) |
| 144 | components | R0 | done | No standalone component catalog screen; components verified in context on every Android shot |
| 145 | biometric-prompt | L6 | system | none |
| 146 | daily-schedule | L4 | done | Recurrence chips stay (built and tested in fix/reminders); iOS sim now renders (iPhone 17, iOS 27.0); this route not yet shot |
| 147 | last-profile | L1 | done | none (fixture has one profile, so ProfileEdit shows the last-profile state) |
| 148 | review-unavailable | L3 | done |  |
| 149 | extraction-details | L3 | done |  |
| 150 | global-feedback | R0 | partial | FeedbackHost live region in the shell (polite/assertive); banner sits at the bottom, image shows it under the header; not moved: header height differs per screen so a top banner would cover titles/back; TalkBack/VoiceOver announcement pass owed (manual) |
| pdf-01 | health-summary | L9 | done | Two A4 pages instead of one: graphs, graph key and the full latest-results table paginate rather than shrink (spec allows 'continued'); a graph that drops labels adds an all-readings table; plain blocks instead of tinted panels |
| pdf-02 | lab-history-first | L9 | done |  |
| pdf-03 | lab-history-continuation | L9 | done |  |
| pdf-04 | single-marker | L9 | done |  |
| pdf-05 | mixed-results | L9 | implemented | No single-point reference plots |
| pdf-06 | medication-record | L9 | done | Strip shows the seven dated days from the export date (works for every recurrence) rather than a Mon-Sun week; open-ended schedules print 'No end date'; details stay one labelled line, not the large icon list |
| pdf-07 | dose-history | L9 | done | Table keeps the Medication column with the recorded time under the status instead of a separate Recorded time column |
| pdf-08 | device-measurements | L9 | done | Three A4 pages: each graph keeps its exact dated table below (text alternative for an untagged PDF); steps print as 6240 (no thousands separator) |
| pdf-09 | source-appendix | L9 | done | Fixture has no stored originals, so rows honestly say 'Original not stored on this device' where the image says 'Original available'; 'Not included' rows show the document's import date (never a date from unreviewed values); page count only for photo captures; options toggle not screenshot-verified |
| pdf-10 | sparse-period | L9 | done |  |
| pdf-11 | portuguese-history | L9 | done |  |
| pdf-12 | letter-grayscale | L9 | done |  |

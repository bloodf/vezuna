# Redesign coverage report (lane V, 2026-11-02)

Verification close-out for the Living Color redesign (#121, accessibility #95). Source of truth per image is
[ledger.csv](ledger.csv); the table at the end is generated from it. Reference images live in the local pack
(`design/redesign-2026-11-01/images/`, not committed).

## Summary

| Status | Count | Meaning |
|---|---|---|
| done | 88 | Implemented and verified (tests and/or Android screenshots compared with the reference) |
| implemented | 21 | Implemented and tested in code; not reachable from the design fixture (lock, vault, restore and backup dialogs) or not shot |
| partial | 22 | Real but incomplete; each gap is named in the table |
| system | 5 | OS-owned UI (permissions, biometric prompt, notification); the real native interface is invoked |
| not-started | 26 | All lane L9 (export UI + PDF image IDs): `feat/redesign-L9-export` was not merged into main when V closed |

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

Each `android/` screen was compared side by side with its reference image.

**iOS simulator** (`6530252C`, Debug build from check-all): `scripts/redesign-shots.sh ios` launched every root
route, but the simulator framebuffer is black (the same result the L1 to L8 lanes reported). No iOS visual
evidence. The iOS build and unit tests pass in check-all.

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
| Care dose row squeezing the medication name next to the time | `CareHome` dose row | Time stacks above the name at large text |
| Focus jumped to the top after closing a dialog/sheet | profile switcher, delete/stop/reject/clear confirmations | `Modifier.returnFocusAfter(open)` |
| Jargon in user copy (SHA-256, inference, OCR) | models notice, review source label | Plain EN/PT/ES copy |

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

Text is selectable (pdftotext extracts it). "Marker" does not appear. The PDF-image IDs (pdf-01 to pdf-12) stay
not-started in the ledger because their export UI belongs to L9.

## Test results (check-all, 2026-11-02)

`scripts/check-all.sh` -> `CHECK_ALL_PASSED: localization brand lab-importer gradle ios`

- Localization catalogs: passed (1002 shared, 75 Android, 87 iOS keys in EN/PT/ES).
- Brand and contrast check: passed.
- Lab importer tests: passed.
- `:shared:jvmTest`: 482 tests, 0 failures. `:shared:koverVerify` passed.
- `:composeApp:testDebugUnitTest`: 59 tests, 0 failures. assembleDebug, assembleDebugAndroidTest, lintDebug and
  assembleRelease (throwaway key) passed.
- iOS: build and XCTest, 27 tests, 0 failures.

No Compose semantics tests were added: the project has no Compose UI test dependency (`ui-test`) and the lane
rules forbid adding heavy dependencies. Semantics behaviour is covered by code review and the Android shots.

## Remaining prerequisites (honest)

- **Exports UI (L9):** not merged at V close-out; the 26 L9 rows are not started on main. When it lands, its
  screens need the same screenshot pass.
- **Model catalog:** the default catalog is empty, so assistant/model-ready states (101-112, 136) cannot be
  reached in any build. Play Asset Delivery and store packs were not exercised.
- **Tagged PDF:** not possible with Android `PdfDocument`/iOS `UIGraphicsPDFRenderer`/PDFBox as used; text stays
  selectable and CSV is the accessible alternative.
- **Manual screen reader passes owed:** TalkBack and VoiceOver passes for headings rotor, live-region
  announcements, focus return after dialogs (code in place, not heard on a device), locked-state tree, and AX5
  Dynamic Type in PT/ES on iOS.
- **iOS visuals:** simulator framebuffer is black in this environment; every iOS screen still needs a visual check.
- **Device-only states:** lock, unlock failure, vault recovery/reset, notification permission and the reminder
  notification itself need a physical device; Health Connect/HealthKit permission sheets are OS UI.
- **Large text edge:** at 320dp + font scale 2.0, a few long single words in grouped rows still break mid-word
  (e.g. ES "Exportaciones").
- **Store assets:** not part of this pass.

## Per-image status

| ID | Screen | Lane | Status | Remaining gap |
|---|---|---|---|---|
| 001 | today | L1 | done | dose panel uses CARE coral and review uses RECORDS mint (RESEARCH.md category rule wins over raster); device shots blocked: simulator renders black |
| 002 | welcome | L1 | done | Open source footer omitted (no offline target); device shots blocked |
| 003 | first-profile | L1 | done | no person illustration asset, uses ill_foliage; device shots blocked |
| 004 | today-empty | L1 | done | device shots blocked |
| 005 | profiles | L1 | partial | R0 ProfileSwitcherSheet (ui/components/Header.kt) has no Add profile row; add lives on Profiles page |
| 006 | profile-edit | L1 | done | device shots blocked |
| 007 | profile-delete | L1 | done | dismiss reads Cancel (shared ConfirmDialog), body keeps existing full-deletion warning |
| 008 | startup | L6 | implemented | Lock/startup states need a real vault (not reachable from the in-memory design fixture): device screenshots not taken |
| 009 | locked | L6 | implemented | Not reachable from the fixture; locked-tree removal proven by code (A11Y_SPIKE section 4); manual VoiceOver/TalkBack pass owed |
| 010 | unlock-failed | L6 | implemented | Not reachable from the fixture; manual device check owed |
| 011 | vault-recovery | L6 | implemented | Not reachable from the fixture; manual device check owed |
| 012 | vault-reset | L6 | implemented | Not reachable from the fixture; manual device check owed |
| 013 | recovered-notice | L6 | partial | image shows a Today banner (L1 surface); shell still shows a dialog |
| 014 | records | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 015 | records-empty | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 016 | record-search | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 017 | search-empty | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 018 | record-filters | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 019 | report-detail | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 020 | source-viewer | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V; no real-file device test |
| 021 | source-missing | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 022 | report-delete | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 023 | result-detail | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 024 | qualitative-detail | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 025 | numeric-edit | L3 | done | none |
| 026 | qualitative-edit | L3 | done | none |
| 027 | result-validation | L3 | done | none |
| 028 | revision-history | L3 | done | none |
| 029 | result-unavailable | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 030 | add-record | L3 | done | none |
| 031 | camera | L3 | done | system UI, not redrawn |
| 032 | photo-picker | L3 | done | system UI, not redrawn |
| 033 | file-picker | L3 | done | system UI, not redrawn |
| 034 | camera-permission | L3 | done | system UI, not redrawn |
| 035 | extracting | L3 | done | page-by-page progress not reported by extractors |
| 036 | import-failed | L3 | done | none |
| 037 | import-no-results | L3 | done | none |
| 038 | import-too-large | L3 | done | none |
| 039 | review-queue | L3 | done | none |
| 040 | review-numeric | L3 | done | none |
| 041 | review-text | L3 | done | none |
| 042 | ambiguous-number | L3 | done | none |
| 043 | collection-date | L3 | done | none |
| 044 | duplicate-result | L3 | done | none |
| 045 | reject-candidate | L3 | done | none |
| 046 | review-complete | L3 | done | none |
| 047 | manual-numeric | L3 | done | none |
| 048 | manual-text | L3 | done | none |
| 049 | unsaved-changes | L3 | done | none |
| 050 | trends | L5 | done | no sparkline in overview cards (text summary only, chart lives on detail with its table) |
| 051 | marker-trend | L5 | done | "Export this marker" not wired (L9 owns exports) |
| 052 | text-trend | L5 | done | reference assessment line not shown (domain TextEvent has no reference) |
| 053 | trends-empty | L5 | implemented | none |
| 054 | incompatible-units | L5 | implemented | none |
| 055 | care | L4 | done | Header follows O1 (profile chip, no gear/lockup); iOS screenshots pending: simulator framebuffer returns black |
| 056 | care-empty | L4 | done | iOS screenshots pending: simulator framebuffer returns black |
| 057 | medication-detail | L4 | done | iOS screenshots pending: simulator framebuffer returns black |
| 058 | medication-add | L4 | done | Form keeps multi-schedule/recurrence editor from fix/reminders (tested by ScheduleDraftTest); iOS screenshots pending: simulator framebuffer returns black |
| 059 | medication-edit | L4 | done | iOS screenshots pending: simulator framebuffer returns black |
| 060 | medication-validation | L4 | done | Save stays enabled so submit can focus the first invalid field (a11y checklist 9); iOS screenshots pending: simulator framebuffer returns black |
| 061 | medication-paused | L4 | done | iOS screenshots pending: simulator framebuffer returns black |
| 062 | medication-stop | L4 | done | Dialog, not a sheet; iOS screenshots pending: simulator framebuffer returns black |
| 063 | medication-delete | L4 | done | Dialog, not a sheet; iOS screenshots pending: simulator framebuffer returns black |
| 064 | dose-actions | L4 | done | iOS screenshots pending: simulator framebuffer returns black |
| 065 | dose-snooze | L4 | partial | 15/30/60 options not built: invariant keeps one 10-minute snooze in-app and from the notification; iOS screenshots pending: simulator framebuffer returns black |
| 066 | dose-recorded | L4 | done | iOS screenshots pending: simulator framebuffer returns black |
| 067 | dose-history | L4 | partial | Per-day dot strip not built; rows and counts carry the same information; iOS screenshots pending: simulator framebuffer returns black |
| 068 | reminder-setup | L4 | done | iOS asks automatically on first reconcile, so the intro panel is Android-only; iOS screenshots pending: simulator framebuffer returns black |
| 069 | notification-permission | L4 | system | Physical-device manual check; iOS screenshots pending: simulator framebuffer returns black |
| 070 | reminders-denied | L4 | done | iOS screenshots pending: simulator framebuffer returns black |
| 071 | reminder-notification | L4 | system | Physical-device manual check; iOS screenshots pending: simulator framebuffer returns black |
| 072 | vitals | L5 | done | steps drawn as dots/line, not bars |
| 073 | vital-detail | L5 | done | none |
| 074 | vitals-empty | L5 | implemented | none |
| 075 | health-link | L5 | done | More must route to AppScreen.Connections (L7) |
| 076 | health-profile-mismatch | L5 | implemented | none |
| 077 | healthkit-permission | L5 | system | none |
| 078 | health-connect-permission | L5 | system | none |
| 079 | health-importing | L5 | partial | no full-screen importing page; native progress/feedback only |
| 080 | health-unavailable | L5 | implemented | state shown as native message, not a full-screen page |
| 081 | health-denied | L5 | partial | no Open settings button |
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
| 100 | assistant-unavailable | L8 | done | none |
| 101 | assistant-empty | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 102 | assistant-chat | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 103 | assistant-generating | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 104 | assistant-error | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 105 | models-empty | L8 | done | none |
| 106 | models-list | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 107 | model-detail | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 108 | model-download | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 109 | model-verifying | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 110 | model-ready | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 111 | model-error | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 112 | model-delete | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 113 | activation | L8 | done | no simulator screenshot |
| 114 | activation-error | L8 | done | no simulator screenshot |
| 115 | export-hub | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 116 | export-options | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 117 | export-selection | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 118 | export-preview | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 119 | export-preparing | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 120 | export-ready | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 121 | csv-preview | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 122 | export-empty | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 123 | export-failed | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 124 | share-ios | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 125 | share-android | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 126 | save-file | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 127 | save-failed | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 128 | about | L7 | done | Real version/build from platform; repo, MIT license, contributors open the browser; notices and privacy use native actions; data-deletion explainer (#94) |
| 129 | privacy-policy | L7 | partial | Screen explains and opens the existing native offline reader; no shared in-app reader |
| 130 | notices | L7 | partial | Screen explains and opens the existing native offline reader; no shared in-app reader |
| 131 | help | L7 | done | Static help with in-app links and GitHub links; no offline guides content exists so none is shown |
| 132 | today-dark | L1 | done | none (Android); iOS dark screenshots not taken |
| 133 | records-dark | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 134 | result-dark | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V |
| 135 | form-dark | L3 | done | none (Android); iOS dark not taken |
| 136 | assistant-dark | L8 | partial | Conditional: the default model catalog is empty, so this state is unreachable in any build today; code compiles and clear-history is unit-tested; needs a rea... |
| 137 | more-dark | L7 | done | none (Android) |
| 138 | preview-dark | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| 139 | portuguese-review | L3 | done | none (Android PT); iOS PT not taken |
| 140 | spanish-care | L4 | done | none (Android ES) |
| 141 | large-text | L1 | done | At 320dp + font 2.0 a few long single words in grouped rows still break mid-word (e.g. ES "Exportaciones"); iOS AX5 pass owed (manual) |
| 142 | tablet-records | L2 | done | iOS fixture shots blocked: simulator framebuffer renders black (app running, no crash); verify in V; tablet landscape not captured |
| 143 | tablet-review | L3 | partial | source/review split pane not built (proposed) |
| 144 | components | R0 | done | No standalone component catalog screen; components verified in context on every Android shot |
| 145 | biometric-prompt | L6 | system | none |
| 146 | daily-schedule | L4 | done | Recurrence chips stay (built and tested in fix/reminders); iOS screenshots pending: simulator framebuffer returns black |
| 147 | last-profile | L1 | done | none (fixture has one profile, so ProfileEdit shows the last-profile state) |
| 148 | review-unavailable | L3 | done | none |
| 149 | extraction-details | L3 | done | none |
| 150 | global-feedback | R0 | partial | FeedbackHost live region in the shell (polite/assertive); banner sits at the bottom, image shows it under the header; TalkBack/VoiceOver announcement pass ow... |
| pdf-01 | health-summary | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-02 | lab-history-first | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-03 | lab-history-continuation | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-04 | single-marker | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-05 | mixed-results | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-06 | medication-record | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-07 | dose-history | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-08 | device-measurements | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-09 | source-appendix | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-10 | sparse-period | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-11 | portuguese-history | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |
| pdf-12 | letter-grayscale | L9 | not-started | L9 Exports UI (feat/redesign-L9-export) not merged into main at V close-out; shared PDF engine (pdf-engine) is on main and produces the sample PDFs listed in... |

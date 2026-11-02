# Shared data and copy

Use this file as the content source for every screen and PDF. The examples are fictional. They are design fixtures, not medical advice or real patient records. A generated image may contain a wrong digit or label. Implement the values and behavior specified here.

## Profile and clock

| Field | Canonical value |
|---|---|
| Profile | Ana Silva |
| Profile ID | `demo-ana` |
| Initials | AS |
| Birth date | 18 May 1988 |
| Design snapshot | Sunday, 1 November 2026, 09:41 |
| Time zone | America/Sao_Paulo, UTC−03:00 |
| Snapshot instant | `2026-11-01T12:41:00Z` |
| Default app language | English |
| PDF generation stamp | 01 Nov 2026, 09:41 UTC−03:00 |
| Profile creation date | 01 Apr 2026 |
| Default lab export period | 12 Apr 2026 to 28 Oct 2026 |
| Default device export period | 25 Oct 2026 to 31 Oct 2026 |
| Demonstration label on PDF samples | Synthetic example · Not a clinical document |

Birth date is optional in the real app. Omit its line when absent. Do not infer sex, pronouns, conditions, allergies, pregnancy, height, BMI, address, insurance, or a clinician from the name or measurements.

Device status-bar clocks are visual context, not independent sample times. At 09:41, the unrecorded 08:00 dose is a dose to record. It is not a future dose. Use “Dose to record” for that state. Once today's dose is recorded, the next scheduled dose is 2 November at 08:00.

## Confirmed laboratory records

There are eight confirmed observations across four markers. All belong to Ana. Dates are collection dates, not import dates. Use the printed precision shown below. All numerical examples and their report ranges are fictional source content, not universal clinical thresholds.

| Observation ID | Collection date | Marker | Code | Original result | Normalized result | Recorded reference | Display status |
|---|---|---|---|---|---|---|---|
| `hgb-2026-04` | 12 Apr 2026 | Hemoglobin | HGB | 13.0 g/dL | 130 g/L | 12.0–16.0 g/dL | Within report range |
| `hgb-2026-07` | 15 Jul 2026 | Hemoglobin | HGB | 12.8 g/dL | 128 g/L | 12.0–16.0 g/dL | Within report range |
| `hgb-2026-10` | 28 Oct 2026 | Hemoglobin | HGB | 13.2 g/dL | 132 g/L | 12.0–16.0 g/dL | Within report range |
| `ldl-2026-04` | 12 Apr 2026 | LDL cholesterol | LDL | 146 mg/dL | 1460 mg/L | Under 130 mg/dL | Above report range |
| `ldl-2026-07` | 15 Jul 2026 | LDL cholesterol | LDL | 140 mg/dL | 1400 mg/L | Under 130 mg/dL | Above report range |
| `ldl-2026-10` | 14 Oct 2026 | LDL cholesterol | LDL | 132 mg/dL | 1320 mg/L | Under 130 mg/dL | Above report range |
| `tsh-2026-10` | 28 Oct 2026 | TSH | TSH | 2.1 mIU/L | 2.1 mIU/L | 0.4–4.0 mIU/L | Within report range |
| `hbsag-2026-10` | 28 Oct 2026 | Hepatitis B surface antigen | HBsAg | Not detected | Not applicable | Not detected | Reference not assessed |

The qualitative result carries `qualitativeValue = "Not detected"`, no units, and no numeric range. The existing domain uses numeric placeholders internally; do not display or export those placeholders as zero. Text matching the reference must not be converted into a numeric “within range” assessment.

Represent the LDL source range as the exact text “Under 130 mg/dL,” with upper bound 130 available for the chart. None of this fixture's values is exactly on the boundary, so no inclusive-versus-exclusive boundary decision is implied. The UI must not rewrite the source wording as a universal target.

Hemoglobin changes by +0.4 g/dL between 15 July and 28 October. LDL changes by −8 mg/dL between 15 July and 14 October. These are arithmetic differences. Do not call them improvement, deterioration, treatment response, or risk reduction. A default graph can use original compatible units; if it uses normalized hemoglobin, its values and range must all change to g/L together. The current normalizer also converts mg/dL to mg/L, so normalized LDL values are 1460, 1400, and 1320 mg/L, with reference 1300 mg/L. The image pack deliberately emphasizes original units; the implementation must apply an explicit display-unit conversion rather than relabel normalized values.

### Readable change-over-time summaries

The revised report pages emphasize the entire available history, actual collection dates, and two separately labelled arithmetic differences. The canonical fixture remains eight observations across four tests; no years or extra readings were added to make a longer graph. The available laboratory period is 12 April through 28 October 2026. A period control can offer longer windows, but its empty years must not acquire fabricated points.

| Test | First result | Previous result | Latest result | Change since first result | Change since previous result |
|---|---|---|---|---|---|
| Hemoglobin | 12 Apr: 13.0 g/dL | 15 Jul: 12.8 g/dL | 28 Oct: 13.2 g/dL | +0.2 g/dL | +0.4 g/dL |
| LDL cholesterol | 12 Apr: 146 mg/dL | 15 Jul: 140 mg/dL | 14 Oct: 132 mg/dL | −14 mg/dL | −8 mg/dL |
| TSH | 28 Oct: 2.1 mIU/L | Not available | 28 Oct: 2.1 mIU/L | Not available: one result | Not available: one result |
| HBsAg | 28 Oct: Not detected | Not available | 28 Oct: Not detected | Not a numeric comparison | Not a numeric comparison |

The formula is latest minus first, and latest minus the preceding confirmed result, within the selected period and compatible unit series. Show the baseline dates alongside the differences. Do not substitute zero when a comparison is unavailable. Use the plain heading “Test” in reports rather than requiring the reader to know “Marker.” A point is an actual recorded result; a connecting segment is a visual guide, not a measurement between the dates. A recorded reference comes from the source report, not a universal health target.

Preferred graph legend: “Dots are recorded results. The reference comes from the source report. Changes are arithmetic differences, not a clinical assessment.” Collection dates belong with the values and horizontal axis. “Generated 01 Nov 2026” identifies document creation and must remain visually separate from the collection period.

### Source report mapping

| Report ID | Report title | Collection date | Source filename | Included observations |
|---|---|---|---|---|
| `report-apr` | April blood work | 12 Apr 2026 | `april-blood-work-2026.pdf` | `hgb-2026-04`, `ldl-2026-04` |
| `report-jul` | July blood work | 15 Jul 2026 | `july-blood-work-2026.pdf` | `hgb-2026-07`, `ldl-2026-07` |
| `report-lipid-oct` | Lipid panel | 14 Oct 2026 | `lipid-panel-2026-10-14.pdf` | `ldl-2026-10` |
| `report-cbc-oct` | Blood count | 28 Oct 2026 | `blood-count-2026-10-28.pdf` | `hgb-2026-10` |
| `report-thyroid-oct` | Thyroid function | 28 Oct 2026 | `thyroid-function-2026-10-28.pdf` | `tsh-2026-10` |
| `report-hbsag-oct` | Hepatitis B screening | 28 Oct 2026 | `hepatitis-b-screening-2026-10-28.pdf` | `hbsag-2026-10` |

Use the laboratory name “Example Laboratory” on source views. This is explicitly fictional. Imported source files are available in the primary fixture. Imports occurred at 18:00 local time on each report's collection date. Collection timestamps may use 08:00 local time for ordering, but the product should show dates when the source only supplies a date. Do not present the internal ordering time as a known collection time.

The recent-record cards deliberately show Blood count and Lipid panel. They are selected recent examples, not a claim that no other reports exist. A full Records view can show all six confirmed source reports. There is also one unreviewed report, below.

### Unreviewed import

The Today card reads “October blood work” and “3 imported values to check.” The file is `october-blood-work-review.pdf`, imported on 1 November at 09:10. Its collection date is 30 October 2026. All three candidates remain unreviewed in the primary fixture.

| Candidate | Extracted value | Extracted reference | Status |
|---|---|---|---|
| Fasting glucose | 96 mg/dL | 70–99 mg/dL | Needs review |
| Ferritin | 42 ng/mL | 15–150 ng/mL | Needs review |
| Vitamin D | 31 ng/mL | 20–50 ng/mL | Needs review |

These are OCR candidates, not confirmed health facts. They do not appear in PDF tables, chart series, CSV rows, clinical counts, or assistant context based on confirmed records. A confirmation-success screenshot is a separate interaction state, not a silent mutation of the primary fixture. Its banner can read “3 results confirmed”; the subsequent counts must then become 11 observations across seven markers. The source values must be verified before that action is enabled.

## Medication and dose events

One medication is recorded. Do not add a second medication to fill a layout.

| Field | Value |
|---|---|
| Medication ID | `med-vitamin-d` |
| Name | Vitamin D |
| Strength | 1,000 IU |
| Instructions | 1 capsule |
| Active | Yes |
| Recurrence | Daily |
| Schedule | 08:00 local time |
| Start date | 01 Apr 2026 |
| End date | None recorded |
| Upcoming event at the primary snapshot | 01 Nov 2026, 08:00, not yet recorded |

The snapshot's medication UI can say “Vitamin D,” “1 capsule · 1,000 IU,” and “Scheduled for 08:00.” “Mark as taken” is a record action. Do not add take-with-food advice, dose-change advice, refill calculations, or a prescriber that is not in the data.

| Scheduled date and time | Status | Recorded time |
|---|---|---|
| 25 Oct 2026, 08:00 | Taken | 25 Oct, 08:03 |
| 26 Oct 2026, 08:00 | Taken | 26 Oct, 08:06 |
| 27 Oct 2026, 08:00 | Skipped | 27 Oct, 08:10 |
| 28 Oct 2026, 08:00 | Taken | 28 Oct, 08:02 |
| 29 Oct 2026, 08:00 | Missed | Not recorded |
| 30 Oct 2026, 08:00 | Taken | 30 Oct, 08:04 |
| 31 Oct 2026, 08:00 | Taken | 31 Oct, 08:05 |
| 01 Nov 2026, 08:00 | Scheduled, unrecorded | Not recorded |

The completed seven-day period has 5 taken, 1 skipped, and 1 missed. No adherence percentage is needed. “Missed” does not prove the person did not take a dose. It reflects the app's recorded event state. In the post-action variant only, the 1 November event becomes Taken with recorded time 09:42. Historical export images use 25–31 October and remain unaffected.

## Device measurements

Device data belongs only to the linked Ana profile. The source label is “Apple Health” in iOS-specific examples and “Health Connect” in Android-specific examples. Generic cross-platform pages say “Connected device health data.” Do not combine duplicate platform sources or claim individual sensor provenance that the stored aggregates do not retain.

| Date | Steps | Daily average heart rate | Weight |
|---|---:|---:|---:|
| 25 Oct 2026 | 5,870 | 73 bpm | 68.8 kg |
| 26 Oct 2026 | 7,120 | 72 bpm | No reading |
| 27 Oct 2026 | 6,845 | 71 bpm | No reading |
| 28 Oct 2026 | 5,930 | 74 bpm | 68.6 kg |
| 29 Oct 2026 | 7,480 | 72 bpm | No reading |
| 30 Oct 2026 | 6,100 | 73 bpm | No reading |
| 31 Oct 2026 | 6,240 | 72 bpm | 68.4 kg |

The period totals 45,585 steps. If showing a rounded daily average, use 6,512 steps/day and explicitly label the rounding. Prefer daily bars and the latest reading over an extra aggregate. Weight readings occurred at 07:30 local time on their listed dates. Last device sync was 1 November at 09:35. The “Latest” cards show 6,240 steps for 31 October, 72 bpm daily average for 31 October, and 68.4 kg measured on 31 October.

Heart rate is a daily average, not resting heart rate, a pulse reading at 09:35, or a diagnostic measurement. Missing weight dates are gaps, not zeroes. Connect real observations with a line if useful, but do not create intermediate daily weights. Device measurements are not part of the current doctor PDF exporter; their PDF representation is proposed.

The revised device PDF labels its available history as seven days and uses these exact comparisons. These differences describe readings, not activity goals or health outcomes.

| Measurement | First baseline | Previous baseline | Latest | Change since first | Change since previous |
|---|---|---|---|---|---|
| Steps | 25 Oct: 5,870 | 30 Oct: 6,100 | 31 Oct: 6,240 | +370 steps | +140 steps |
| Daily average heart rate | 25 Oct: 73 bpm | 30 Oct: 73 bpm | 31 Oct: 72 bpm | −1 bpm | −1 bpm |
| Weight | 25 Oct: 68.8 kg | 28 Oct: 68.6 kg | 31 Oct: 68.4 kg | −0.4 kg | −0.2 kg |

The previous weight baseline is 28 October, not 30 October, because the latter date has no reading. Dose statuses, source availability and empty selections are categorical states and receive no arithmetic change label or numeric trend graph.

## Isolated demonstration variants

Each variant starts from the primary fixture unless it explicitly substitutes a field. Never merge every error, empty state, and variant into one real profile.

| Variant | Change from primary fixture |
|---|---|
| Empty profile | Same display name, no records, no medication, no vitals. Use for first-run/empty screens. |
| Sparse export | Export range 01–31 March 2026; zero confirmed laboratory observations in range. Do not remove April–October data from the profile. |
| Missing reference | A dedicated field-state example omits the reference and says “Reference not provided.” Do not change the canonical HGB range. |
| Missing original file | After cross-device restore, preserve source metadata and confirmed values; clear unavailable source-file pointers. |
| Edit-history detail | Demonstrate on a separately labeled correction state. Earlier HGB value 13.0 g/dL corrected to 13.2 on 29 Oct at 10:15. Do not insert 13.0 as another clinical measurement. |
| Ambiguous OCR | Screen042 uses raw token `1,234` with choices `1.234` and `1234` and requires an explicit decimal interpretation. Do not attach it to the canonical glucose/ferritin/vitamin D candidates. The source-message snapshot in IMPLEMENTATION_GUIDANCE quotes a separate existing `1.020` example. |
| No health permission | Keep stored last-sync data readable; label access unavailable and offer the native settings handoff. |

The correction variant may use the same report/observation IDs in an isolated fixture. PDF-09 uses the primary source ledger and does not need an invented revision to populate the page. A long pagination stress fixture can repeat real-shaped records under test-only IDs, but those values must never enter the product image pack or its canonical counts.

## Copy dictionary

| Context | Preferred copy |
|---|---|
| Local storage | Saved on this device |
| Import action | Add a record |
| Pending review | 3 imported values to check |
| Confirmation action | Confirm 3 results |
| Numeric status | Within report range / Above report range / Below report range |
| Missing reference | Reference not provided |
| Qualitative assessment | Reference not assessed |
| Original source missing | Original file unavailable on this device |
| PDF scope | Includes confirmed results only. 3 unreviewed values are excluded. |
| Medication PDF note | Medication details and dose events are recorded by the user. They are not treatment recommendations. |
| General PDF note | Private health record for discussion with a clinician. Imported values are included only after user confirmation. |
| Empty lab range | No confirmed results in this period. Choose another period to include your laboratory history. |
| Export failure | Couldn't create the file. Your records are unchanged. Try again. |
| Save canceled | Save canceled |
| Backup boundary | Original report files are not included. |
| Backup passphrase | Keep this passphrase somewhere safe. It is required to open this backup on another device. |
| Wrong passphrase | Couldn't open this backup. Check the passphrase and try again. |
| Existing profile restore | Restore as a new profile / Replace Ana Silva's records |
| Export actions | Preview PDF / Save file / Share file |

Avoid “normal,” “healthy,” “at risk,” “improved,” “concerning,” “you should take,” and “AI diagnosis” as computed conclusions. Do not imply a share recipient, cloud account, sync service, clinician review, or scheduled backup exists without implementing that feature.

### Localization examples

| English | Portuguese | Spanish |
|---|---|---|
| Health summary | Resumo de saúde | Resumen de salud |
| Confirmed laboratory results | Resultados laboratoriais confirmados | Resultados de laboratorio confirmados |
| Hemoglobin | Hemoglobin, preserved source name | Hemoglobin, preserved source name |
| Within report range | Dentro da faixa do laudo | Dentro del rango del informe |
| Above report range | Acima da faixa do laudo | Por encima del rango del informe |
| Reference not assessed | Referência não avaliada | Referencia no evaluada |
| Synthetic example | Exemplo fictício | Ejemplo ficticio |

Preserve marker names, qualitative result text, instructions, and units as entered in the source. Translate interface headings and explanatory labels. Thus Portuguese PDF-11 displays numeric value `13,2 g/dL` while the source test name remains `Hemoglobin`. The source reference text “Under 130 mg/dL” also remains unchanged; a localized explanatory label may precede it. CSV numeric cells always use a decimal point and timestamps remain ISO-8601, regardless of header language.

## Backup and privacy truth

Portable `.vhbak` backups contain one profile's structured records: profile details, source-document metadata, reports, observations and revisions, OCR candidates, medication schedules, dose events, chat, and device measurements. They do not bundle original report PDFs or images. The encryption passphrase is separate from the device's app-lock authentication.

Current backup creation is manual. A “Last backup” date, automatic schedule, cloud destination, or recovery key requires new persisted behavior and must be labeled proposed. Do not infer that a share action successfully saved a recoverable file to another device. The current restore flow supports replacing an existing profile or restoring under fresh IDs. Legacy backups may open only on their original device. File-format and maximum-size failures must remain distinct from a wrong passphrase.

The app works without an application server. System share sheets can send a file to another app when the user chooses; exported PDF/CSV files leave the vault's encrypted storage boundary. Do not label ordinary PDF/CSV exports encrypted. A `.vhbak` backup is encrypted. No export subscription gate, account signup, email delivery service, or clinician portal belongs in the implemented baseline.

## Source anchors

Source paths below are relative to the repository root.

| Behavior | Source |
|---|---|
| Profile, source files, results, qualitative values, revisions, medication/events | `shared/src/commonMain/kotlin/app/vezuna/domain/Models.kt` |
| Device metric definitions and retention | `shared/src/commonMain/kotlin/app/vezuna/domain/Vitals.kt` |
| Confirmed-only PDF and CSV | `shared/src/commonMain/kotlin/app/vezuna/export/DoctorExport.kt` |
| Export localization and formatting | `shared/src/commonMain/kotlin/app/vezuna/export/ExportText.kt`, `DoctorReportFormat.kt` |
| Backup contents and supported formats | `shared/src/commonMain/kotlin/app/vezuna/security/BackupService.kt` |
| Original files excluded from portable restores | `shared/src/commonMain/kotlin/app/vezuna/domain/ProfileRestore.kt` |
| Create/restore decisions and passphrase errors | `shared/src/commonMain/kotlin/app/vezuna/ui/BackupUi.kt` |
| Lock and recovery boundaries | `shared/src/commonMain/kotlin/app/vezuna/ui/AppLockUi.kt`, `VaultRecoveryScreen.kt` |

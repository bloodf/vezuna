# Implementation guidance

Implement the reference images as native Vezuna screens, using [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) for tokens and navigation and [SOURCE_AUDIT.md](SOURCE_AUDIT.md) for current behavior. Written requirements take precedence over malformed text or invented details in a generated image. Keep the existing Kotlin Multiplatform, Compose, Android and SwiftUI architecture unless a separate task authorizes a migration.

Use the revised Living Color direction: pine `#123B46` headers, vivid mint `#71E2CC` records, coral `#FF8A65` Care, lavender `#B8ABFF` Trends, citron `#DDF27C` device activity, and warm paper `#F6F7F2`. Clinical content remains opaque and legible. Color adds energy and helps users find tasks; it does not imply a wellness score or clinical diagnosis. Use the latest DESIGN_SYSTEM tokens if the root specification refines these colors.

## Start with these units

1. Add the five destinations Today, Records, Care, Trends, More and the reusable profile control, list rows, forms, sheets and feedback states. Keep all existing routes reachable during the transition.
2. Complete Records and the unified native import/review flow. Then complete Care and Trends with real stored data.
3. Complete More, including exports, portable backups, connections, privacy, app lock and conditional assistant/model flows.
4. Add the proposed screen behaviors listed in the source audit. Implement the PDF designs using the separate export spec.
5. Run the regression acceptance matrix against both platforms, supported locales, dark theme and enlarged text. Record any device/store check that could not run.

## Navigation and data ownership

| Requirement | Expected behavior |
| --- | --- |
| Root navigation | Exactly Today, Records, Care, Trends, More. All labels remain visible. Assistant is in More. Import is an action, not a tab. |
| Profile context | Every root screen shows the selected profile. A focused form identifies its target profile. Changing profiles clears or safely rebinds view selections and never moves records across profiles. |
| Back navigation | Detail/forms use a back affordance and restore the previous list/filter/scroll context. Native back gestures and Android system back behave consistently. |
| Form exit | Keep unsaved input until save succeeds. Proposed discard confirmation is required only when the user would lose entered changes. Canceling an OS picker returns to the task without an error. |
| Household | Add/rename/switch/delete remain available. Delete requires the target profile name and explains the affected records. Never delete the last profile. |
| First launch | Optional onboarding cannot gate access to existing records. Existing installs open their current profiles; do not recreate or rename them. No account is required. |

## Records and import behavior

| Requirement | Expected behavior |
| --- | --- |
| Record truth | Preserve original result, original unit, collection date, source, confirmation status, per-result reference and revision history. Numeric and qualitative values have distinct layouts. |
| Local search/filter | Proposed search must query real local records, retain query/filter state, show result count and distinguish an empty library from no search matches. Clear filters restores records. |
| Report detail | Group the actual observations and candidates owned by the report. The original document opens through safe local file access. If absent after restore, explain that results remain available and offer re-import without claiming an attachment exists. |
| Source entry | Camera, photos/images and PDFs work from every advertised entry on both platforms. Use native system pickers and camera. Never make network OCR a prerequisite. |
| Extraction | Show meaningful local progress without fake percentages. Prefer embedded PDF text and use existing offline OCR fallback. If extraction finds nothing, retain the source context and offer manual entry/retry. |
| Review | Extracted values remain unconfirmed until a person confirms them. Show editable code/name/value/unit/reference/date, source provenance and original ambiguous token. Missing dates block confirmation. |
| Date handling | Use the existing collection-date conventions and disallow future collection dates. Apply-to-all affects only candidates from the intended report. Do not silently substitute import time for collection date. |
| Numeric entry | Preserve decimal-comma support, original displayed precision and ambiguity safeguards. Do not strip thousands/decimal separators blindly. Numeric values require a unit. Qualitative values must never be plotted as zero. |
| Duplicate handling | Re-importing a source cannot silently multiply candidates. Confirming a duplicate clinical result explains the conflict and keeps the user's draft available to correct or reject. |
| Candidate completion | Confirm/reject updates remaining count and Today review context. A completed queue offers a clear route to the report or Records. Do not equate a rejected candidate with a confirmed result. |
| Corrections | Save creates the existing revision record. Preserve earlier value, unit, reference and collection date in history. Editing the UI cannot bypass domain validation. |
| Deletion | Report deletion names the report and explains its dependent results/drafts. Require confirmation. Result correction remains available without deleting the entire report. |

## Care and trends behavior

| Requirement | Expected behavior |
| --- | --- |
| Medication editing | Current fields are name, prescribed strength, instructions and one daily time. Never offer dose recommendations. Richer recurrence controls require real schedule support and tests, not decorative fields. |
| Medication state | Active, paused and stopped remain distinct. Pause preserves history. Stop ends schedules. Resume uses the existing domain semantics, including stopped schedules where supported. Delete names the medication and explains removed data. |
| Dose actions | Taken, Skip and Snooze 10 min act on the actual occurrence. Repeated taps or notification actions cannot create contradictory duplicate events. Update Today, Care and adherence consistently. |
| Dose history | A proposed history page lists stored events and scheduled occurrences honestly. Do not invent event statuses in the presentation. Render persisted MISSED when existing reconciliation supplies it; it does not prove the person did not take the dose. An occurrence without a recorded terminal event remains unrecorded. Current 30-day adherence is taken events divided by recorded terminal events, including taken/skipped/missed, not all scheduled doses. Label that denominator or explicitly implement a different measure. |
| Reminders denied | Schedules remain saved when notification permission is denied. Offer OS settings or retry where available. Avoid a success label that implies notification delivery was tested. |
| Lab chart | Use actual confirmed samples at actual dates. Show units and accessible values. Separate incompatible normalized units. A single reading has no invented trend line. |
| Reference presentation | A reference belongs to a specific result. Draw a common reference band only when its bounds and units are valid for that displayed series; otherwise show per-reading references. Status text describes the report range, not a diagnosis. |
| Text results | Qualitative results use dated text rows/timelines. Keep their supplied reference text. |
| Device measurements | Heart rate, steps and weight remain read-only imports. Label daily averages/totals and source. Missing permission, no values and another linked profile require distinct explanations. |
| Device-health ownership | One linked profile receives device data. Show its name before authorization/import. Switching the visible profile must not redirect imported values unintentionally. |

## More, assistant and data protection

| Requirement | Expected behavior |
| --- | --- |
| Exports | Keep exports available with an empty model catalog and inactive license. Every entry reaches real output and native share/save. Follow the separate PDF spec for template content and preview. |
| Language | Existing app language follows device EN/PT/ES. Preserve per-export override. Any new language/appearance preference is a proposed local setting, not an existing control. Translate all new errors, fields and accessibility text. |
| Portable backup | Create one-profile `.vhbak` with passphrase confirmation and the existing minimum length/strength rules. Explain that Vezuna cannot recover the secret. Do not imply original source files are included. |
| Restore | Validate file kind/size/version/content, unlock portable files, distinguish legacy same-device restrictions, show target profile, and handle collisions through explicit Replace existing profile or Restore as new profile. |
| Restore safety | Keep existing profile data intact until a valid, authorized restore succeeds. Use existing ID remapping when restoring as new. Preserve source references only where the source file actually exists. Report partial vitals failure. |
| Vault recovery | Preserve reason-specific explanations. Retry first. Temporary unavailable storage exposes reset only after the existing three failed retries. Starting empty quarantines unreadable files; it must not delete them. |
| App lock | Authenticate with platform biometrics/device credentials. When locked, health data must not remain composed behind the screen or exposed in an open native sheet. Cover app-switcher previews as implemented. |
| Platform privacy copy | Android may describe screenshot blocking when secure-window protection is enabled. iOS must describe authentication and hidden app-switcher previews only; the current native code does not prevent screenshots. |
| Assistant default | With the committed empty catalog, show AI model not available yet. The screen still allows navigation to records/settings. Do not invent a downloadable model. |
| Assistant configured | Only confirmed, explicitly selected profile records enter the context. Identify loaded model separately from selected model. Send is unavailable until a model is actually loaded. Keep assistant replies educational and tied to selected records. |
| Model lifecycle | Show true install, store-download, integrity-check, load, selected, loaded, delete and failure states. Use real size/RAM/context metadata. Hide implementation IDs/hashes from ordinary product flows; retain diagnostics when useful. |
| Activation | Store verification state governs only the supported AI/model capability. Preserve verified/offline-until/inactive/failed distinctions. No health record is sent to a store service. |
| Legal/open source | Privacy and third-party notices remain offline. Use the actual MIT license/repository links. Do not add a contact form or outgoing message flow just because an image depicts help. |

## Native integration fixes required by this redesign

| Fix | Implementation evidence to produce |
| --- | --- |
| Unify iOS import | Starting from Today, Records or More invokes extraction and returns non-empty candidates for a valid synthetic report. The user confirms them once. No source picker returns empty candidates as a successful import. |
| Unify iOS export | Starting from any export button presents the generated preview/output and opens the native share sheet. There is no prepared-but-unshareable dead end. |
| Consolidate connection discovery | Health authorization, notification settings, app lock and store activation are reachable from their More groups on both platforms. Existing native actions remain functional. |
| Clarify protection claims | Android and iOS lock descriptions match actual OS behavior; neither promises unimplemented recovery or screenshot protection. |
| Reduce technical interruptions | Raw OCR text and model asset identifiers move into optional details. Normal users proceed directly to review or model readiness. Real errors and diagnostics remain inspectable. |

## Reusable state patterns

Use one layout per pattern with a reason, target, current operation and supported recovery action. Reuse visual structure, never replace specific explanations with a universal "Something went wrong" message.

| Pattern | Variants that must remain distinguishable | Actions |
| --- | --- | --- |
| Empty list | First use; no pending review; no search match; no confirmed trend values; no stored dose events; no measurements | Primary next task or clear filters, selected for the actual reason |
| Inline validation | Missing required field/date/unit; invalid time; invalid numeric value; ambiguous separator; range conflict; passphrase mismatch | Focus the affected field; preserve all input |
| Local progress | Opening vault; extracting; saving/encrypting; restoring; preparing export; verifying/loading model; reading measurements | Prevent duplicate submissions; allow only supported cancel/back actions |
| External progress | OS picker/authorization; store downloading; waiting Wi-Fi | Preserve task context; show genuine progress when supplied |
| Recoverable failure | Extraction/read failure; canceled or failed authentication; export/share failure; health import failure; model failure | Retry only the failed operation, or provide the specific fallback |
| Destructive decision | Delete profile/report/medication; stop medication; replace restore; start empty | Name target and consequences; explicit verb; cancel; typed name for profile deletion |
| Success | Confirmed result; manual result; saved backup; restored backup; export saved; recorded dose | Update underlying data; concise confirmation; relevant next action |
| Blocked/unavailable | Empty model catalog; permission denied; missing OS provider; wrong linked profile; unavailable source attachment | Explain the dependency and a real next action without disabling unrelated records |

The exact English reason copy below is a source snapshot. Reuse the existing localization keys and EN/PT/ES translations where meaning stays the same. If the new flow changes the text, update all catalogs together. Do not reproduce technical raw exceptions or implementation IDs as the sole user explanation.

Two source-copy corrections are required. Replace the stale iOS screenshot-blocking claim with "Require Face ID, Touch ID or your device passcode when Vezuna opens and after 5 minutes in the background. App switcher previews hide your records." Replace the old iOS no-results instruction naming the "shared Import screen" with "No laboratory results were recognized. Try another source or enter a result manually." These are proposed copy changes, not existing localized strings.

## Regression acceptance matrix

Run each applicable case on Android and iOS with synthetic records. Rows marked Conditional need real supported device/store configuration before claiming release verification. Source inspection or a compiled build is not a runtime pass.

| ID | Case | Acceptance evidence |
| --- | --- | --- |
| R01 | Clean launch and upgrade | Empty install reaches useful first state; existing encrypted profiles open unchanged; no duplicate Me profile |
| R02 | Five destinations | Correct labels/order, assistant under More, import action reachable, back restores context |
| R03 | Profile isolation | Switch across two synthetic profiles; labs, drugs, chat selections, exports and measurements never leak across profiles |
| R04 | Household edits | Add/rename succeeds; delete requires matching typed name; last profile cannot be deleted |
| R05 | Numeric PDF import | A synthetic embedded-text PDF reaches review with original values, units, references and source; confirm persists exactly once |
| R06 | Scanned/photo import | Camera and image paths run offline OCR on both platforms; busy/error/retry states remain usable |
| R07 | iOS entry parity | Today and Records import entries work, including native menu replacement; no empty-candidate dead end |
| R08 | Missing/ambiguous date/value | Confirmation blocked until date supplied; apply date affects intended report only; ambiguous number requires explicit correction |
| R09 | Qualitative result | Import/manual/edit preserve text and reference; records/export show text; trends use a timeline and no zero-valued numeric chart |
| R10 | Duplicate import/result | Re-import does not multiply candidates; duplicate confirmation reports the exact conflict and preserves draft |
| R11 | Manual result and corrections | Numeric and text forms save; revision history shows old value/unit/reference/date; failure preserves inputs |
| R12 | Source/report lifecycle | Actual attachment opens; missing attachment offers honest fallback; report deletion removes intended dependents only |
| R13 | Local search/filter | Query/filter works on synthetic matching/nonmatching records; no-match differs from empty library; clear restores rows |
| R14 | Lab charts | One-point, irregular dates, varied references, incompatible units and long series render without invented points or misleading shared bands |
| R15 | Medication lifecycle | Add/edit/pause/resume/stop/delete update schedule and retained history with correct confirmations |
| R16 | Dose idempotence | Repeated in-app/notification action for same occurrence does not double count; snooze advances 10 minutes; next dose/adherence refresh |
| R17 | Reminder permission | Denial preserves schedules; enable path reaches OS prompt/settings; physical-device elapsed delivery checked separately |
| R18 | Health link and sync | Permission denial/import failure preserves data; successful import updates linked profile only; other-profile screen offers correct switch |
| R19 | Vitals retention | Actual sample timestamps/aggregates and retention rules remain correct; first/trailing sync windows do not imply unavailable OS history |
| R20 | Exports | All entry points produce real local output and share/save; cancel returns safely; empty-data and language choices handled; PDF spec checks pass |
| R21 | Backup create | Short/mismatched secrets blocked; accepted secret produces portable file; canceled destination does not claim saved |
| R22 | Backup restore | Wrong secret, malformed/oversized/version errors distinct; new profile and replace collision choices work; IDs/source availability handled |
| R23 | Backup legacy/partial | Legacy same-device file works only with original key; unavailable key says so; partial vitals failure is visible |
| R24 | Vault failures | Corrupt/invalidated/missing/temporary errors use correct copy; three-retry rule retained; reset/restore quarantine old bytes |
| R25 | Lock/privacy | Cancel/retry works; after background timeout app locks; native sheets close/hide; Android screenshot protection and iOS switcher cover verified separately |
| R26 | Empty AI catalog | Unavailable state is truthful; no fake install; records/imports/backup/exports still work |
| R27 | Conditional models | Install/download/wait/verify/load/switch/delete/failure agree with real catalog and loaded model; failed verification cannot show ready |
| R28 | Conditional activation | Inactive/verified/offline-expiry/failed states agree with store; expiry does not gate records/export |
| R29 | Conditional assistant | Only selected confirmed records of target profile enter context; selected-not-loaded blocks sending; UI handles busy/error/history |
| R30 | Locale | EN/PT/ES labels/errors/accessibility/plurals fit; decimal-comma input and original clinical text remain correct; export override respected |
| R31 | Accessibility/themes | Screen reader order, labels, status text, focus, 48dp/44pt targets, contrast, dark theme, large text and reduced motion verified |
| R32 | Adaptive layout | Narrow phone, landscape and wide/tablet layouts avoid clipping; expanded keyboard and long translated fields remain usable |
| R33 | Legal/offline | Privacy/notices open offline; errors have useful recovery; no redesign adds account/server/network health-data dependency |

Existing relevant tests include `shared/src/commonTest/kotlin/app/vezuna/` suites for profiles, dashboard, import, numeric/date review, medication, dose events, app lock, backup, vitals and export. Android has instrumented native OCR, export, dashboard, localization and privacy tests under `composeApp/src/androidTest/java/app/vezuna/`. iOS tests are under `iosApp/VezunaTests/`. Extend behavior tests when the redesign adds behavior; do not replace working domain tests with screenshot assertions.


## Exact source message snapshot

The messages below are copied from source, not from rasterized images. Parameter markers must be formatted by the localization system with the actual target value. Preserve each distinct reason even when several reasons share one layout. The existing Play waiting-Wi-Fi text exposes implementation detail; the redesign may simplify its explanation to "Waiting for Wi-Fi to download the model" while retaining the actual delivery state.

### Shared reason-specific messages

Source: `shared/src/commonMain/composeResources/values/strings.xml`.

| Localization key | Exact English source message |
| --- | --- |
| `profile_name_required` | Profile name is required |
| `profile_name_mismatch` | The name you typed does not match this profile |
| `last_profile_cannot_be_deleted` | The last remaining profile cannot be deleted |
| `delete_profile_warning` | This permanently deletes %1$s with all its records, source files and reminders. Type the profile name to confirm. |
| `delete_report_warning` | Delete the report %1$s with its stored file, unreviewed drafts and confirmed results? This cannot be undone. |
| `stop_medication_warning` | %1$s will stop today and its reminders will be removed. Past records are kept. |
| `delete_medication_warning` | Delete %1$s with its schedules, dose history and reminders? This cannot be undone. |
| `vault_recovery_body_corrupt` | The encrypted file that holds your records is damaged or incomplete, and no earlier copy could be read. |
| `vault_recovery_body_key_invalidated` | The device security key that protects your records is no longer valid. This can happen after changing the screen lock or biometrics. |
| `vault_recovery_body_key_unavailable` | The key that protects your records is missing or unreadable on this device. |
| `vault_recovery_body_unavailable` | Your records are temporarily unavailable. The device may be locked or its secure storage busy. Try again in a moment. |
| `vault_recovery_kept_note` | Nothing is deleted automatically. The unreadable files stay on this device in a separate folder. |
| `vault_recovery_reset_confirm_body` | Vezuna will open with no profiles or records. The unreadable files are kept aside, but the app will not be able to read them. |
| `vault_notice_body` | The latest copy of your records was damaged, so Vezuna opened the one before it. Changes made just before the last close may be missing. The damaged file was kept on this device. |
| `app_lock_unavailable` | Set up a screen lock or biometrics on this device to use app lock. |
| `app_lock_failed` | Authentication failed. Try again. |
| `app_lock_suspended` | App lock is off because this device has no screen lock. |
| `no_clinical_candidates_extracted` | No clinical candidates were confidently extracted; use manual entry |
| `no_new_candidates` | No new candidates; duplicate rows were skipped |
| `readable_source_required` | A readable source is required |
| `review_candidate_required` | At least one review candidate is required |
| `candidate_required_fields` | Every extracted candidate needs a test code, name, and value |
| `numeric_candidate_unit_required` | Numeric candidates need a unit |
| `candidate_unavailable` | This review candidate is no longer available |
| `candidate_already_reviewed` | Candidate was already reviewed |
| `collection_date_required` | Choose the collection date before confirming |
| `ambiguous_number_warning` | Check this number: a dot may mean thousands (1.020 = 1,020) or decimals (1.020 = 1.02) |
| `number_ambiguous_input` | Ambiguous number: write thousands without a separator (250000) and decimals with a comma or point that is not followed by exactly three digits (type 1,020 or 1.02 for one point zero two) |
| `enter_numeric_result` | Enter a numeric result |
| `qualitative_result_required` | Enter the text result |
| `reference_bounds_numeric` | Reference bounds must be numeric |
| `duplicate_clinical_result` | This clinical result is already recorded |
| `confirmed_result_saved` | Confirmed result saved |
| `manual_result_saved` | Manual result saved |
| `result_updated` | Result updated |
| `result_unchanged` | Nothing changed |
| `report_not_found` | Report not found |
| `result_unavailable` | Result unavailable |
| `medication_name_required` | Medication name is required |
| `invalid_reminder_time` | Enter a valid reminder time in HH:mm format |
| `medication_not_found` | Medication not found |
| `no_store_model_packs` | AI model not available yet |
| `no_store_model_packs_explanation` | No on-device AI model is included in this version yet. Your records, imports and exports work fully without it. |
| `context_exceeds_model_limit` | Context exceeds model limit |
| `install_model_first` | Install the model before selecting it |
| `backup_passphrase_too_short` | Use at least 12 characters for the backup passphrase |
| `backup_wrong_passphrase` | Wrong passphrase, or the backup file is damaged |
| `backup_not_a_backup` | This file is not a Vezuna backup |
| `backup_unsupported_version` | This backup comes from a newer or unsupported version of Vezuna |
| `backup_unsupported_schema` | Unsupported backup schema |
| `backup_same_device_unavailable` | This is a same-device backup. It can only be opened on the device that created it, and that device key is not available here |
| `backup_invalid_content` | The backup content is damaged or invalid |
| `backup_passphrase_warning` | The backup is protected only by this passphrase. Vezuna cannot recover it: if you lose the passphrase, the backup can never be opened. |
| `backup_passphrase_mismatch` | The passphrases do not match |
| `backup_strength_too_short` | Too short: use at least 12 characters |
| `backup_strength_fair` | Acceptable. A longer phrase with mixed characters is stronger |
| `backup_strength_strong` | Strong passphrase |
| `backup_saved` | Backup saved. Keep the passphrase safe |
| `backup_same_device_label` | Same-device backup: it only restores on the device that created it |
| `backup_profile_exists_body` | This device already has the profile %1$s. Replace it with the backup (its current records are removed), or restore the backup as a new profile. |
| `backup_confirm_body` | Profile %1$s will be added to this device. |
| `backup_recovery_confirm_body` | The unreadable records are moved aside, not deleted, and the backup is restored into a fresh vault. |
| `backup_file_unreadable` | The file could not be read, or it is too large to be a backup |
| `backup_restore_failed_after_reset` | The restore did not finish. Your previous data was kept aside, not deleted. Try restoring again. |
| `backup_restored` | Backup restored |
| `backup_restored_without_vitals` | Backup restored, but the device health data could not be restored |
| `linked_profile_explanation` | Heart rate, steps and weight from Health Connect or Apple Health are saved to one linked profile. Other profiles never receive them, and changing the link does not move data already saved. |
| `vitals_other_profile` | Device vitals are saved to %1$s. Switch to that profile to see them. |

### Android integration messages

Source: `composeApp/src/main/res/values/strings.xml`.

| Localization key | Exact English source message |
| --- | --- |
| `dose_record_unavailable` | Could not record this dose. Open Vezuna and try again. |
| `notification_permission_denied` | Notification permission was not granted. Recorded schedules remain saved; enable notifications to receive reminders. |
| `health_connect_permission_denied` | Health Connect read permissions were not granted. No health metrics were imported. |
| `health_connect_update_required` | Health Connect needs an installed provider update. Update it from your device or app store, then retry. |
| `health_connect_unavailable` | Health Connect is unavailable on this device. Vezuna records and exports remain fully accessible. |
| `health_connect_import_failed` | Health Connect import failed. The previous cursor and encrypted samples were preserved. |
| `reminder_schedules_active` | Recorded medication schedules are active. Reminder actions record taken or skipped; snooze waits 10 minutes. |
| `share_export_failed` | Could not open the share sheet. |
| `export_saved` | Export saved to the destination you selected. |
| `export_save_failed` | Could not save the export. |
| `play_waiting_wifi` | Play is waiting for Wi-Fi. No model URL or hash has been assumed; delivery remains under Play Asset Delivery control. |
| `play_downloading` | Downloading the configured asset pack: %1$d%% |
| `source_too_large` | Source exceeds the 64 MB import limit. |
| `source_open_failed` | Could not open the selected source. |
| `image_empty` | The selected image is empty. |
| `image_unreadable` | The selected file is not a readable image. |
| `pdf_empty` | The selected PDF is empty. |
| `pdf_read_failed` | Could not read %1$s as a PDF. |
| `model_premium_unavailable` | Premium AI activation is unavailable. Records and exports remain accessible. |
| `model_pack_unavailable` | The configured Play asset pack is not installed or is unavailable for this app version. |
| `model_delivery_failed` | Play asset pack delivery failed. |
| `asset_path_unavailable` | Asset pack has no readable assets path. |
| `gguf_missing` | GGUF file is missing from the delivered asset pack. |
| `save_already_active` | Another document save is already active. |
| `source_import_active` | Another source import is already active. |
| `destination_open_failed` | Could not open the destination. |
| `privacy_policy_unavailable` | The privacy policy could not be loaded. |

### iOS integration messages

Source: `iosApp/Vezuna/en.lproj/Localizable.strings`.

| Localization key | Exact English source message |
| --- | --- |
| `premium_inactive` | Premium AI is not active. Records and exports remain available. |
| `premium_verified` | Premium AI is verified for this installation. |
| `premium_offline_until` | Premium AI is available from the offline cache until %1$@. |
| `store_verification_failed` | Store verification failed. Records and exports remain available. |
| `model_premium_required` | Verify premium AI access before downloading a model. Records and exports remain available. |
| `model_asset_size_mismatch` | The store asset size does not match its signed catalog metadata. |
| `model_asset_unavailable` | The store asset %1$@ is configured but its GGUF file is unavailable. |
| `import_unreadable_pdf` | The selected PDF could not be opened. |
| `import_unreadable_image` | The selected image could not be decoded. |
| `no_text_found` | No embedded or offline-OCR text was found in this source. |
| `camera_unavailable` | The camera is unavailable on this device. |
| `invalid_selection` | Choose a PDF or image file. |
| `healthkit_unavailable` | HealthKit is unavailable on this device. |
| `healthkit_type_unavailable` | This HealthKit quantity type is unavailable. |
| `medication_schedule_missing` | The medication schedule no longer exists |
| `dose_record_failed` | Could not record the dose action |
| `active_profile_changed` | The active profile changed before import |
| `import_save_failed` | Could not save the imported results |
| `storage_open_failed` | Could not open encrypted Vezuna storage |
| `model_catalog_invalid` | Model catalog is invalid |

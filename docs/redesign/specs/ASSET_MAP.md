# Screen asset map

Every one of the 150 app references and 12 PDF references is mapped in `assets/screen-assets.json`. Paths resolve from this redesign directory. The JSON contains exact SVG paths, native-control contracts, vector primitives and per-screen implementation notes. It is the lookup table for the coding agent.

## What to implement

Use the supplied editable vectors for app-owned artwork. Do not slice illustrations, icons, logos or charts out of the generated PNGs. The illustrations are consistent Living Color replacements for the generator's varying decorative strokes. Text, fields, cards, chips, dividers, chart axes and page rules are live components/vector geometry, not image assets.

Use exact official logo assets: two rounded curved strokes forming an open V around one dot. The updated mockups match this identity, while SVG masters remain authoritative for exact paths. `lockup-dark.svg` serves pine headers, `mark-dark.svg` serves dark standalone marks, and `lockup-ink.svg` serves print pages. Use color variants on light surfaces and preserve clear space. Foliage is decorative, never the logo.

The 86 general UI icon files are listed in `assets/vectors/ui-catalog.json`; seven chart references and their source values are in `assets/vectors/charts/`. Thirty motifs are catalogued in `assets/illustrations.json`. These cover foliage, category art, empty states, import/OCR, assistant/models, backup/restore, export and feedback. The older small `assets/brand/ui-icons/` family remains a brand-kit reference; the full `assets/vectors/icons/` family is the screen implementation set.

SVG chart examples are fixtures, never production patient data. Use original units and real date spacing, retain gaps and draw only measured points. The heart-rate reference contains daily averages; the hourly variant requires actual hourly observations. One-point and qualitative states use a labelled point or text timeline without a fabricated line. Calendar dots, progress arcs, status dots and any chart selection marker use vector geometry and text semantics.

## Source document fixtures

Four editable, clearly fictional report excerpts are supplied in `assets/vectors/fixtures/`: `report-blood-count.svg`, `report-october-review.svg`, `report-hbsag.svg` and `ocr-ambiguous-token.svg`. They cover the original viewer, report thumbnails and review crops. They use Ana Silva's canonical synthetic values and the isolated ambiguous token `1,234`. They are not replacement clinical reports. In production, show the person's original stored document, even when it is a scanned bitmap; never redraw or silently alter the source as a vector. Source files are not included in structured backup exports.

## Native controls and system surfaces

The OS owns its status bar, gesture area, camera, photo/file pickers, save destination sheet, permission alerts, biometric prompt, notification surface, Health authorization and share sheet. The reference images explain transitions. They do not license or require custom reproductions of OS or third-party icons. Use system APIs and the actual installed share targets, permissions, filenames and computed sizes. Native controls remain native at large text, with screen reader focus and reduced motion respected.

| Contract | Implementation requirement |
|---|---|
| `system-chrome` | OS status bar, safe areas, gesture/home indicator and accessibility scaling. Do not draw fixed time, signal or battery SVGs into production UI. |
| `text-input` | Platform-backed editable field, selection menu and appropriate keyboard; preserve labels, entered text and error semantics. |
| `secure-input` | Platform-backed secure passphrase field with app show/hide action; never bake a secret into an asset. |
| `date-picker` | Platform date picker or accessible platform-backed calendar, localized and limited by the recorded-date rules. |
| `time-picker` | Native time picker using the device time zone and locale. |
| `selection-controls` | Platform-backed checkbox, radio, switch or segmented selection with programmatic labels and states. |
| `progress` | Native accessible progress indicator; determinate only when actual progress exists; reduced-motion alternative. |
| `confirmation-dialog` | Native modal/sheet behavior with trapped accessibility focus, labelled destructive action and cancellation. |
| `action-sheet` | Native presentation/dismissal behavior for contextual app actions; icon artwork is supplied separately. |
| `camera-capture` | OS camera or platform camera API preview and capture controls; actual viewfinder content comes from the camera. |
| `photo-picker` | OS photo picker with user-selected originals; do not reproduce its app icons or synthetic thumbnails in production. |
| `file-open-picker` | OS document-open picker. The OS owns locations, provider icons and file availability. |
| `file-save-picker` | OS create-document/Save to Files sheet; filename, storage providers and availability come from the platform. |
| `camera-permission` | OS camera permission alert; app supplies a truthful purpose string, not a custom imitation. |
| `notification-permission` | OS notification authorization alert. Respect the resulting capability. |
| `system-notification` | Native lock-screen notification; redact medicine/profile details by default and honor platform lock policy. |
| `healthkit-permission` | Apple Health authorization, requested read scopes only: steps, heart rate and weight. |
| `health-connect-permission` | Android Health Connect authorization, requested read scopes only: steps, heart rate and weight. |
| `biometric-authentication` | OS authentication prompt and available passcode fallback; hide all profile and medical content behind it. |
| `share-ios` | UIActivityViewController or the supported native share interface; installed targets and file metadata belong to the OS. |
| `share-android` | Android Sharesheet; recipients and installed apps are generated by the platform. |
| `document-renderer` | Native/PDF-capable renderer for the actual original or generated document; retain zoom, selection and accessible text where supported. |
| `external-settings` | Platform settings handoff for device permissions/security; show only actions that the platform supports. |
| `external-browser` | System browser for verified source-code, license, support or other external links. |
| `screen-reader-feedback` | Native announcement of result/validation/save state without moving focus unexpectedly. |

## PDF coverage

All twelve print designs use the ink SVG lockup, embedded Noto Sans, selectable text, fine vector rules and small category bands. `pdf-01`, `pdf-02` and `pdf-11` map HGB/LDL charts; `pdf-04` maps HGB; `pdf-08` maps steps, daily average heart rate and weight. `pdf-05` may show single-point range positions, not a trend. `pdf-07` uses a categorical seven-day dose timeline generated from its own baseline data. Other pages have no numeric graph. The grayscale proof uses the ink logo and black/gray rules; statuses remain explicit words. Tables, labels, footers and axes are PDF vector primitives. Follow PDF_SPEC for readable type, chart explanations, pagination, tagging, A4/Letter reflow and grayscale.

## Complete screen lookup

Names below are SVG basenames; the JSON stores full paths. `nav` expands to home, records, heart, trends and more. `back` is supplied on focused screens. Listed artwork is available for the semantic role; do not force every decorative motif onto a dense form. Native controls are named above. Source fixtures are marked `source:` and charts `chart:`.

| ID | Reference | Owned vector artwork | Native controls |
|---|---|---|---|
| 001 | Today | foliage; capsule; icons: user, chevron-down, capsule, check, clock, warning, file-text, plus, nav | system-chrome |
| 002 | Welcome | logo:lockup-dark; foliage; records; privacy-device; backdrop-mint; icons: chevron-right, archive | system-chrome |
| 003 | Create your first profile | foliage; records; backdrop-mint; icons: user, calendar, plus | system-chrome, text-input, date-picker |
| 004 | Today without records | foliage; record-empty; backdrop-mint; icons: user, chevron-down, plus, file-text, capsule, nav | system-chrome |
| 005 | Household profiles | icons: users, user, check, plus, settings, close | system-chrome, action-sheet |
| 006 | Edit profile | icons: user, calendar, edit, trash, back | system-chrome, text-input, date-picker |
| 007 | Delete profile confirmation | icons: trash, warning, close | system-chrome, text-input, confirmation-dialog |
| 008 | Opening local records | logo:lockup-dark; foliage; unlock; processing; icons: loading, lock | system-chrome, progress |
| 009 | App locked | logo:mark-dark; foliage; lock; icons: lock, fingerprint, unlock | system-chrome, biometric-authentication |
| 010 | Unlock unsuccessful | logo:mark-dark; lock; icons: lock, warning, refresh | system-chrome, biometric-authentication |
| 011 | Storage recovery | logo:lockup-dark; restore; icons: file-text, warning, refresh, archive | system-chrome |
| 012 | Start fresh confirmation | logo:lockup-dark; lock; icons: warning, archive, close | system-chrome, confirmation-dialog |
| 013 | Recovered records notice | foliage; success; icons: info, close, capsule, check, nav | system-chrome, screen-reader-feedback |
| 014 | Records list | foliage; records; icons: user, chevron-down, search, filter, file-text, plus, warning, nav | system-chrome |
| 015 | No records | foliage; record-empty; backdrop-mint; icons: user, chevron-down, plus, file-text, nav | system-chrome |
| 016 | Search results | record-search; icons: search, close, lab, file-text, nav | system-chrome, text-input |
| 017 | No search matches | record-search; icons: search, close, filter, nav | system-chrome, text-input |
| 018 | Filter records | icons: filter, calendar, chevron-down, check, close | system-chrome, date-picker, selection-controls, action-sheet |
| 019 | Report overview | icons: file-text, lab, check-circle, more, eye, share, back | system-chrome |
| 020 | Original report viewer | source:report-blood-count; icons: file-text, minus, plus, chevron-right, back | system-chrome, document-renderer |
| 021 | Original unavailable | file-error; icons: file-text, warning, upload, back | system-chrome |
| 022 | Delete report | icons: trash, file-text, close | system-chrome, confirmation-dialog |
| 023 | Numeric result detail | logo:lockup-dark; chart:hemoglobin; icons: lab, info, edit, share, file-text, history, back | system-chrome |
| 024 | Text result detail | icons: file-text, info, edit, back | system-chrome |
| 025 | Edit numeric result | icons: calendar, chevron-down, lab, check, back | system-chrome, text-input, date-picker |
| 026 | Edit text result | icons: calendar, file-text, check, back | system-chrome, text-input, date-picker, selection-controls |
| 027 | Result validation errors | icons: calendar, warning, error, back | system-chrome, text-input, date-picker, screen-reader-feedback |
| 028 | Correction history | icons: history, edit, file-text, back | system-chrome |
| 029 | Result no longer available | logo:lockup-dark; record-empty; file-error; icons: file-text, warning, back | system-chrome |
| 030 | Add record source menu | records; icons: camera, photo, file-pdf, edit, close | system-chrome, action-sheet |
| 031 | Camera capture | source:report-blood-count; icons: camera, flash, rotate, close | system-chrome, camera-capture |
| 032 | Photo picker | source:report-blood-count; source:report-october-review; icons: photo, check, close | system-chrome, photo-picker |
| 033 | File picker | icons: folder, file-pdf, search, close | system-chrome, file-open-picker |
| 034 | Camera permission | logo:mark-color; icons: camera | system-chrome, camera-permission |
| 035 | Reading report | processing; ocr-document; source:report-blood-count; icons: file-text, scan, loading, close, back | system-chrome, progress |
| 036 | Import failed | file-error; icons: file-text, warning, refresh, edit, back | system-chrome, screen-reader-feedback |
| 037 | No extracted results | record-empty; source:report-blood-count; icons: file-text, search, edit, back | system-chrome |
| 038 | File too large | logo:lockup-dark; file-error; icons: file-pdf, warning, folder, close | system-chrome |
| 039 | Review imported results | records; ocr-document; icons: file-text, warning, lab, chevron-right, back | system-chrome |
| 040 | Review numeric candidate | logo:lockup-dark; ocr-document; source:report-october-review; icons: file-text, lab, edit, calendar, check, close, back | system-chrome, text-input, date-picker, document-renderer |
| 041 | Review text candidate | ocr-document; source:report-hbsag; icons: file-text, edit, calendar, check, close, back | system-chrome, text-input, date-picker, selection-controls, document-renderer |
| 042 | Ambiguous OCR number | ocr-document; source:ocr-ambiguous-token; icons: search, warning, edit, check, back | system-chrome, text-input, selection-controls, document-renderer, screen-reader-feedback |
| 043 | Collection date picker | icons: calendar, chevron-right, chevron-down, check, close | system-chrome, date-picker, selection-controls |
| 044 | Duplicate result found | icons: copy, file-text, info, check, back | system-chrome |
| 045 | Reject import candidate | icons: close, warning | system-chrome, confirmation-dialog |
| 046 | Review finished | success; backdrop-mint; icons: check-circle, records, plus, back | system-chrome, screen-reader-feedback |
| 047 | Add numeric result | icons: lab, calendar, chevron-down, plus, back | system-chrome, text-input, date-picker, selection-controls |
| 048 | Add text result | icons: file-text, calendar, chevron-down, plus, back | system-chrome, text-input, date-picker, selection-controls |
| 049 | Unsaved changes | icons: warning, close | system-chrome, confirmation-dialog |
| 050 | Trends overview | logo:lockup-dark; foliage; lab-tube; backdrop-lavender; chart:hemoglobin; chart:ldl; icons: user, chevron-down, lab, chevron-right, nav | system-chrome, selection-controls |
| 051 | Test history | logo:lockup-dark; chart:hemoglobin-year; icons: calendar, filter, share, lab, back | system-chrome, date-picker |
| 052 | Qualitative result timeline | logo:lockup-dark; icons: calendar, file-text, history, back | system-chrome |
| 053 | No trend data | trend-empty; backdrop-lavender; icons: lab, file-text, nav | system-chrome |
| 054 | Separate result units | logo:lockup-dark; trend-empty; icons: info, chevron-down, lab, back | system-chrome, selection-controls |
| 055 | Medication schedule | logo:lockup-dark; foliage; capsule; icons: user, chevron-down, capsule, clock, check, plus, nav | system-chrome |
| 056 | No medications | foliage; medication-calendar; icons: user, chevron-down, capsule, plus, nav | system-chrome |
| 057 | Medication detail | capsule; icons: capsule, clock, calendar, edit, pause, history, more, back | system-chrome |
| 058 | Add medication | capsule; icons: capsule, clock, chevron-down, back | system-chrome, text-input, time-picker |
| 059 | Edit medication | capsule; icons: capsule, clock, calendar, chevron-down, back | system-chrome, text-input, date-picker, time-picker |
| 060 | Medication validation | capsule; icons: capsule, clock, warning, error, back | system-chrome, text-input, time-picker, screen-reader-feedback |
| 061 | Medication paused | capsule; icons: capsule, pause, play, edit, back | system-chrome |
| 062 | Stop medication confirmation | icons: capsule, pause, warning, close | system-chrome, confirmation-dialog |
| 063 | Delete medication confirmation | icons: capsule, trash, warning, close | system-chrome, confirmation-dialog |
| 064 | Dose action sheet | capsule; icons: capsule, check, skip, clock, close | system-chrome, action-sheet |
| 065 | Snooze reminder | medication-calendar; icons: clock, check, close | system-chrome, time-picker, selection-controls, action-sheet |
| 066 | Dose recorded | capsule; success; icons: capsule, check-circle, clock, history, nav | system-chrome, screen-reader-feedback |
| 067 | Dose history | logo:lockup-dark; medication-calendar; chart:dose-history; icons: calendar, check, skip, clock, chevron-right, back | system-chrome, date-picker |
| 068 | Reminder permission introduction | notification-bell; icons: bell, back | system-chrome |
| 069 | Notification permission | logo:mark-color; notification-bell; icons: bell | system-chrome, notification-permission |
| 070 | Notifications disabled | notification-off; icons: bell-off, settings, clock, back | system-chrome, external-settings |
| 071 | Lock screen reminder | logo:mark-color; icons: bell, check, skip, clock | system-chrome, system-notification |
| 072 | Device measurements | logo:lockup-dark; foliage; activity-shoe; heart-rate; weight-scale; chart:steps; icons: user, chevron-down, steps, pulse, weight, refresh, calendar, connect, nav | system-chrome, selection-controls |
| 073 | Heart rate detail | logo:lockup-dark; heart-rate; chart:heart-rate; icons: pulse, calendar, info, connect, back | system-chrome |
| 074 | No device measurements | device-link; icons: device, connect, back | system-chrome |
| 075 | Link device data | device-link; icons: device, user, connect, check, back | system-chrome, selection-controls |
| 076 | Device linked to another profile | device-link; icons: device, users, warning, refresh, back | system-chrome |
| 077 | Apple Health authorization | logo:mark-color; icons: heart, steps, pulse, weight | system-chrome, selection-controls, healthkit-permission |
| 078 | Health Connect authorization | icons: steps, pulse, weight | system-chrome, selection-controls, health-connect-permission |
| 079 | Importing measurements | logo:lockup-dark; device-link; processing; icons: device, loading, steps, pulse, weight, back | system-chrome, progress |
| 080 | Health service unavailable | device-link; icons: device, warning, external-link, back | system-chrome, external-settings |
| 081 | Health permission denied | device-link; icons: device, warning, settings, back | system-chrome, external-settings |
| 082 | More and settings | foliage; icons: user, users, share, archive, lock, connect, chat, chip, sun, language, help, info, chevron-right, nav | system-chrome |
| 083 | Privacy and storage | privacy-device; backdrop-mint; icons: device, lock, archive, share, external-link, chevron-right, back | system-chrome |
| 084 | Backups hub | backup; backdrop-mint; icons: archive, upload, download, lock, user, back | system-chrome |
| 085 | Create backup | backup; icons: user, key, eye, eye-off, lock, info, back | system-chrome, secure-input |
| 086 | Backup passphrase errors | backup; icons: key, eye, eye-off, warning, error, back | system-chrome, secure-input, screen-reader-feedback |
| 087 | Encrypting backup | backup; processing; icons: archive, lock, loading, back | system-chrome, progress |
| 088 | Backup ready | backup; success; icons: archive, check-circle, download, share, back | system-chrome, file-save-picker, screen-reader-feedback |
| 089 | Open encrypted backup | restore; icons: archive, key, eye, eye-off, folder, back | system-chrome, secure-input, file-open-picker |
| 090 | Incorrect passphrase | restore; icons: archive, key, eye, eye-off, warning, back | system-chrome, secure-input, screen-reader-feedback |
| 091 | Invalid backup file | file-error; icons: archive, warning, folder, refresh, back | system-chrome, screen-reader-feedback |
| 092 | Review backup contents | records; restore; icons: user, records, capsule, chat, pulse, check-circle, back | system-chrome |
| 093 | Profile already exists | restore; icons: users, warning, copy, archive | system-chrome, confirmation-dialog |
| 094 | Restore completed | restore; success; backdrop-mint; icons: check-circle, user, records, back | system-chrome, screen-reader-feedback |
| 095 | Restore unsuccessful | file-error; icons: archive, warning, refresh, folder, back | system-chrome, screen-reader-feedback |
| 096 | App lock settings | logo:lockup-dark; lock; icons: lock, fingerprint, window, back | system-chrome, selection-controls, biometric-authentication |
| 097 | App lock unavailable | lock; icons: lock, fingerprint, settings, warning, back | system-chrome, external-settings |
| 098 | Appearance preferences | appearance; icons: sun, moon, settings, text-size, motion, check, back | system-chrome, selection-controls |
| 099 | Language preference | icons: language, check, back | system-chrome, selection-controls |
| 100 | Assistant unavailable | logo:lockup-dark; assistant-records; backdrop-lavender; icons: chat, chip, records, back | system-chrome |
| 101 | Assistant ready | assistant; backdrop-lavender; icons: chat, chip, check-circle, file-text, send, back | system-chrome, text-input, selection-controls |
| 102 | Assistant conversation | logo:mark-color; assistant; icons: chat, chip, check-circle, file-text, send, copy, back | system-chrome, text-input |
| 103 | Assistant generating | assistant; processing; icons: chat, chip, file-text, loading, send, back | system-chrome, text-input, progress |
| 104 | Assistant error | assistant; icons: chat, warning, refresh, chip, send, back | system-chrome, text-input, screen-reader-feedback |
| 105 | No model packs | model-download; backdrop-lavender; icons: chip, storage, records, back | system-chrome |
| 106 | Local model library | model-download; backdrop-lavender; icons: chip, check-circle, download, storage, chevron-right, back | system-chrome |
| 107 | Model details | model-download; backdrop-lavender; icons: chip, download, storage, language, info, back | system-chrome |
| 108 | Model downloading | model-download; backdrop-lavender; icons: chip, download, pause, offline, back | system-chrome, progress |
| 109 | Verifying model | model-download; processing; backdrop-lavender; icons: chip, loading, check-circle, back | system-chrome, progress |
| 110 | Model loaded | model-download; success; icons: chip, check-circle, chat, chevron-right, back | system-chrome |
| 111 | Model installation failure | model-download; file-error; icons: chip, warning, refresh, trash, back | system-chrome, screen-reader-feedback |
| 112 | Delete model | model-download; icons: chip, trash, warning, close | system-chrome, confirmation-dialog |
| 113 | Optional feature activation | icons: check-circle, refresh, info, back | system-chrome |
| 114 | Activation verification failure | file-error; icons: warning, refresh, info, back | system-chrome, screen-reader-feedback |
| 115 | Create export | export; icons: file-pdf, lab, capsule, pulse, file-csv, user, chevron-right, back | system-chrome |
| 116 | Export options | export; icons: user, calendar, check, chevron-down, file-pdf, back | system-chrome, date-picker, selection-controls |
| 117 | Select results for export | icons: check, file-text, lab, warning, back | system-chrome, selection-controls |
| 118 | PDF preview | logo:lockup-ink; export; chart:hemoglobin; chart:ldl; icons: back, file-pdf, share, edit, plus, minus | system-chrome, document-renderer |
| 119 | Preparing export | export; processing; icons: file-pdf, loading, back | system-chrome, progress |
| 120 | Export ready | logo:lockup-ink; export; success; chart:hemoglobin; chart:ldl; icons: file-pdf, check-circle, download, share, back | system-chrome, file-save-picker, screen-reader-feedback |
| 121 | CSV preview | export; icons: file-csv, download, back | system-chrome, file-save-picker |
| 122 | No exportable results | export; icons: file-text, info, warning, back | system-chrome |
| 123 | Export failed | file-error; icons: file-pdf, warning, refresh, edit, back | system-chrome, screen-reader-feedback |
| 124 | iOS share sheet | logo:lockup-ink; chart:hemoglobin; chart:ldl; icons: share, file-pdf, print, folder | system-chrome, share-ios |
| 125 | Android share sheet | icons: share, file-pdf, download | system-chrome, share-android |
| 126 | Save file picker | logo:lockup-dark; icons: folder, file-pdf, close | system-chrome, file-save-picker |
| 127 | Saving failed | export; icons: file-pdf, warning, refresh, share, back | system-chrome, file-save-picker, screen-reader-feedback |
| 128 | About Vezuna | logo:lockup-dark; foliage; records; backdrop-mint; icons: code, external-link, users, file-text, info, back | system-chrome, external-browser |
| 129 | Privacy policy reader | icons: file-text, external-link, back | system-chrome, external-browser |
| 130 | Third-party notices | icons: search, file-text, external-link, chevron-right, back | system-chrome, text-input, external-browser |
| 131 | Help and open-source contribution | icons: file-text, archive, file-pdf, help, code, external-link, chevron-right, back | system-chrome, external-browser |
| 132 | Today dark | foliage; capsule; icons: user, chevron-down, capsule, check, clock, warning, file-text, plus, nav | system-chrome |
| 133 | Records dark | foliage; icons: user, chevron-down, search, filter, file-text, plus, warning, nav | system-chrome |
| 134 | Result detail dark | logo:lockup-dark; chart:hemoglobin; icons: lab, info, edit, share, file-text, history, back | system-chrome |
| 135 | Edit result dark | icons: calendar, chevron-down, lab, check, back | system-chrome, text-input, date-picker |
| 136 | Assistant dark | assistant; icons: chat, chip, check-circle, file-text, send, copy, back | system-chrome, text-input |
| 137 | More dark | foliage; icons: user, users, share, archive, lock, connect, chat, chip, sun, language, help, info, chevron-right, nav | system-chrome |
| 138 | Export preview dark | logo:lockup-ink; export; chart:hemoglobin; chart:ldl; icons: file-pdf, share, edit, plus, minus, back | system-chrome, document-renderer |
| 139 | Portuguese import review | ocr-document; source:report-blood-count; icons: file-text, lab, edit, calendar, check, close, back | system-chrome, text-input, date-picker, selection-controls, document-renderer |
| 140 | Spanish medication screen | foliage; capsule; icons: user, chevron-down, capsule, clock, check, plus, nav | system-chrome |
| 141 | Large text Today | foliage; capsule; icons: user, chevron-down, capsule, check, clock, nav | system-chrome |
| 142 | Tablet records and detail | logo:lockup-dark; foliage; chart:hemoglobin; icons: user, search, filter, file-text, lab, plus, edit, share, nav | system-chrome, text-input |
| 143 | Tablet source and review | source:report-october-review; icons: file-text, lab, edit, calendar, check, close, minus, plus, back | system-chrome, text-input, date-picker, selection-controls, document-renderer |
| 144 | Component state reference | logo:lockup-color; logo:mark-color; chart:hemoglobin; icons: check, check-circle, warning, error, loading, close, chevron-right, user | system-chrome, selection-controls, progress |
| 145 | Device authentication | logo:mark-dark; lock; icons: lock, fingerprint | system-chrome, biometric-authentication |
| 146 | Daily reminder time | capsule; icons: capsule, clock, info, back | system-chrome, time-picker |
| 147 | Cannot delete last profile | icons: user, users, warning, edit, close | system-chrome, confirmation-dialog |
| 148 | Review item unavailable | file-error; icons: file-text, info, back | system-chrome, screen-reader-feedback |
| 149 | Extraction details | ocr-document; source:report-october-review; icons: file-text, scan, info, chevron-right, back | system-chrome, document-renderer |
| 150 | Save success feedback | success; chart:hemoglobin; icons: lab, edit, share, check-circle, close, loading, file-text, back | system-chrome, progress, screen-reader-feedback |
| pdf-01 | Health summary | logo:lockup-ink; chart:hemoglobin; chart:ldl; icons: lab, capsule, info | Print primitives only |
| pdf-02 | Lab history, first page | logo:lockup-ink; chart:hemoglobin; chart:ldl; icons: lab | Print primitives only |
| pdf-03 | Lab history, continuation | logo:lockup-ink; icons: lab, file-text, capsule, info | Print primitives only |
| pdf-04 | Single-test history | logo:lockup-ink; chart:hemoglobin; icons: lab | Print primitives only |
| pdf-05 | Numeric and text results | logo:lockup-ink; icons: lab, file-text | Print primitives only |
| pdf-06 | Medication record | logo:lockup-ink; icons: capsule, clock, calendar | Print primitives only |
| pdf-07 | Medication dose history | logo:lockup-ink; icons: capsule, check, skip, clock | Print primitives only |
| pdf-08 | Device measurements | logo:lockup-ink; chart:steps; chart:heart-rate; chart:weight; icons: steps, pulse, weight | Print primitives only |
| pdf-09 | Source appendix | logo:lockup-ink; icons: file-text, info | Print primitives only |
| pdf-10 | Sparse report | logo:lockup-ink; icons: info | Print primitives only |
| pdf-11 | Portuguese lab history | logo:lockup-ink; chart:hemoglobin; chart:ldl; icons: lab | Print primitives only |
| pdf-12 | Letter grayscale proof | logo:lockup-ink; icons: lab, file-text, capsule, info | Print primitives only |

## Reference-image corrections

These deliberate implementation corrections are also stored with their screen entries; the image generation metadata retains the visual review notes.

- **020 — Original report viewer:** The fictional SVG is a development excerpt, not a replacement for the full stored original PDF.
- **028 — Correction history:** Revision history uses a vector timeline and live before/after text. It is not another clinical chart reading.
- **041 — Review text candidate:** HBsAg is an isolated text-review variant, separate from the three pending October candidates.
- **042 — Ambiguous OCR number:** Ambiguous token 1,234 must remain unresolved until the person verifies its source meaning.
- **052 — Qualitative result timeline:** One text observation: use a timeline row, no numeric chart or invented prior observation.
- **054 — Separate result units:** Only one value in this isolated unit variant: render a single labelled point, never the multi-reading HGB fixture.
- **067 — Dose history:** Dated event markers have a textual legend; no score or adherence percentage. This isolated post-action fixture includes the 1 Nov taken event. Export pdf-07 uses the separate 25–31 Oct baseline.
- **073 — Heart rate detail:** Seven daily averages dated 25–31 Oct. Daily-average labels are explicit; no fabricated hourly observations. Long-range controls preserve missing history.
- **077 — Apple Health authorization:** Health app branding and authorization controls are OS-owned; generic app-side device/link vectors cover the preflight UI.
- **078 — Health Connect authorization:** Health Connect branding and authorization controls are OS-owned; do not redraw third-party symbols from the generated mockup.
- **088 — Backup ready:** Compute final backup size; structured backups omit original report files.
- **092 — Review backup contents:** Preview structured backup contents only. Original report files are not restored from a .vhbak file.
- **109 — Verifying model:** Verification in progress needs a spinner; show a completed check only after verification succeeds.
- **111 — Model installation failure:** Omit invented 1.2 GB metadata. Show the removal confirmation only after the user chooses Remove file.
- **112 — Delete model:** Any displayed model size comes from the actual catalog, not the generated image.
- **115 — Create export:** Final corrected reference uses a single PDF-template selection and a separate CSV action.
- **118 — PDF preview:** White PDF page remains a document-renderer surface. Updated health summary contains HGB/LDL dated graphs, first/previous changes and four latest result rows, matching pdf-01.
- **119 — Preparing export:** Final corrected loading reference uses a spinner and offers no premature Save/Share controls.
- **124 — iOS share sheet:** Actual target apps and computed file size come from the OS; never ship the illustrated app list.
- **125 — Android share sheet:** Actual target apps and computed file size come from the OS; never ship the illustrated app list.
- **129 — Privacy policy reader:** Use the approved repository privacy policy, not generated paraphrase in the image.
- **133 — Records dark:** All navigation surfaces follow dark tokens, including any light-looking bar in the raster reference.
- **136 — Assistant dark:** Omit the incidental generated PDF attachment; the baseline assistant has no attachment-generation requirement.
- **138 — Export preview dark:** Dark app chrome surrounds the updated white pdf-01 summary with two history graphs; do not invert the exported PDF.
- **139 — Portuguese import review:** Final corrected reference includes the source crop, editable labelled fields and Reject action. HGB is an isolated variant.
- **142 — Tablet records and detail:** Tablet shows six confirmed reports and HGB detail with dated history, range selection and first/previous changes. Use the exact official logo SVG.
- **143 — Tablet source and review:** Use the glucose source fixture with 30 Oct collection and 1 Nov 09:10 import metadata; no invented patient IDs.
- **144 — Component state reference:** Reference board only. States use live components, accessible focus and tokens, not image slices.
- **pdf-05 — Numeric and text results:** Single-point range displays are optional position indicators, not trends. Construct from the selected original value and its recorded source reference.
- **pdf-07 — Medication dose history:** Seven-day categorical dose timeline25–31 Oct: five taken, one skipped, one missed. Do not use the app067 eight-day post-action fixture as this page's data.
- **pdf-10 — Sparse report:** Empty period is a scope statement, not a medical finding. Use an information box, not a clinical status icon.
- **pdf-12 — Letter grayscale proof:** Ink lockup and black/gray primitives only; no category color survives in this proof.

## Validation

The map contains 162 unique IDs matching the screen manifest, with 121 distinct referenced SVG files. Each existing SVG was parsed as XML and checked for embedded raster image elements. `generation/build_asset_map.py` rebuilds this lookup and records any missing assets in the JSON validation field. A complete deliverable has an empty `missing_files` array and an empty `embedded_raster_files` array.

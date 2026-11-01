# Physical-device validation

Run this checklist on real hardware for every release candidate (RC). Simulators and emulators do not enforce data protection, exact-alarm policy, Doze/background limits, TalkBack/VoiceOver behavior or real OCR/camera input, so this cannot be replaced by CI.

## Setup

Record before starting, in the results table at the end:

- RC version name and build number, commit SHA, tester, date.
- Android: one device on the minimum supported API (26) if available, and one on the latest API. iOS: one device on the minimum (18.5) and one on the latest.
- Use a clean install for sections 1-9, then an upgrade install over the previous release for section 10.
- Use only synthetic data. Never use real health records or private samples.
- Mark each step PASS, FAIL (link an issue) or N/A (state why). Any FAIL in a section marked blocker stops the release.

## 1. Notifications and reminders (blocker)

Create a medication with a daily schedule ~3 minutes ahead and one weekly schedule.

| # | Step | Expected |
| --- | --- | --- |
| 1.1 | Deny notification permission, then open Reminders | Clear message, no crash; granting later via settings enables reminders |
| 1.2 | Grant permission, wait for the daily reminder | Notification arrives within 1 minute of the scheduled time |
| 1.3 | Android 12+: revoke "Alarms & reminders" (exact alarm) in system settings, reopen the app | App explains the limitation or falls back; re-granting restores exact timing |
| 1.4 | Lock the screen; wait for a reminder | Appears on lock screen without showing more detail than the privacy setting allows |
| 1.5 | Lock-screen actions: tap Taken, then repeat with Snooze (10 min) | Dose recorded as taken / reminder reappears after 10 minutes |
| 1.6 | Reboot the device, do not open the app | Pending reminders still fire after reboot (Android: boot receiver; iOS: pending requests retained) |
| 1.7 | Change device timezone (e.g. +3h), reopen app | Reminders move to the same local wall-clock time |
| 1.8 | Toggle daylight-saving boundary date or manual clock change across midnight | No duplicate or missing reminder for the day |
| 1.9 | Edit a medication name and schedule time | Old reminder is replaced, not duplicated |
| 1.10 | Delete the medication | No further reminders fire |
| 1.11 | Force-stop (Android) / swipe away (iOS), wait for a reminder | Reminder still fires |
| 1.12 | Battery saver / Low Power Mode enabled | Reminder still fires, possibly delayed; delay documented in results |

## 2. Health Connect / HealthKit

| # | Step | Expected |
| --- | --- | --- |
| 2.1 | Open Health import, deny all permissions | Message, no crash, no data imported |
| 2.2 | Grant a subset of types | Only granted types imported |
| 2.3 | Add synthetic readings in Health Connect / Health app, import | Readings appear once, with correct units and dates |
| 2.4 | Import again with no new data | No duplicates (sync mark respected) |
| 2.5 | Revoke permission in system settings, reopen | App handles revocation, previously imported records remain |
| 2.6 | Device without Health Connect (Android) | Prompt to install/update or clear unavailable message |

## 3. OCR and lab import

| # | Step | Expected |
| --- | --- | --- |
| 3.1 | Photograph a printed synthetic lab report (EN) | Values, units and reference ranges extracted; low-confidence rows flagged for review |
| 3.2 | Same with PT-BR report (decimal comma, `1.234,5`, `250.000`) | Numbers are not misread; ambiguous thousands flagged |
| 3.3 | Import a PDF with text layer and a scanned PDF | Both import; scanned uses OCR |
| 3.4 | Cancel the picker / revoke camera permission | Clear message, no crash |
| 3.5 | Review screen: edit a value, save | Saved record matches edited value |
| 3.6 | Import the same file twice | Duplicate handling matches spec, no silent data loss |

## 4. Backup and restore, cross-platform (blocker)

| # | Step | Expected |
| --- | --- | --- |
| 4.1 | Android: create a passphrase backup with non-ASCII passphrase | File saved, size > 0 |
| 4.2 | iOS: restore the Android backup | All profiles/records restored; same counts |
| 4.3 | iOS: create backup, Android: restore it | Same as above |
| 4.4 | Restore with wrong passphrase | Rejected, existing data untouched |
| 4.5 | Restore a file with one flipped byte | Rejected as corrupt, existing data untouched |
| 4.6 | NFC vs NFD form of the same passphrase | Both open the backup |
| 4.7 | Restore over existing data | User warned before replace; result matches the backup |
| 4.8 | Check the saved backup is excluded from OS cloud backup where applicable | Not present in device backup contents |

## 5. Vault recovery (blocker)

| # | Step | Expected |
| --- | --- | --- |
| 5.1 | Cold-start after reboot while still locked (iOS before first unlock, Android direct boot) | App does not treat the vault as empty or recreate the key |
| 5.2 | Android: clear biometrics/screen lock changes, then open app | Vault still opens, or recovery screen appears; no silent data loss |
| 5.3 | Reach the recovery screen (debug build with corrupted vault file) | Retry, restore-from-backup and reset-aside options shown |
| 5.4 | Choose reset | Old vault is moved aside, never deleted; app starts empty |

## 6. Model install and AI (premium)

| # | Step | Expected |
| --- | --- | --- |
| 6.1 | Without premium, open model settings | Records and export remain fully usable; model activation blocked |
| 6.2 | With license, install the model pack on Wi-Fi | Progress shown, checksum verified, install completes |
| 6.3 | Interrupt install (airplane mode / kill app) and retry | Resumes or restarts cleanly, no partial model active |
| 6.4 | Low storage | Clear message before or during install |
| 6.5 | Ask a question with model loaded | Answer produced offline; airplane mode on |
| 6.6 | Remove the model | Storage reclaimed, AI disabled, records unaffected |

## 7. Licensing

| # | Step | Expected |
| --- | --- | --- |
| 7.1 | Fresh install, no purchase | Free tier works fully for records and export |
| 7.2 | Purchase / restore purchase (store sandbox account) | Premium activates |
| 7.3 | Go offline for less than the offline grace period | Premium stays active |
| 7.4 | Go offline beyond the grace period (adjust device date only on a test device) | Premium becomes inactive; records still accessible |
| 7.5 | Refund or revoke in sandbox, go online | Premium deactivates after next check |

## 8. Localization (EN, PT-BR, ES)

Repeat per language (change device language, relaunch).

| # | Step | Expected |
| --- | --- | --- |
| 8.1 | Walk dashboard, records, medications, import, export, settings, privacy, notices | No English fallback, no truncated or clipped text, no raw keys |
| 8.2 | Dates, decimals, plurals (e.g. "1 reminder"/"2 reminders") | Locale-correct formatting and plural forms |
| 8.3 | Notification text and lock-screen actions | Localized |
| 8.4 | Exported PDF/CSV headings | Localized |

## 9. Accessibility

| # | Step | Expected |
| --- | --- | --- |
| 9.1 | TalkBack (Android) / VoiceOver (iOS) through onboarding, add record, add medication, export | Every control has a meaningful label, order is logical, no focus traps |
| 9.2 | Charts and status indicators | Have text alternatives; meaning is not conveyed by color alone |
| 9.3 | Largest system font / 200% | No clipped or overlapping text, all actions reachable by scrolling |
| 9.4 | Display zoom / bold text (iOS), display size (Android) | Layout intact |
| 9.5 | Touch targets | At least 48dp / 44pt |
| 9.6 | Dark mode and high contrast | Text contrast stays readable |
| 9.7 | Reduce motion | No essential information lost |

## 10. Upgrade and install hygiene

| # | Step | Expected |
| --- | --- | --- |
| 10.1 | Upgrade over the previous public release with existing data | Data intact, reminders still scheduled |
| 10.2 | Uninstall and reinstall | Starts clean; restore from backup works |
| 10.3 | Release build identity | Package/bundle ID `app.vezuna`, version and build number match the RC |
| 10.4 | Release build has no debug menus or logging of health data | Confirmed via logcat / Console during a full pass |

## Results template

Copy this table into the release issue or PR for each RC.

| Field | Value |
| --- | --- |
| RC version / build / commit | |
| Date / tester | |
| Android devices (model, API) | |
| iOS devices (model, OS) | |

| Section | Result (PASS / FAIL / N/A) | Issues / notes |
| --- | --- | --- |
| 1 Notifications and reminders | | |
| 2 Health Connect / HealthKit | | |
| 3 OCR and lab import | | |
| 4 Backup and restore | | |
| 5 Vault recovery | | |
| 6 Model install and AI | | |
| 7 Licensing | | |
| 8 Localization EN / PT-BR / ES | | |
| 9 Accessibility | | |
| 10 Upgrade and install hygiene | | |

Sign-off: release only when sections 1, 4 and 5 are PASS and every FAIL elsewhere has an accepted issue.

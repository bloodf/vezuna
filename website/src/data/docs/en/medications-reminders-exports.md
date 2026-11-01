---
title: Medications and exports
description: Record medication schedules, use local reminders, mark dose actions, and export confirmed information.
lang: en
order: 3
audience: user
translationKey: medications-reminders-exports
essential: false
---

## Add a medication schedule

Create a medication schedule under the relevant household profile. Keep each person’s medication records separate from other profiles.

For a fictional walkthrough, add **Demo Medicine**, enter a dose exactly as shown on a synthetic label, choose a reminder time, and use the dose actions to mark a scheduled dose as **taken**, **skipped**, or **snoozed**. This example is not dosing guidance.

Vezuna records the schedule you enter. It does not recommend treatment, choose doses, or change medication doses.

## Local reminders

Reminders are delivered as local notifications. Android includes rescheduling after reboot, time or timezone changes, and package replacement. Allow notification permission if you want these reminders.

Compilation and scheduling tests do not prove actual notification arrival. Real elapsed-time delivery, including reboot and timezone scenarios, remains a physical-device release check. Do not treat an unreleased build as a guaranteed medication alarm.

## Export confirmed information

Vezuna can generate:

- **PDF reports** with exam sections, result and reference columns, status indicators, history tables, and charts.
- **CSV exports** for confirmed data.
- **Passphrase-encrypted profile backups** that restore on this or another phone.

Exports are generated on demand and handed to the operating system’s document picker or share sheet. Only confirmed results appear in clinician exports.

Shared PDF and CSV files are plaintext and are no longer protected by the app’s encrypted vault. Records, backups, and exports never require an AI model or AI licensing.

## Delete exported copies

Removing Vezuna does not remove files you already exported or shared. Delete those copies through the apps or storage locations where you saved them. The app cannot manage destinations outside its own sandbox.

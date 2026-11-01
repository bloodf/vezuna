---
title: Getting started
description: Create a synthetic first profile, import and check a report, and understand supported devices and app identity.
lang: en
order: 1
audience: user
translationKey: getting-started
essential: true
---

Vezuna organizes household health records on Android and iPhone without an app-owned account or login. iPhone requires **iOS 18.5 or later**. The current Android source minimum is **Android 8.0 (API 26)**. Release checks on physical devices are still pending.

## Create your first synthetic profile

For a first-use walkthrough, use entirely fictional information:

1. Create a household profile named **Alex Example**.
2. Import a synthetic laboratory image or PDF containing **Hemoglobin — 13.2 g/dL**. Copy the test date and reference range from that synthetic report.
3. Review the extracted draft. Correct the test name, value, unit, reference range, or date if necessary, then confirm the result.
4. Import a second synthetic report for the same profile containing **Hemoglobin — 13.8 g/dL**, review it, and confirm it.
5. Open the comparison or trend view to see the two confirmed results.

OCR results remain unconfirmed drafts until you review them. Only confirmed results appear in trends and clinician exports. Always check imported values against the original report.

## Use synthetic data for testing and support

Never upload real medical records to GitHub issues, pull requests, security reports, public documentation, or external AI and analysis services. Do not attach real reports, prescriptions, screenshots of records, vault files, backups, or documents derived from real patient data. Use fictional records for demonstrations, tests, and support requests.

## Optional health imports

With your permission, Vezuna can read heart rate, steps, and weight from Health Connect on Android or HealthKit on iPhone. It does not request write access. If permission is denied, manually entered records remain usable.

Camera and selected-image access support report import. Notification permission supports local medication reminders. Each capability can be understood separately from health access.

## App identity and existing installations

The current Android application ID and iOS bundle ID are `app.vezuna`. Changing an app ID does **not** transfer another app’s sandbox or Android Keystore keys. A build with the new ID installs separately; the earlier installation is not removed, and its data is not moved or deleted.

To move records to a new phone or a differently identified build, create a passphrase backup and restore it there. Read [Privacy and data](/docs/privacy-and-data) before relying on a backup or changing builds.

## What Vezuna does not do

Vezuna organizes the records you provide. It does not diagnose conditions, recommend treatment, choose doses, or change doses. Imported text can be wrong, which is why review and confirmation are required.

---
title: Laboratory records
description: Import camera images and PDFs, review OCR drafts, confirm values, and compare laboratory results over time.
lang: en
order: 2
audience: user
translationKey: lab-records
essential: false
---

## Import a report

Vezuna accepts camera captures, images, and PDFs. It first extracts embedded text when available and otherwise uses on-device OCR: ML Kit on Android and PDFKit/Vision on iOS. Imported source material is processed locally; camera captures are staged in the app cache.

Keep the original report available while reviewing. An imported document is a source for a draft, not proof that the extracted fields are correct.

## Review before confirming

Each import produces draft candidates for:

- Test name
- Value
- Unit
- Reference range
- Test date

Compare every field with the original report and correct any errors. Decimal-comma input is localized while the stored value remains canonical. Duplicate candidates are fingerprinted, and confirmed records retain revision history.

A draft does not enter your confirmed record automatically. Check units and report-specific reference ranges as carefully as numeric values.

## Compare confirmed results

Confirm at least two results for the same test to compare values over time. For a synthetic demonstration, use **Hemoglobin — 13.2 g/dL** in the first report and **Hemoglobin — 13.8 g/dL** in the second, with units and reference ranges copied from the corresponding fictional reports.

Only confirmed results appear in comparisons, trend graphs, and clinician exports. Only results with matching normalized units are graphed together. These tools organize observations; they do not interpret a diagnosis or recommend treatment.

## Prepare a clinician copy

PDF reports can include exam sections, result and reference columns, status indicators, history tables, and charts. CSV export provides confirmed data in a portable tabular format. Both are generated on demand and shared through the operating system.

Shared PDF and CSV files are plaintext. Choose the destination carefully and read [Privacy and data](/docs/privacy-and-data) before sharing.

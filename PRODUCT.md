# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

## Users

People managing their own and family members’ health records on one phone, including children and parents. Clinicians receive user-generated exports for discussion, not automated recommendations.

## Product Purpose

Vezuna keeps household health records understandable and available entirely on-device: lab import/OCR, confirmed results, trends, medication reminders, local AI explanations, and doctor-ready exports.

## Positioning

A paid household health vault with no application server, no health-data cloud, and fully local GGUF inference. Records and exports remain available regardless of AI/model licensing.

## Operating Context

Users scan or import Brazilian Portuguese and other lab reports, review extracted values, track longitudinal results, receive medication reminders, and share PDF/CSV reports through native OS sharing. The interface follows the device language in English, Portuguese, or Spanish, with English fallback. Store services handle purchase verification and model-pack delivery only.

## Capabilities and Constraints

Kotlin Multiplatform + Compose Multiplatform; native HealthKit/Health Connect, OCR, notifications, encrypted storage, and share sheets. No health-data HTTP client. No diagnosis, treatment, dose changes, or invented reference ranges. iOS runtime and store-hosted assets still require account/device validation.

## Brand Commitments

The visible product name is Vezuna in every language. The supplied logo, native icons, semantic palette, and local Noto Sans 400/700 fonts define the interface without changing the application ID, bundle ID, storage keys, routes, clinical behavior, or exports. The taglines are “Your health, in one place.”, “Sua saúde, em um só lugar.”, and “Tu salud, en un solo lugar.” Doctor exports retain their compact clinical structure rather than becoming marketing assets.

## Evidence on Hand

Synthetic de-identified fixtures under `fixtures/lab_importer/`; a working Android emulator and generated clinical PDFs under ignored `build/qa/`.

## Product Principles

- Health data stays on the device by default.
- OCR is a draft until the user confirms it.
- Dense clinical information must remain readable and actionable.
- Motion and visual design support orientation and feedback, never obscure medical facts.
- Records and export never depend on AI availability or licensing.
- Localization changes presentation, never persisted clinical values or source data.

## Accessibility & Inclusion

Readable contrast and text sizes, meaningful labels, native touch targets, accessible tables alongside charts, and animations that are brief and non-blocking. English, Portuguese, and Spanish layouts allow long translations and enlarged text; right-to-left layout never mirrors the logo, numeric content, or charts.

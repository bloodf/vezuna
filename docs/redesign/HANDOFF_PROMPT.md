# Copy this prompt into the implementation agent

You are redesigning Vezuna, an existing open-source offline household health app. Implement the full redesign in the existing repository using this image/specification pack. This is a native Kotlin Multiplatform and Compose app with native Android/iOS integrations. Do not replace it with a website, React Native app, or static screenshot shell.

The owner wants a lively, colorful, modern app that remains simple to use. The approved direction is **Vezuna Living Color**: deep pine structure, vivid mint records, coral care, lavender trends and citron device activity. Avoid a pale white-card redesign. Use the actual logo and local Noto Sans assets from the supplied brand folder. All custom artwork must use the supplied editable SVG vector library; no rasterized UI assets.

This is the updated handoff. The official Vezuna symbol has two rounded curved strokes forming an open V around a circular dot. Use the exact SVG paths in assets/brand/logos; never recreate the logo from generated pixels, substitute a leaf/sprout, or typeset a replacement wordmark. Botanical artwork is decoration only. Preserve the supplied lockup proportions and use the appropriate light, dark or ink variant.

## Read the pack

Start with README.md and SCREEN_INDEX.md. Inspect images/app/001-today.png, 014-records.png, 055-care.png, 050-trends.png, 082-more.png and the PDF images, then inspect each relevant image before implementing its workflow. Use index.html to browse the entire pack. screen-manifest.json maps each image to its purpose and scope.

Read specs/DESIGN_SYSTEM.md, specs/FLOW_MAP.md, specs/SOURCE_AUDIT.md, specs/IMPLEMENTATION_GUIDANCE.md, specs/DATA_AND_COPY.md, specs/ACCESSIBILITY_AND_PLATFORM.md, specs/GRAPH_GUIDANCE.md and specs/PDF_SPEC.md. specs/RESEARCH.md explains the visual research. If raster copy or chart geometry conflicts with the written specification or actual domain data, the written specification and actual domain data win. Rebuild every element as native accessible UI, never embed a whole screenshot as a screen.

## Scope

Implement the user-visible screens and states in the manifest, grouped by complete workflows. The five root destinations, in order, are Today, Records, Care, Trends and More. Import is a contextual action. Assistant and model management live under More. Maintain clear profile ownership everywhere.

The status field distinguishes:

- redesign: existing capability receiving a new presentation.
- proposed: new interaction, screen, export option or adaptive behavior that needs real implementation.
- conditional: a real capability whose visible availability depends on configured model assets or device support.
- system: OS-owned picker, permission, authentication, notification or share handoff. Invoke the real native interface; do not build a replica.

Some redesign images contain small proposed additions. Inspect the source audit and image description rather than assuming the status means every displayed control already exists. Treat the manifest as a bounded complete screen-and-state specification, not a request to invent accounts, server sync, diagnoses or unrelated health features.

The user authorizes implementing proposed UX in this pack, but functionality must be real. If an integration cannot run in this environment, implement its truthful unavailable state and document the remaining external prerequisite. Do not fabricate model catalogs, store entitlement, device permission, export success or clinical data to make a screenshot look complete.

## Protect behavior and data

Keep encrypted local storage, household isolation, reviewed-versus-unreviewed results, original values/units/references, collection dates, correction history, duplicate protection, medication schedules and recorded dose events. Preserve EN/PT/ES and native integrations. No network service for health records. Optional AI access must never gate records, backups or exports.

The default model catalog is empty. Unavailable is a valid default. Model-ready screens are conditional examples, not evidence of working store assets. App language and theme currently follow the device; explicit in-app preferences are proposed.

Portable passphrase backups already exist in code. They include structured records but exclude original PDF/image bytes. Restore must disclose that boundary and handle new-profile versus replacement choices. App lock must hide health UI; iOS cannot promise Android-style screenshot blocking.

Unconfirmed OCR never appears in confirmed trends or clinical exports. Text results never become zero. Separate incompatible units. Plot actual values at actual dates. Keep clinical flag text independent from decorative category colors. Do not invent diagnoses, recommendations, clinician signatures, readiness scores or new patient attributes.

## Make changes over time easy to understand

People must be able to follow their records over months and years. Provide accessible time-range controls, dated numeric charts, a clear latest value with unit, and changes since the first and previous readings in the selected period. Explain the comparison dates. Use plain language such as “No reading recorded” and “Not enough readings to compare.” A numeric difference is not a conclusion that health improved or worsened.

Allow a person to select a plotted point and read its date, original value/unit, source and recorded reference. Pair every graph with a readable table or timeline. Use actual elapsed-time spacing. Do not invent intermediate readings, fill missing intervals with zero, merge incompatible units, turn text results into scores, or draw a trend from one point. Show sparse history honestly even when a long time range is selected. Daily summaries must be labelled as daily summaries, not instantaneous or hourly measurements.

PDFs must communicate the same history without requiring the app: clear title/profile/period, a short scope explanation, large legible charts, directly labelled readings, first/previous comparisons, and complete tabular detail. Use “Test” instead of unexplained “Marker.” Explain what dots, lines and report ranges mean. Maintain at least 11pt body and table text by paginating; never squeeze a long history onto one page. Preserve selectable text, original units, printed dates, source attribution, repeated table headers and meaningful grayscale. Follow the reference PDF examples and PDF_SPEC for exact export behavior.

Inspect pdf-samples/vezuna-laboratory-history-a4.pdf and pdf-samples/vezuna-laboratory-history-letter-grayscale.pdf. These two-page reference documents use actual text and vector content, with explanatory copy and data tables. Their generation script is an example, not a replacement for the repository's native exporter. Recreate the behavior through the existing app architecture.

## Implementation order

1. Inspect repository instructions, current architecture and tests. Map the manifest to existing routes/state. Create a progress ledger with every image ID, implementation location, verification and remaining gaps.
2. Implement semantic tokens, reusable controls, accessible navigation and profile context. Complete Today, Records/import/review and Care as end-to-end workflows before polishing isolated pages.
3. Complete trends/device measurements, backups/recovery/lock, settings/legal and conditional assistant/model flows. Unify the currently inconsistent iOS shared/native import and export paths.
4. Implement the export configuration and preview, then native PDF/CSV generation and save/share. Follow PDF_SPEC for A4/Letter, pagination, original units, selectable text, language and grayscale. The PDF references are separate layouts, not one twelve-page document.
5. Verify every workflow and error/empty/loading state on Android and iOS where available. Test dark mode, enlarged text, narrow phones, tablets and EN/PT/ES. Compare actual screenshots to the references. Run the repository's appropriate domain/native tests and report evidence.

## Acceptance

No visible placeholder actions, invented successful operations or flattened screenshots. Every screen has an entry, exit and recovery path. Destructive actions preserve their existing confirmation and data semantics. Native handoffs use native APIs. Values and counts come from live state. Fonts, icons and text remain accessible and local.

Read specs/VECTOR_ASSETS.md and specs/ASSET_MAP.md, inspect assets.html, and use assets/screen-assets.json to resolve each screen's vector artwork. Use the supplied illustration SVGs as the canonical coherent artwork, the icon SVGs for action/navigation glyphs, and exact supplied SVGs for the logo. Do not trace the mockup pixels or embed PNGs in SVG files. Render charts, initials, controls and PDF tables from live data and native vector primitives. Invoke actual OS UI for system-owned assets. Generated illustration details and raster text are visual references, not copy truth. Keep module colors substantial and intentional, readable clinical surfaces opaque and primary actions obvious. The old root DESIGN.md presentation rules about giant home slogans/privacy auras and unlabeled navigation are superseded by this user-authorized redesign; security and domain requirements remain.

Finish with a coverage ledger for all image IDs, tested screenshots, actual sample PDF files, test results, and honest remaining platform/store prerequisites. Do not declare the redesign complete while visible workflows are missing or nonfunctional.

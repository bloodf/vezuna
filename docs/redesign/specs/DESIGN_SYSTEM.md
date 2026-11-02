# Vezuna redesign design system

Design date: 1 November 2026. Purpose: reference images and implementation specification, not implemented application UI.

## Direction

A task-first native household health organizer with life and color. The final direction is **Vezuna Living Color**. Retain the Vezuna name, supplied logo, local Noto Sans and pine/teal/mint foundation, expanding it with coral, lavender and citron. Replace the marketing-style dashboard, oversized privacy decoration, technical settings wall and unlabeled navigation with compact profile context, readable records, focused tasks and grouped settings.

The user rejected the initial pale mockup. That discarded image is archived and excluded from the deliverable ZIP. The final system uses substantial rich color areas, confident type, rounded graphic accents and distinct category colors. Do not dilute it into a white card stack with tiny colored icons. Use a pine upper region, a colorful focal panel or a strong category header on each main screen; keep dense form/data areas legible. Forms, recovery and legal screens may use smaller category accents. This is a lively health organizer, not a fitness coach.

Images are individual flat screen exports without device frames because another agent will implement them. Logical phone canvas is 390 × 844; image generator output dimensions may differ. Use the logical layout and tokens, never infer dimensions from raster pixels. Image copy can contain generator imperfections. Written requirements and domain data take precedence.

## Exact brand assets

Use the repository's supplied curved V and central dot, with its Vezuna wordmark stored as vector paths. The two rounded curves represent information around a person. A leaf, sprout, heart, cross, shield, or generic letter V is not the Vezuna logo. Use `assets/brand/logos/lockup-dark.svg` on pine and `lockup-color.svg` on light surfaces. Use the supplied mark-only asset when space cannot accommodate the horizontal lockup. Preserve aspect ratio, both curves, dot, colors, and the wordmark paths; do not recreate the brand with a font or trace a generated screenshot.

Branding belongs in a compact header or navigation rail. It must not push the chart, result, or primary action off screen. Use at least 120 logical pixels for a horizontal lockup and leave at least one dot-diameter of clear space around the visible artwork. Botanical illustrations remain optional decoration, visually separate from the brand. The logo-bearing raster mockups are references; the SVG assets are the authoritative geometry for implementation.

## Navigation

Five labeled destinations in this exact order: Today, Records, Care, Trends, More. Import is an action in Today and Records. The profile switcher is available on root screens. Detail/form screens use back navigation and omit the bottom bar when the task benefits from focus. Preserve the active profile across navigation.

- Today: next dose, pending import review, recent records.
- Records: reports/results, search and filters, add/import, exports.
- Care: medications and recorded dose history.
- Trends: laboratory trends and device measurements.
- More: profiles, exports, backups, privacy, connections, app lock, assistant/models, preferences, help, open source.

Assistant and model management remain accessible from More. They do not block records or exports.

## Tokens

| Role | Light | Dark |
|---|---|---|
| Background | #F6F7F2 | #0C2027 |
| Surface | #FFFFFF | #14313A |
| Subtle surface | #E7F3F1 | #1A3E48 |
| Text | #152E35 | #E7F5F2 |
| Secondary text | #526C74 | #ADC7CE |
| Primary | #087F8C | #71E2CC |
| On primary | #FFFFFF | #0C2027 |
| Required control outline | #6C878F | #668992 |
| Decorative divider | #DCE3DF | #35545D |
| Focus | #086A78 | #71E2CC |
| Warning | #805500 | #F5CB73 |
| Error | #B3263B | #FF9AAA |
| Success | #176B50 | #87DEB4 |

Category colors: records mint #71E2CC, care coral #FF8A65, trends lavender #B8ABFF, activity citron #DDF27C. Use #123B46 or #152E35 text on these fills, never white small text. Category hues identify content, not health status. Clinical alerts retain semantic icons and explicit text. Primary buttons can use pine with white labels on colorful surfaces; on pine, use mint with dark labels. Teal #087F8C remains the standard link/action color on light surfaces.

The home may devote its top third to deep pine and use a mint next-action panel, coral review accent and lavender/citron measurement tiles. Category screens reuse the same palette and geometry with one dominant accent. Welcome/empty states may use bespoke friendly document-and-orbit illustration. Do not copy competitor mascots or use decorative medical shields. Brief tonal gradients are allowed in welcome artwork; fields, lab values and charts remain on opaque flat surfaces.

Noto Sans 400 and 700 only, using bundled fonts. Titles 32, section headings 22–24, body and controls 16–17, secondary 14, nonessential captions 12. Critical health data, instructions and warnings are never 12. Numbers use tabular alignment where available. Body line height about 1.5.

Spacing uses 4/8/12/16/24/32/48. Phone side gutters 24, reducing to 20 on narrow displays. Typical row/control height at least 52. Touch targets at least 48dp Android and 44pt iOS. Card radius 20, control radius 12, sheets 28. Height grows with content. White space and dividers establish groups; avoid nested cards and heavy shadows.

One filled primary action per decision area. Secondary actions are outlined or text. Destructive actions use a clear verb and target. Disabled states retain readable labels. Fields keep persistent labels, hints and local errors. Keyboard type follows input, and errors preserve all entered data.

## Clinical information

Opaque content surfaces. Original value/unit, collection date, source, reference and confirmation status remain explicit. Use textual statuses such as Above report range, Below report range, Within report range, Reference not provided, Needs review. Color is supplementary and never a diagnosis. No wellness score, diagnostic promise or dose advice.

Charts show actual recorded readings at actual dates, visible units, accessible values and provenance. Give history a large readable area and reduce illustration before reducing data text. Offer 3M, 6M, 1Y, and All controls, show the selected dates and available-history dates, and preserve empty periods. Default to All for the supplied sparse fixture. Screen051 intentionally demonstrates the 1Y state. Separate incompatible units; do not invent interpolated observations or one reference band when ranges differ. Qualitative results use dated text rows. One reading has no invented trend line. Show a neutral numerical change only when two compatible observations support it, with the comparison date. Never label a change as better, worse, or a health score.

Follow [GRAPH_GUIDANCE.md](GRAPH_GUIDANCE.md) for range semantics, exact plotting, sparse states, accessible history, and the seven editable chart references. The device fixture contains seven daily averages and totals, not hourly readings. Screen067 is an isolated post-action medication history, not the baseline export snapshot.

## Adaptation

Use platform-native navigation materials, permissions, file/photo/camera pickers, biometric authentication and share sheets. OS mockups explain the handoff only; do not rebuild OS UI. On supported iOS, reserve restrained native glass for navigation/controls and honor reduced transparency. On Android, use semantic Material surfaces. Medical content stays opaque.

At wider widths use navigation rail and list/detail or source/review panes. At large text, stack side-by-side inputs/actions, expand cells, preserve reading order and scrolling. Never shrink fonts to fit. Dark theme maps semantic colors separately. Respect reduce motion, screen readers, focus order, keyboard navigation and at least WCAG AA contrast targets. Verify contrast in the implementation.

App language currently follows device EN/PT/ES. An in-app language/appearance preference is a proposed improvement. Preserve original clinical text and units while localizing interface copy, dates and input formatting.

## Signature components

Compact avatar/name profile control; segmented local tabs; bottom action sheets; grouped settings rows. Small rounded product icons with consistent weight. Use the supplied foliage, capsule, document, device and other SVG illustrations consistently in headers, category panels and empty/recovery states. Keep artwork clear of clinical text and actions; do not shrink health data to make room for decoration. Motion is short sheet transitions and calm tab transitions, 120–200ms, disabled when reduced motion is requested.

## Current references

Design decisions interpret current native guidance rather than copying another app.

- Apple, Communicate your brand identity on iOS: https://developer.apple.com/videos/play/wwdc2026/251/
- Apple, Materials: https://developer.apple.com/design/human-interface-guidelines/materials
- Google, Material 3 Expressive research: https://design.google/library/expressive-material-design-google-research
- Android, adaptive navigation: https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation

The redesign supersedes conflicting visual prescriptions in the existing root DESIGN.md, including the oversized home slogan/privacy aura and unlabeled six-position navigation. It does not supersede privacy, security, domain integrity or accessibility requirements.

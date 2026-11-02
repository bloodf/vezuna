---
name: Vezuna Living Color
description: Offline household health app. Deep pine structure with four category colours (mint records, coral care, lavender trends, citron device activity), the supplied Vezuna logo, local Noto Sans, and light/dark semantic roles.
colors:
  pine: "#123B46"
  teal: "#087F8C"
  mint: "#71E2CC"
  coral: "#FF8A65"
  lavender: "#B8ABFF"
  citron: "#DDF27C"
  ink: "#152E35"
  paper: "#F6F7F2"
  deep: "#0C2027"
  mist: "#E7F5F2"
  light-surface: "#FFFFFF"
  light-surface-subtle: "#E7F3F1"
  light-text-muted: "#526C74"
  light-border: "#6C878F"
  light-divider: "#DCE3DF"
  light-focus: "#086A78"
  light-link: "#076F7B"
  light-success: "#176B50"
  light-warning: "#805500"
  light-danger: "#B3263B"
  light-info: "#245DA8"
  dark-surface: "#14313A"
  dark-surface-subtle: "#1A3E48"
  dark-text-muted: "#ADC7CE"
  dark-border: "#72969F"
  dark-divider: "#35545D"
  dark-success: "#87DEB4"
  dark-warning: "#F5CB73"
  dark-danger: "#FF9AAA"
  dark-info: "#9DC4FF"
  series-records-light: "#0B6B5C"
  series-care-light: "#C0552F"
  series-trends-light: "#6F5FD6"
  series-activity-light: "#5E7A00"
typography:
  family: "Noto Sans 400/700, bundled locally (shared/src/commonMain/composeResources/font)"
  displaySmall: "32sp/40 bold (root and onboarding titles; growth capped at 1.3x)"
  headlineLarge: "32sp/40 bold"
  headlineMedium: "28sp/36 bold"
  headlineSmall: "24sp/32 bold"
  titleLarge: "22sp/28 bold"
  titleMedium: "17sp/26 bold"
  bodyLarge: "17sp/26"
  bodyMedium: "16sp/24"
  bodySmall: "14sp/21"
  labelLarge: "16sp/24 bold"
  labelMedium: "14sp/21 bold"
  labelSmall: "12sp/18 (navigation labels only, never clinical data)"
spacing: { xs: 4dp, sm: 8dp, smd: 12dp, md: 16dp, lg: 24dp, xl: 32dp, xxl: 48dp, gutter: 24dp, gutterNarrow: 20dp }
radius: { control: 12dp, card: 20dp, sheet: 28dp, pill: 999dp }
targets: { android: 48dp, ios: 44pt, rowHeight: 52dp }
breakpoints: { rail: 600dp }
---

# Vezuna design system: Living Color

This file describes the design system as it is implemented in the app. The owner-approved redesign pack lives in
[docs/redesign/](docs/redesign/): the brief ([HANDOFF_PROMPT.md](docs/redesign/HANDOFF_PROMPT.md)), the specs
([specs/DESIGN_SYSTEM.md](docs/redesign/specs/DESIGN_SYSTEM.md), DATA_AND_COPY, ACCESSIBILITY_AND_PLATFORM,
GRAPH_GUIDANCE, PDF_SPEC, VECTOR_ASSETS, ASSET_MAP), the per-image [ledger.csv](docs/redesign/ledger.csv) and
the verification record [COVERAGE.md](docs/redesign/COVERAGE.md). When this file and a spec disagree on a
detail, the spec and real domain data win; when a reference image and a spec disagree, the spec wins.

This version supersedes the earlier "calm hub" rules: there is no giant home slogan, no privacy aura or
breathing animation, no icon-only navigation and no gear in the root header. The security, privacy and domain
rules below are unchanged.

## Where it lives in code

| Concern | Location |
|---|---|
| Colour tokens, spacing, radii, category colours | `shared/src/commonMain/kotlin/app/vezuna/ui/VezunaTokens.kt` |
| Material colour roles (light/dark), typography | `ui/Theme.kt` (`VezunaTheme`, `MaterialTheme.vezuna`) |
| Shell: bottom bar, rail at 600dp+, feedback host | `ui/VezunaApp.kt` |
| Routes, one file per workflow lane | `ui/AppScreen.kt`, `ui/nav/Routes.kt`, `ui/<feature>/<Feature>Routes.kt` |
| Header, profile switcher, focused task scaffold | `ui/components/Header.kt`, `ui/components/States.kt` |
| Rows, category panels, controls, buttons, states | `ui/components/Rows.kt`, `Controls.kt`, `Buttons.kt`, `States.kt` |
| Charts | `ui/LineChart.kt`, `ui/trends/TrendParts.kt` |
| Vector icons and illustrations | `shared/src/commonMain/composeResources/drawable/ic_*.xml`, `ill_*.xml` (masters in `brand/vectors/`) |
| Logo lockups | `drawable/vezuna_lockup_*`, `vezuna_mark_*` (from `brand/`, never redrawn) |
| Design fixture for screenshots | `ui/DesignFixture.kt`, `scripts/redesign-shots.sh` |

## Colour

- **Structure is pine.** Root headers and focused-task bands are a pine band (`#123B46` light, header role in
  dark) with white text, `#ADC7CE` for secondary text and mint for accents. Never muted grey or teal text on pine.
- **Four category colours carry the modules:** mint = Records, coral = Care, lavender = Trends, citron = device
  activity. They are substantial fills for panels and selected states, not tints. Text on a category fill is
  always ink/pine (`Category.onFill`), in light and in dark mode. Status, error or link text inside a category
  panel sits on an opaque white/surface inner card.
- **Fills are not marks.** Chart lines, points and informative icons use `Category.series(dark)`: the darker
  series colour on light backgrounds (3:1 or more) and the bright fill colour on dark backgrounds.
- **Clinical flags never depend on category colour.** Range status is a text label with an icon
  ("Above report range"), readable in grayscale.
- **Teal `#087F8C` is a fill**, for filled buttons with white labels. Teal text and links on light use
  `#076F7B`. Required control outlines use the border role (`#6C878F` light, `#72969F` dark), never the divider.
- Contrast pairs are asserted by `tools/branding/check_brand.py` (light and dark) in `scripts/check-all.sh`.

## Typography

Noto Sans 400/700 from local resources, `sp` everywhere so text follows the system size. Values use tabular
figures (`TextStyle.tabular()`). Clinical data is never set at 12sp. Display titles stop growing at 1.3x
(`TextStyle.cappedScale()`) so a single long word fits a 320dp phone; body text is never capped. Tab and
segment labels shrink to one line at large sizes (`singleWordAutoSize`) instead of breaking mid-word.

## Layout

- Five root destinations, in order: Today, Records, Care, Trends, More. Bottom navigation bar with visible
  labels on phones, a navigation rail with the Vezuna mark from 600dp. Import is a contextual action (Today,
  Records), not a tab. Assistant and models live under More.
- Root screens use `RootScaffold`: pine band with the profile chip (opens the profile switcher sheet), title,
  optional subtitle and foliage art. No gear. Pushed screens use `FocusedTaskScaffold`: back button, optional
  title, no bottom bar. The lockup appears only on the rail, Welcome, Lock and About.
- Profile ownership is visible on every root screen. Changing profile resets every back stack.
- Tablets: Records uses list/detail panes; forms and review are width-limited and centred.
- Decorative corner and header art hides from font scale 1.5 and gives its space back to the text
  (`decorativeArtShown()`, `Modifier.clearOfArt()`).

## Components

- **Category panel** (`CategoryPanel`): category fill, 20dp radius, optional corner illustration, ink content.
- **Grouped rows** (`GroupedRows`, `ListRow`): one focus stop per row with a composed description; leading
  vector icon; chevron is decorative.
- **Buttons**: primary (filled), secondary (outlined), tertiary (text), destructive. Minimum 48dp/44pt.
  Destructive actions keep their confirmation (`ConfirmDialog`) with an explicit verb, never "OK".
- **Controls**: `SegmentedControl` (inactive labels follow the surrounding content colour), `RangeChips`
  (3M/6M/1Y/All, radio semantics with the full period name, wraps at large text), `StatusTag`, `LabeledField`
  (persistent label, `isError` + supporting text, correct keyboard type).
- **States**: `EmptyState`, `ProgressState`, `ConfirmDialog`, `VezunaSheet` (heading title), `FeedbackHost`
  (one polite/assertive live region in the shell). `Modifier.returnFocusAfter(open)` returns focus to the
  control that opened a dialog or sheet.
- **Charts**: actual values at actual dates with elapsed-time spacing; no invented points, no zero-filling, one
  unit per series (incompatible units are separate series); the latest or selected value is labelled on an
  opaque backdrop; reference ranges are an outlined band or a dashed line with a text legend; every chart is
  paired with a dated table or timeline and a text summary (latest, change since previous and first reading,
  with the comparison dates). Daily device summaries are labelled as daily summaries.

## Motion

120-200ms transitions, all gated by `LocalVitaAnimationsEnabled` (system reduced motion). No infinite or
breathing animation. No information is carried only by motion.

## Copy

Plain language per [DATA_AND_COPY.md](docs/redesign/specs/DATA_AND_COPY.md): "Test", not "Marker"; "No reading
recorded"; "Not enough readings to compare". A numeric difference is never described as better or worse. No
diagnoses, recommendations or readiness scores. No implementation jargon (OCR, SHA-256, inference, bytes) in
user-facing text. EN/PT/ES for every string in the shared, Android and iOS catalogs;
`tools/localization/check_localization.py` fails on drift and on escaped apostrophes in shared catalogs.

## Accessibility

- Every interactive node has a localized name, role and state; decorative icons have no description.
- Screen titles and section titles are headings; on navigation, focus starts at the title.
- Targets 48dp/44pt; 8dp or more between destructive and routine actions.
- Text reflows at 200%: no fixed heights around text, no truncation of names, values, doses or instructions.
- Status changes (save, import, export, dose actions) are announced through the feedback live region.
- While the app is locked, health UI is not composed at all, so no health node is reachable by TalkBack or
  VoiceOver.
- RTL uses start/end; the logo, numbers and charts are not mirrored.

## Security, privacy and domain rules (unchanged)

- Health records stay in encrypted local storage. No account, no server sync, no telemetry, no network service
  for health data. Optional on-device AI never gates records, backups or exports.
- Household/profile isolation; reviewed versus unreviewed results (unconfirmed reading never appears in
  confirmed trends or clinical exports); original values, units and reference ranges; collection dates as
  calendar days; correction history; duplicate protection; medication schedules and recorded dose events.
- Text results never become numbers or zero. Incompatible units are never merged.
- Portable passphrase backups include structured records but not original PDF/image bytes; restore discloses
  this and offers new-profile versus replacement.
- App lock hides health UI. iOS cannot promise Android-style screenshot blocking (`FLAG_SECURE`).
- System-owned UI (pickers, permissions, biometric prompt, notifications, share/save sheets) is the real OS
  interface, never a replica.
- Keep `app.vezuna`, storage keys, controller APIs and export formats stable.

## Don't

- Don't redraw, trace or mirror the logo, or substitute a leaf/sprout or a typeset wordmark.
- Don't embed raster screenshots or PNGs in UI or SVG assets; all artwork is the supplied vector library.
- Don't use category fills as text colours or chart marks on light backgrounds, or white text on coral/mint.
- Don't add telemetry, marketing SDKs, remote health-data services or new permissions.
- Don't claim diagnosis, clinical approval, WCAG certification or store readiness.
- Don't translate persisted clinical values, source units, internal IDs or model IDs.

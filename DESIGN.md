---
name: Vezuna
description: A calm, private personal health hub using the supplied Vezuna identity, local Noto Sans, and semantic light/dark themes.
colors:
  navy: "#123B46"
  teal: "#087F8C"
  aqua: "#71E2CC"
  offwhite: "#F5FAF9"
  ink: "#152E35"
  deep: "#0C2027"
  mist: "#E7F5F2"
  light-surface: "#FFFFFF"
  light-surface-subtle: "#E7F3F1"
  light-muted: "#526C74"
  light-border: "#8CA7AD"
  light-focus: "#086A78"
  light-success: "#176B50"
  light-warning: "#805500"
  light-danger: "#B3263B"
  light-info: "#245DA8"
  dark-surface: "#14313A"
  dark-surface-subtle: "#1A3E48"
  dark-muted: "#ADC7CE"
  dark-border: "#668992"
  dark-accent: "#35B7B8"
  dark-success: "#87DEB4"
  dark-warning: "#F5CB73"
  dark-danger: "#FF9AAA"
  dark-info: "#9DC4FF"
typography:
  display:
    fontFamily: "Noto Sans"
    fontSize: "40sp"
    fontWeight: 700
    lineHeight: "48sp"
  display-small:
    fontFamily: "Noto Sans"
    fontSize: "32sp"
    fontWeight: 700
    lineHeight: "38sp"
  headline:
    fontFamily: "Noto Sans"
    fontSize: "28sp"
    fontWeight: 700
    lineHeight: "34sp"
  headline-small:
    fontFamily: "Noto Sans"
    fontSize: "24sp"
    fontWeight: 700
    lineHeight: "29sp"
  title:
    fontFamily: "Noto Sans"
    fontSize: "24sp"
    fontWeight: 700
    lineHeight: "29sp"
  title-medium:
    fontFamily: "Noto Sans"
    fontSize: "20sp"
    fontWeight: 700
    lineHeight: "26sp"
  title-small:
    fontFamily: "Noto Sans"
    fontSize: "16sp"
    fontWeight: 700
    lineHeight: "24sp"
  body:
    fontFamily: "Noto Sans"
    fontSize: "16sp"
    fontWeight: 400
    lineHeight: "24sp"
  body-small:
    fontFamily: "Noto Sans"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "21sp"
  label:
    fontFamily: "Noto Sans"
    fontSize: "16sp"
    fontWeight: 700
    lineHeight: "24sp"
  label-medium:
    fontFamily: "Noto Sans"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "21sp"
  caption:
    fontFamily: "Noto Sans"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "18sp"
rounded:
  control: "12dp"
  card: "20dp"
  pill: "999dp"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "16dp"
  lg: "24dp"
  xl: "32dp"
  xxl: "48dp"
components:
  button-primary-light:
    backgroundColor: "{colors.teal}"
    textColor: "{colors.light-surface}"
    rounded: "{rounded.control}"
    height: "48dp minimum"
  button-primary-dark:
    backgroundColor: "{colors.aqua}"
    textColor: "{colors.deep}"
    rounded: "{rounded.control}"
    height: "48dp minimum"
  button-outlined-light:
    backgroundColor: "transparent"
    textColor: "{colors.teal}"
    rounded: "{rounded.control}"
    height: "48dp minimum"
  button-outlined-dark:
    backgroundColor: "transparent"
    textColor: "{colors.aqua}"
    rounded: "{rounded.control}"
    height: "48dp minimum"
  input-outlined-light:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
  input-outlined-dark:
    backgroundColor: "transparent"
    textColor: "{colors.mist}"
    rounded: "{rounded.control}"
  card-standard-light:
    backgroundColor: "{colors.light-surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.card}"
    padding: "16dp"
  card-standard-dark:
    backgroundColor: "{colors.dark-surface}"
    textColor: "{colors.mist}"
    rounded: "{rounded.card}"
    padding: "16dp"
  status-pill-success-light:
    backgroundColor: "{colors.light-surface-subtle}"
    textColor: "{colors.light-success}"
    rounded: "50%"
    padding: "10dp horizontal; 6dp vertical"
  status-pill-success-dark:
    backgroundColor: "{colors.dark-surface-subtle}"
    textColor: "{colors.dark-success}"
    rounded: "50%"
    padding: "10dp horizontal; 6dp vertical"
  floating-navigation-light:
    backgroundColor: "{colors.navy}"
    textColor: "{colors.aqua}"
    rounded: "{rounded.card}"
    padding: "6dp horizontal; 7dp vertical"
  floating-navigation-dark:
    backgroundColor: "{colors.deep}"
    textColor: "{colors.aqua}"
    rounded: "{rounded.card}"
    padding: "6dp horizontal; 7dp vertical"
  import-action-light:
    backgroundColor: "{colors.teal}"
    textColor: "{colors.light-surface}"
    rounded: "{rounded.card}"
    size: "66dp"
  import-action-dark:
    backgroundColor: "{colors.aqua}"
    textColor: "{colors.deep}"
    rounded: "{rounded.card}"
    size: "66dp"
  snackbar-light:
    backgroundColor: "{colors.navy}"
    textColor: "{colors.offwhite}"
    rounded: "{rounded.control}"
  snackbar-dark:
    backgroundColor: "{colors.deep}"
    textColor: "{colors.mist}"
    rounded: "{rounded.control}"
---

# Design System: Vezuna

## Overview

**Creative North Star: “Your health, in one place.”**

Vezuna is a calm, private health hub for dense household records. Navy provides structure, teal identifies light-theme actions, and aqua identifies dark-theme actions. Quiet workspaces and exact supplied brand assets keep clinical content more prominent than decoration. The two-curve mark around a point is a brand metaphor, not a security or clinical certification.

Home remains a functional scrolling dashboard with profile controls, a localized greeting, local-privacy context, medication reminders, adherence, workflow actions, and recent records. Five top-level destinations—Home, Labs, Meds, AI, and Settings—share the floating navigation; the raised central Import control is a separate workflow action, not a sixth top-level destination. Existing routes, records, controller behavior, calculations, exports, and technical identifiers remain incumbent.

**Key Characteristics:**
- Exact supplied Vezuna lockups and native app icons; local Noto Sans 400/700.
- Independent semantic light/dark palettes with teal light actions and aqua dark actions.
- A functional scrolling dashboard and native clinical workflows, not promotional sections.
- Four-based spacing primitives, 12dp controls, 20dp cards, and 48dp Android touch targets.
- Brief 120/200ms transitions with 0ms/static reduced motion.
- Localized EN/PT/ES, large-text resilience, and RTL without mirrored clinical content.

## Colors

The palette is restrained and semantic: brand colors establish orientation while theme-specific surfaces, foregrounds, borders, and state colors carry the working interface.

### Primary
- **Teal:** Light-theme primary actions, selected data marks, and emphasis; paired with white foregrounds.
- **Aqua:** Dark-theme primary actions, selected navigation, focus, and the snackbar action; paired with Deep foregrounds when used as a filled action.

### Secondary
- **Navy:** Brand structure, light-theme ink surfaces, and the floating navigation.
- **Dark Accent:** The independent dark-theme secondary accent used by the Material color scheme; it does not replace Aqua as the primary dark action.

### Tertiary
- **Success:** Light and dark success tokens confirm actual interface state in privacy and confirmation treatments.
- **Warning:** Light and dark warning tokens mark attention and unreviewed states without interpreting a clinical result.
- **Danger:** Light and dark danger tokens serve real errors and risk states, together with explanatory content.
- **Information:** Light and dark information tokens are reserved for informational state, not diagnostic scoring.

### Neutral
- **Offwhite / Deep:** Independent light and dark workspace backgrounds.
- **Ink / Mist:** Primary reading text in light and dark themes.
- **Light Surface / Dark Surface:** Cards and raised content surfaces.
- **Light Surface Subtle / Dark Surface Subtle:** Tonal containers, selected regions, and status backgrounds.
- **Light Muted / Dark Muted:** Secondary text and supporting metadata.
- **Light Border / Dark Border:** Outlines, chart grid lines, and subtle separation.
- **Light Focus / Aqua:** Theme-appropriate focus emphasis.

**The Semantic Theme Rule.** Map light and dark roles independently; never derive dark mode by inversion.

**The Action Contrast Rule.** Use teal with white labels in light mode and aqua with Deep labels in dark mode; never use aqua for small text on white or Offwhite.

**The Clinical Color Rule.** Pair success, warning, danger, and information color with text or an icon; color never asserts diagnosis, health, or normality.

## Typography

**Display Font:** Local Noto Sans Bold.
**Body Font:** Local Noto Sans Regular.
**Label Font:** Local Noto Sans Regular or Bold according to the defined role.

The single-family system is direct and readable. Regular 400 carries clinical content and controls; Bold 700 establishes hierarchy without synthetic intermediate weights. All sizes use scalable sp units and reflow with system text enlargement.

### Hierarchy
- **Display** (700, 40sp/48sp): The localized dashboard greeting.
- **Display Small** (700, 32sp/38sp): Scrolling screen titles.
- **Headline** (700, 28sp/34sp): Prominent result values.
- **Headline Small / Title** (700, 24sp/29sp): Section headings, workflow labels, and compact result emphasis.
- **Title Medium** (700, 20sp/26sp): Card titles, profile names, and empty-state titles.
- **Title Small / Label Large** (700, 16sp/24sp): Emphasized rows, privacy status, and control labels.
- **Body** (400, 16sp/24sp): Primary reading, values, instructions, and form content.
- **Body Small / Label Medium** (400, 14sp/21sp): Supporting metadata, notices, and status-pill text.
- **Caption / Label Small** (400, 12sp/18sp): Nonessential codes, dates, and chart labels; never the sole presentation of critical instructions or warnings.

**The Two-Weight Rule.** Use local Noto Sans Regular 400 for reading and Bold 700 for emphasis; do not synthesize intermediate weights.

## Layout

The core spacing primitives are 4, 8, 16, 24, 32, and 48dp. Shared screen columns scroll vertically, use 20dp horizontal content margins, and separate major content by 16dp; the denser dashboard uses its implemented 10dp vertical cadence. Cards grow with content rather than receiving fixed heights. The dashboard is not an above-the-fold composition: reminders, adherence, recent results, and empty states remain reachable through ordinary scrolling.

Profile controls use a wrapping flow row while Settings stays at the trailing edge. Workflow tiles form two-column rows, fields fill available width, and paired fields share compact rows where the incumbent layout does so. Trend filters scroll horizontally instead of truncating test codes. Charts have a 210dp canvas and the privacy aura has a 120dp region; neither displaces the underlying records or textual alternatives.

The interface follows English, Portuguese, or Spanish with English fallback. Long translations and enlarged text wrap or expand containers. RTL changes layout direction through native composition, while the logo, numeric content, and charts remain unmirrored.

## Elevation & Depth

Depth is primarily tonal. Surface, subtle-surface, border, and spacing changes separate clinical content without a general shadow treatment. Explicit native elevations are limited to the workflow tile (1dp), raised Import action (5dp), and floating navigation (6dp). Snackbars overlay transient feedback without introducing another decorative elevation system.

### Shadow Vocabulary
- **Workflow lift** (1dp): A small native card elevation on actionable workflow tiles.
- **Action lift** (5dp): The central Import control rising 18dp above the navigation row.
- **Navigation lift** (6dp): The floating container separating global navigation from scrolling content.

**The Quiet Surface Rule.** Let tonal surfaces, borders, and spacing carry hierarchy; reserve explicit elevation for the workflow tile, central Import action, and floating navigation.

## Shapes

Controls and outlined fields use gently rounded corners (12dp). Cards, workflow tiles, the floating navigation, and central Import action use a broader container curve (20dp). Fully rounded geometry is reserved for pills and compact status language: the pill primitive is 999dp and the implemented status pill uses 50% corners. Circular or capsule forms do not become decoration without a content role.

Use the exact light/dark lockup assets and native app-icon collections. Preserve the supplied symbol geometry and at least one central-dot diameter of clear space; do not redraw, stretch, rotate, mirror, recolor, outline, or add effects. Supplied Vezuna interface icons use a 24-unit viewport with rounded 1.8 strokes; incumbent Material rounded icons remain consistent where already used. Icon-only controls carry localized accessible descriptions.

## Components

### Buttons
- **Shape and target:** Gently rounded controls (12dp) with a minimum 48dp Android target; preserve the 44pt iOS minimum in native integration.
- **Primary:** Teal with white content in light mode and aqua with Deep content in dark mode. The shared button pairs a 20dp icon, 8dp gap, and explicit label.
- **Outlined:** A lighter secondary action using the semantic primary foreground and native outline. Material pressed, focus, and disabled states remain intact.
- **Decision hierarchy:** Use one primary action per decision region; existing Taken/Skip/Snooze and Confirm/Reject behaviors do not change.

### Inputs / Fields
- **Style:** Native outlined text fields with persistent labels, semantic text and outline colors, and 12dp theme corners.
- **Layout:** Full-width fields or existing weighted pairs; single-line entry does not justify removing labels or clipping surrounding instructions.
- **Behavior:** Preserve native focus, selection, IME, validation, and error association. OCR values remain editable drafts until the user confirms them.

### Cards / Containers
- **Clinical cards:** Quiet semantic surfaces, 20dp corners, 16dp internal padding, content-driven height, and native animated sizing only when motion is enabled.
- **Result rows:** Test identity, original value and unit, reference context, date, confirmation status, and details action retain their hierarchy.
- **Workflow tiles:** At least 100dp high, 20dp corners, 16dp padding, semantic aura surfaces, and 1dp elevation.
- **Empty states and notices:** Tonal surfaces with an icon, concise title or explanation, and readable secondary text; they describe actual empty, error, or offline state without implying synchronization.

### Status Pills
- **Style:** Fully rounded capsules with 10dp horizontal and 6dp vertical padding, a 16dp icon, and 14sp supporting text.
- **State:** Semantic container and foreground pairs distinguish Confirmed and Draft; text remains present rather than relying on color.

### Navigation
- **Brand header:** The exact theme-appropriate lockup occupies a 40dp-high region; the Device action retains a 48dp minimum target.
- **Floating bar:** Navy in light mode and Deep in dark mode, 20dp corners, 6dp elevation, and semantic selected/unselected icon colors. Five top-level destinations use localized accessible labels.
- **Central Import:** A separate 66dp action, offset upward 18dp with 5dp elevation, using the primary/on-primary pair and a localized Import label.
- **Transitions:** Screen entry uses a 200ms fade/slide and exit uses a 120ms fade; reduced motion uses immediate transitions. No content depends on animation completing.

### Snackbar
- **Style:** Ink container—Navy in light mode and Deep in dark mode—with Offwhite or Mist content and an Aqua dismiss action.
- **Measured contrast:** Aqua on Navy measures 7.73:1; Aqua on Deep measures 10.75:1. These are measured color pairs, not an application-wide accessibility certification.
- **Behavior:** Errors and notices remain transient native snackbars with an explicit dismiss action; they do not become diagnostic alerts.

### Trend Chart
- **Style:** A 210dp semantic canvas with primary series strokes, surface-centered points, and subtle outline grid lines. The selected series changes through native filter chips.
- **Motion:** Series reveal uses the normal 200ms duration and snaps to its final state under reduced motion. Trend selection has no animation when motion is disabled.
- **Alternative:** Confirmed dates, values, units, and references remain available as textual rows. Numbers, series meaning, and chart geometry never mirror for RTL.

### Privacy Aura
- **Style:** A 120dp decorative region of semantic radial gradients around the existing on-device message. Its icon and wording provide context, not proof of encryption or security.
- **Motion:** The incumbent decorative breath reverses over 2200ms when animation is enabled and resolves to a static composition under reduced motion. This local effect does not redefine the 120/200ms interface-transition tokens.

## Do's and Don'ts

### Do:
- **Do** use semantic tokens, exact supplied lockups and native icons, and local Noto Sans 400/700.
- **Do** preserve `com.bloodf.vitahearth`, routes, storage keys, controller APIs, records, alerts, calculations, privacy behavior, and export formats.
- **Do** keep five top-level destinations and the separate central Import action.
- **Do** support EN/PT/ES with English fallback, large text, long translations, and real RTL without mirroring the logo, numbers, or charts.
- **Do** preserve 48dp Android and 44pt iOS touch targets, native focus behavior, localized accessible descriptions, and textual chart alternatives.
- **Do** use 120/200ms interface transitions and 0ms/static reduced motion without hiding information.
- **Do** report measured accessibility results precisely and without certification claims.

### Don't:
- **Don't** replace the scrolling clinical dashboard with promotional sections or require all content above the fold.
- **Don't** redraw or mirror supplied brand geometry, or substitute emoji, flags, or icon-font approximations.
- **Don't** use aqua for small text on white or Offwhite, or white text on aqua filled buttons.
- **Don't** add telemetry, marketing SDKs, remote health-data services, or new permissions.
- **Don't** claim diagnosis, clinical approval, WCAG certification, store readiness, or iOS runtime validation; iOS remains build-only because its runtime is unavailable.
- **Don't** translate persisted clinical values, source units, internal IDs, model IDs, or doctor-export structures as part of localization.
- **Don't** animate content when reduced motion is requested or make clinical meaning depend on color, motion, or the privacy aura.

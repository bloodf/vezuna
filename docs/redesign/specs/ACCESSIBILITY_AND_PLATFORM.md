# Accessibility and native platform behavior

Design specification, 1 November 2026. Read with [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) and [FLOW_MAP.md](FLOW_MAP.md). These are implementation acceptance criteria, not a claim that the generated images or current application pass accessibility testing.

The five root destinations are **Today, Records, Care, Trends, More**, in that order. Assistant and local models belong in More. Import is an action in Today and Records.

## Living Color with readable content

The approved direction is **Vezuna Living Color**. Use deep pine `#123B46` top bands, vivid mint `#71E2CC` for Records, coral `#FF8A65` for Care, lavender `#B8ABFF` for Trends, citron `#DDF27C` for activity, and warm paper `#F6F7F2` for the page. Meaningful colored panels should have visible area and a clear task or data purpose. Accessibility does not require reducing this palette to pale tints.

Use dark ink `#152E35` over mint, coral, lavender, citron and warm paper, and white over pine. The solid sRGB token pairs measure 9.13:1, 6.16:1, 6.99:1, 11.58:1, 13.23:1 and 12.07:1 respectively. These calculations use standard relative luminance without transparency; verify the rendered states separately. White labels over the bright category colors are not the prescribed pairing.

Category color identifies a destination or type of content, not the person's health. Coral medication panels are not error messages; lavender trend panels are not a clinical assessment. Error and reference-status indicators must have explicit text and a distinct local treatment. Colored navigation, visible labels and selected indicators work together. See [RESEARCH.md](RESEARCH.md) for the source observations and design reasoning.

## Reading and interaction

| Area | Required behavior | Verify with |
|---|---|---|
| Clinical content | Keep result name, original value, unit, collection date, source and confirmation status readable. Never use color alone for a reference-range comparison | VoiceOver/TalkBack and a grayscale inspection of result detail |
| Typography | Use bundled Noto Sans 400/700 and system-scaled text. Body/control text starts at 16–17; secondary text at 14. Critical instructions, doses and values never use 12 | Default text, 200% text, and the largest supported accessibility text setting |
| Contrast | Target at least 4.5:1 for normal text and 3:1 for large text. Required control boundaries, states and chart marks target 3:1 against adjacent colors | Measure actual rendered color pairs in both themes, including pressed, focused and error states |
| Touch | At least 48dp targets on Android and 44pt on iOS, even for a smaller icon. Separate destructive and routine actions | Bounds inspection and one-handed operation |
| Focus | Logical traversal order; visible keyboard focus; modal focus contained; dismissal returns focus to the invoking control | External keyboard, switch access and screen reader |
| Form errors | Persistent field labels, a specific error beside the field, and retained user input. Move focus to the first invalid field after submission | Submit empty, malformed and partially complete forms |
| Status changes | Announce save, import completion and validation results without stealing focus. A transient snackbar is not the only place a blocking error appears | Screen reader during import, save and export |
| Charts | Provide dated text values or a data table. Announce metric, unit, period and selected reading. Use distinguishable lines/markers and textual statuses | Navigate every reading without relying on a visual chart |

The contrast targets follow [WCAG 2.2](https://www.w3.org/TR/WCAG22/). Apply its relevant reading, focus, reflow and non-color principles to native components, then test the complete application. A palette check does not establish conformance.

## Large text and localization

At enlarged text, stacked forms are the default. Place value, unit and reference fields on separate rows when paired fields no longer fit. Medication actions wrap into full-width rows. Expand sheets to full height when necessary; keep the title and primary action reachable by scrolling. A keyboard must not cover the selected field, its error or its confirmation action.

Root navigation keeps readable labels. Use native adaptive navigation behavior if the available width cannot accommodate all labels; do not silently remove labels or shrink the text. Keep the same destination order and accessible selected state. The profile control may occupy its own row.

Use real English, Portuguese and Spanish resource strings. Test Portuguese action labels and long medication/report names. Localize interface dates, numbers and date/time input while preserving original clinical text, values and units. Locale-sensitive decimal parsing must retain the existing ambiguity review behavior. Language names use text, not flags. In-app language and appearance selection are proposed enhancements; current interface language follows the device.

Use logical start/end alignment. In RTL layouts, mirror directional navigation where appropriate, but preserve logo geometry and the reading of numeric values, source units and charts. Inspect mixed-script names and values rather than applying a blanket horizontal mirror.

## Dark mode, contrast and motion

Use the independently mapped light/dark tokens in the design system. Vivid category panels retain recognizable color in both themes while their text pairing remains readable. Aqua/mint is suitable as a filled panel with dark text; it is not small text on white. Clinical cards, source previews and PDF pages remain opaque. A PDF preview displays the document's actual white page in a dark viewer, rather than recoloring the exported document.

Honor increased contrast, reduced transparency and reduced motion. Native glass may appear in supported iOS navigation and controls, but must fall back through native accessibility settings. It must not blur a value, date or prescription instruction. Android uses semantic Material surfaces. Keep transitions at 120–200ms, and use an immediate/static presentation under reduced motion. No flashing alerts, breathing privacy claims or animation-dependent information.

This separates expressive product color from platform materials. Apple describes Liquid Glass as a functional layer for controls and navigation; Android adapts navigation between a compact bottom bar and a rail in wider windows. See [Apple materials](https://developer.apple.com/design/human-interface-guidelines/materials) and [Android adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation), checked 1 November 2026. Adoption depends on the platform versions and APIs actually supported by the implementation.

## Phone, tablet and resizing

The 390 × 844 reference canvas is a layout example, not a fixed runtime size. Support narrower phones, landscape, split windows and safe-area changes. Keep phone gutters at 24 where possible and 20 on narrow displays. Do not scale the whole screenshot to fit.

| Available space | Layout |
|---|---|
| Compact phone | One content column, five labeled bottom destinations, focused detail/form pages and native sheets |
| Wider window | Navigation rail or native sidebar; preserve labels, order, selected destination and profile |
| Records with room for two readable panes | Report/result list at the leading side, selected detail at the trailing side; no forced detail selection in an empty list |
| Import review with room for two readable panes | Source page and selected editable result side by side; collapse to source/review switching on phone |
| Export preview | Page thumbnails or page list beside a readable document preview; configuration remains reachable |

Choose breakpoints using current platform window size classes and measured text capacity. A two-pane layout must collapse if each pane becomes too narrow for its content. Preserve selection, scroll position and unsaved values across resizing.

## Native system handoffs

Illustrative OS screens in this design package identify a transition. They are not instructions to recreate Apple or Android permission dialogs in app code. Use platform APIs and let the installed OS supply their wording, controls and appearance.

| User action | Native handoff | Return behavior |
|---|---|---|
| Choose a report | Android document/image picker; iOS Files or Photos picker | Cancel returns to the import source screen unchanged. An unreadable file keeps an actionable error and another source option |
| Take a photo | Platform camera/capture flow and required permission handling | Cancel preserves the draft. If unavailable, offer Files/Photos; do not present a camera permission as mandatory for all imports |
| Choose collection date/time | Native date/time controls with locale-aware entry | Return the explicit selected value. Do not silently substitute today for an unknown collection date |
| Connect health data | Health Connect on Android or HealthKit on iOS | Show the actual supported sources, linked household profile and returned data. Do not equate an empty query with proof of denied permission |
| Enable medication notifications | Native notification authorization and OS notification settings where supported | A denied permission leaves medication records usable and shows how to review settings. Never claim a reminder is scheduled when the platform reports failure |
| Unlock | Native biometrics/device credential authentication | Cancel/failure leaves the neutral lock screen with Unlock available. Do not add an app-owned biometric dialog or invented account password |
| Share PDF/CSV | Android chooser or iOS share sheet | Cancel returns to the prepared preview. Preparation success is not proof that another app received the document |
| Save/open backup | Native file destination/document picker | Cancel does not report success. Preserve retryable passphrase and validation states without exposing the secret |
| Activate or retrieve model assets | Existing store verification and platform asset delivery | Store failure affects the model operation only. Records, medication tracking and exports remain available |

Native input selection, copy/paste, keyboard dismissal, system Back and iOS back gestures must remain functional. Do not add duplicate top toolbars over the native shell. When a lock activates, dismiss native app-owned sheets and remove health data from the accessible UI tree.

## Screenshots, app switching and privacy

All design images, implementation screenshots and OS-handoff examples must use synthetic records. Show generic share destinations and fictional files only. No real contacts, account avatars, recent photo thumbnails, medication records or file paths may appear in published previews. Do not upload user health data to create reference images.

Preserve the existing platform behavior and describe it precisely:

- Android currently enables `FLAG_SECURE` with the app-lock setting. Preserve its application to protected app windows. Do not disable it in a production change merely to obtain screenshots. Capture synthetic fixtures in a controlled development/test configuration where applicable.
- iOS currently places an opaque cover above native sheets when the scene becomes inactive and app lock is enabled. Preserve that cover for app-switcher snapshots. The cover should show only the mark or a neutral lock symbol; no active profile, medication name, notification count or blurred health content.
- The iOS cover is an app-switcher protection, not a promise to prevent every user screenshot or screen recording. Android also documents limits to `FLAG_SECURE`; do not label either implementation “screenshot-proof.” See [Android sensitive activities](https://developer.android.com/security/fraud-prevention/activities).
- Current lock behavior includes locked launch and relocking after five minutes in the background when enabled. Preserve the policy unless a separate approved behavior change is implemented. Missing device credentials must use the existing suspended/unavailable state rather than trapping the user.
- OS-owned share sheets and external destinations have their own behavior. The app should preview the selected profile and export content before invoking them. Never imply that a user-shared document stays inside the vault.

Code evidence: `composeApp/src/main/java/app/vezuna/AndroidMainActivity.kt`, `iosApp/Vezuna/AppLockSupport.swift`, `shared/src/commonMain/kotlin/app/vezuna/security/AppLock.kt`, and `shared/src/commonMain/kotlin/app/vezuna/ui/AppLockUi.kt`.

## Verification before implementation is called complete

1. Walk through import/review, a medication action and export with VoiceOver or TalkBack. Confirm the active profile, values, errors and next action are understandable without sight.
2. Inspect Today, result detail, review form, medication form and settings at default and enlarged text in light/dark themes. Check long Portuguese copy, a narrow display and the keyboard-open state.
3. Resize a tablet list/detail and source/review flow. Verify selection, focus, drafts and safe-area behavior; repeat using an external keyboard.
4. Test permission cancellation, no returned health readings, model unavailability, file/share cancellation, wrong backup passphrase and failed unlock. Each must have a usable recovery path without losing unrelated records.
5. Check app-switcher captures and locked screens with synthetic records in the foreground. Verify that no sensitive content appears behind overlays or in the accessible tree. Record Android and iOS results separately; image generation is not device validation.

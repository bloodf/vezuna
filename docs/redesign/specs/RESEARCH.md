# Health and fitness design research

Reviewed 1 November 2026. The user requested a lively, colorful redesign and rejected the initial pale/plain direction. **Vezuna Living Color** is the resulting direction. This document records primary references and explains the choices to carry into every screen.

These references inform visual hierarchy and interaction. They are not evidence for adding another product's medical claims, scoring algorithms, cloud services or device capabilities to Vezuna. Observations below come from official product pages, product documentation and platform guidance. Design applications are our interpretation.

## Gentler Streak

Gentler Streak presents fitness data through a colorful, approachable interface with a recognizable character and a visible Activity Path. Its official redesign notes describe bringing relevant wellbeing information into the main Streak view and reducing the number of tabs. The underlying message is supportive rather than punitive. See the [official product page](https://gentlerstories.com/gentlerstreak), [A more intuitive Streak](https://docs.gentler.app/release-notes-and-announcements/a-more-intuitive-streak), and [Apple's design interview](https://developer.apple.com/news/?id=3m0ht22s).

For Vezuna, this supports larger areas of purposeful color, a warmer onboarding/empty-state personality, and a useful Today view. A person should recognize Records or Care before reading every row. Keep the actual task at the top. An empty medication schedule should invite adding a record without guilt.

Do not reuse Yorhart, the Activity Path, proprietary illustrations or workout recommendations. Vezuna's existing symbol and original small illustrations establish its own identity. A colorful clinical record screen does not need a mascot or a fabricated health score.

## Bevel

Bevel's public presentation groups distinct health metrics into prominent summary areas. Its support documentation explains daily dashboard navigation, historical dates and metric-specific interpretation; it also describes using color with baseline comparisons. See the [official product page](https://www.bevel.health/), [daily data guide](https://help.bevel.health/en/articles/10436737), and [common questions](https://help.bevel.health/en/articles/11257601).

For Vezuna, use a distinct color for each content category, a large recorded value with its unit, and concise date/source context. A lavender trend panel can hold a real series; a coral Care panel can hold the next dose and its actions. Color should organize substantial content rather than appear only in tiny icons.

Do not import Bevel's Recovery, Strain, Sleep or Nutrition scores. Vezuna may display only measures and calculations its data model actually supports. A displayed percentage needs a named denominator and a real calculation, such as the existing medication adherence calculation.

## Oura

Oura's official redesign article separates Today, immediate metrics and longer-term health views. It describes a single daily focus and a stronger color hierarchy. This is a documented design direction from its November 2025 redesign, not a claim that every current Oura screen looks identical. See [A new look for the Oura app](https://ouraring.com/blog/new-app-design/).

For Vezuna, Today should emphasize one real next task, such as a scheduled medication or report awaiting review. Records and Trends keep history available without turning the home screen into every available chart at once. The deep pine top region gives the daily view a recognizable identity; coral or mint content can establish the current priority beneath it.

Do not copy Oura's readiness assessments, health recommendations, environment artwork or personalized baselines. Vezuna's priority is a task based on existing records, not an inferred state of health.

## Apple Health and Fitness

Apple Health presents health information by named categories and shows examples of recorded readings and shareable reports. Fitness provides a summary containing activity rings, recorded steps, distance and trends. See [Apple Health](https://www.apple.com/health/) and [Get started with Fitness on iPhone](https://support.apple.com/en-euro/guide/iphone/ipha5dddb411/ios).

For Vezuna, category identity, explicit units and a clear route from summary to detailed history make dense information approachable. Citron can highlight the existing activity/steps data, while mint, coral and lavender separate records, medication tasks and trends. Maintain the five labeled roots rather than relying on icon familiarity.

Do not add activity rings merely because they are visually recognizable. Every chart or progress shape must encode a supported quantity. Do not inherit Apple's device capabilities, clinical validation claims, sharing infrastructure or proprietary iconography.

## Current native platform guidance

Apple's current materials guidance places Liquid Glass in controls and navigation, separate from the content layer. Android's adaptive guidance uses a navigation bar in compact windows and a rail for wider layouts. These provide a way to feel current while retaining readable clinical data. See [Apple materials](https://developer.apple.com/design/human-interface-guidelines/materials) and [Android adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation).

Our application is a native shell with expressive solid color inside it. The top band and important panels supply the visual character. Native bars, sheets, permissions, authentication and sharing retain their platform behaviors. Clinical tables and source images stay opaque. Reduced transparency and motion must remain effective.

## Living Color decisions

| Role | Color | Use |
|---|---|---|
| Identity and top bands | Deep pine `#123B46` | Compact brand/profile context, clear page heading, white text |
| Records | Vivid mint `#71E2CC` | Import/review summaries, report identity, selected record context |
| Care | Coral `#FF8A65` | Next medication panel, schedule identity and care actions |
| Trends | Lavender `#B8ABFF` | Trend summaries, selected metric context and real plotted data |
| Activity | Citron `#DDF27C` | Existing steps/activity panels and their labeled values |
| Page | Warm paper `#F6F7F2` | Background that gives the colored content room to read |
| Text on bright panels | Ink `#152E35` | Titles, numbers, labels and instructions |

The bright panels are opaque and substantial. Use one dominant category color per task, with supporting colors only where other categories are actually represented. Today can combine a coral medication panel and a mint review panel because they identify different real tasks. Forms keep a vivid header or summary panel while grouping fields on a readable surface. Settings use larger colored category entries where useful, then concise grouped rows for individual preferences.

Category colors are stable across states. Coral does not mean danger; mint does not mean healthy. Actual errors and report-range comparisons use explicit labels and distinct local indicators. Avoid rainbow decoration, gradients behind numbers, faint tinted cards everywhere, white text over bright fills, and made-up scores. Color must not reduce spacing, typography, discoverability or clinical precision.

## Design review questions

1. Does the screen visibly belong to Living Color at thumbnail size, through a pine band or a meaningful saturated panel?
2. Can the user identify the current profile, section and next action without relying on color?
3. Does each large number, chart, color block and status correspond to an actual record, category or operation?
4. Do fields, source data and long instructions remain readable at large text sizes in both themes?
5. Does the screen retain Vezuna's own mark, copy and capabilities while applying the hierarchy learned from these references?

The image set and [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) must follow this direction consistently. Earlier pale/plain anchors are superseded and should not guide implementation.

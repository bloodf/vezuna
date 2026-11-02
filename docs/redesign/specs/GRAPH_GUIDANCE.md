# Readable history and long-range graphs

This guide governs app graph revisions dated 1 November 2026. [DATA_AND_COPY.md](DATA_AND_COPY.md) remains the source of fictional values, dates, units, and snapshot state. [PDF_SPEC.md](PDF_SPEC.md) governs exported page composition. No new clinical measurements were added for this revision.

## What the reader should see first

Show the marker or measurement name, latest value with its unit and date, selected period, and number of actual readings. Give the graph enough height to distinguish measured values without exaggerating changes. Keep the result history or an accessible list immediately below it. A source reference and a simple numerical difference can follow. Artwork must yield space to these elements.

Keep text on opaque surfaces. Use dark ink on mint, coral, lavender, and citron panels. Use a dark selected range control with white text, or mint with dark text. Range state cannot depend on color alone. Axis labels should remain at least 14 logical pixels and value labels at least 16 where practical; use a 16–17 pixel accessible list when the plot cannot fit every label. The SVGs are geometry references, not a reason to scale text below these sizes.

Use the repository Vezuna logo from `assets/brand/logos/`. The logo is the rounded curved V with a central dot. Do not use the decorative leaf illustration as the app mark. Prefer `lockup-dark.svg` on pine and `lockup-color.svg` on paper. Do not redraw the SVG or substitute typed wordmark text.

## Range controls and available history

The common controls are **3M, 6M, 1Y, All**. Announce them as “Three months”, “Six months”, “One year”, and “All recorded history” to screen readers. Use calendar-month subtraction in the device or user-selected local time zone. Preserve date-only collection dates without time-zone shifting; use the local time zone for actual timestamps and range boundaries. This does not introduce a profile-specific time-zone field. The controls filter the complete stored history, including records outside the first loaded list page.

For the 1 November 2026 snapshot:

| Selection | Selected window | Hemoglobin observations in window |
|---|---|---|
| 3M | 1 Aug–1 Nov 2026 | 28 Oct only. Show one result and no trend line. |
| 6M | 1 May–1 Nov 2026 | 15 Jul and 28 Oct. Two recorded points. |
| 1Y | 1 Nov 2025–1 Nov 2026 | All three readings, with the months before April left empty. |
| All | First through latest available reading | 12 Apr–28 Oct 2026. Three readings. |

Display both the selected period and available data when they differ. A one-year request does not mean that a year of measurements exists. In the one-year fixture, x-coordinates for 12 April, 15 July, and 28 October are 44.38%, 70.14%, and 98.90% of the axis span. The first point is before the May tick. Do not distribute these points evenly or place a point at the start of the window.

All is the default for the sparse fixture and fits the recorded history. The app must retain access to older data as a person's history grows. A broader range must not manufacture new points, duplicate records, or silently summarize multiple readings into an apparently raw value. If a dense production series needs aggregation, label the aggregation and keep the original dated observations accessible. Do not smooth laboratory results into an implied continuous measurement.

## Measurements, missing data, and units

Plot actual collection dates, not import dates. Keep the original precision: `13.0`, `12.8`, `13.2 g/dL`; `68.8`, `68.6`, `68.4 kg`. Numeric labels and ticks must use the same unit and conversion. Do not relabel a normalized g/L value as g/dL. Separate incompatible units. The original-unit demo in screen054 has a unit selector and only one observation in the selected group.

For numeric results, use visible point markers and straight segments only between actual observations when a connecting line helps reading. Never add intermediate observation dots. Stop the path at the first and last known point. For a regularly sampled device series, break the line where observations are missing. The weight reference uses isolated dated dots, because four of the seven days have no reading. Missing dates are not zero.

Use source ranges only when they are present and applicable to the displayed records. The fictional hemoglobin range is 12.0–16.0 g/dL for each of its three observations. The LDL source says “Under 130 mg/dL”; a dashed source-reference line can appear at 130 on its 120–160 mg/dL axis. These are report-specific examples, not universal clinical targets. If ranges differ, show them per record instead of one common band.

| Data shape | Presentation |
|---|---|
| No result in selected period | “No recorded results in this period”, with Show all history. Preserve the selected dates. No zero-valued chart. |
| One numeric result | Dated value card and “No trend yet”. No line, percentage, or invented predecessor. |
| Multiple compatible numeric results | Dated plot plus original values and sources. |
| Qualitative result | Dated text records. No numeric axes, zero placeholders, line, or change percentage. |
| Unreviewed OCR result | Review workflow only. Exclude from confirmed graphs and exports. |
| Missing source file after restore | Keep confirmed value and source metadata; explain that the original file is unavailable. |

The HBsAg fixture has exactly one text result: “Not detected” on 28 October 2026, source “Hepatitis B screening”, with reference assessment “Not assessed”. TSH has exactly one numeric result, 2.1 mIU/L on 28 October. Neither has a trend line.

## Plain numerical summaries

Prefer one short statement such as “+0.4 g/dL since 15 Jul”. State the comparison date and unit, and keep it visually neutral. Do not use green/red arrows to imply improvement or harm. A plus or minus sign is sufficient. Derive comparisons from compatible observations in the selected window; if only one remains, hide the change summary. If a product later compares against a value outside the selected window, label that scope explicitly.

| Series | Latest compared with previous recorded value | Latest compared with first available value |
|---|---|---|
| Hemoglobin | +0.4 g/dL since 15 Jul | +0.2 g/dL since 12 Apr |
| LDL cholesterol | −8 mg/dL since 15 Jul | −14 mg/dL since 12 Apr |
| Steps | +140 steps since 30 Oct | +370 steps since 25 Oct |
| Daily average heart rate | −1 bpm since 30 Oct | −1 bpm since 25 Oct |
| Weight | −0.2 kg since 28 Oct | −0.4 kg since 25 Oct |

These are arithmetic differences only. Do not infer treatment response, diagnosis, health risk, or a wellness score.

## Device and dose histories

Device data is available only for 25–31 October 2026. Steps are the seven daily totals 5,870; 7,120; 6,845; 5,930; 7,480; 6,100; 6,240. Daily average heart rate is 73; 72; 71; 74; 72; 73; 72 bpm on those dates. Weight has three readings, on 25, 28, and 31 October. The last import is 1 November at 09:35. There is no hourly heart-rate fixture and no earlier device history. “Daily average” must not become “resting heart rate”.

Screen067 and `dose-history.svg` are an **isolated post-action variant**. They include the 1 November dose recorded as Taken at 09:42. Across 25 October–1 November this variant has six taken, one skipped, and one missed. Its timeline uses dates and recorded statuses, with no adherence percentage or medical judgment.

The primary snapshot at 09:41 still has the 1 November dose pending. The completed baseline export period remains 25–31 October with five taken, one skipped, and one missed. Do not copy the post-action count into Today, the baseline dataset, or PDF-07. “Missed” is an app event state and does not prove a dose was not taken.

## Screen mapping and editable references

| Screen | Revised content | Vector reference |
|---|---|---|
| 023 | Hemoglobin detail, All, three dated results, +0.4 from 15 Jul | `charts/hemoglobin.svg` |
| 050 | Labs overview, All, HGB and LDL histories, TSH single-result state | `charts/hemoglobin.svg`, `charts/ldl.svg` |
| 051 | Hemoglobin, 1Y selected, visible empty months before April | `charts/hemoglobin-year.svg` |
| 052 | One dated qualitative HBsAg record, no numeric plot | No chart asset |
| 054 | One HGB record in the selected original-unit group | No chart asset |
| 067 | Post-action dose events, 25 Oct–1 Nov | `charts/dose-history.svg` |
| 072 | Device overview, seven steps bars, latest heart rate and weight | `charts/steps.svg` |
| 073 | Seven daily average heart rates and dated list | `charts/heart-rate.svg` |
| 134 | Dark Hemoglobin detail, All | `charts/hemoglobin.svg` with semantic dark-theme styling |
| 142 | Tablet record list/detail, All | `charts/hemoglobin.svg` |

Paths above are relative to `assets/vectors/`. `charts/weight.svg` supports device details and proposed exports. `charts/fixture-data.json` retains the unchanged canonical measurements. `charts/reference-layouts.json` records the exact coordinate calculations and window boundaries. Run `generation/build_graph_references.py` to rebuild the seven references.

Build ordering matters because the older `tools/build_vectors.py` writes five chart references and replaces the UI catalog. From the pack directory, run these commands in order when rebuilding all vector assets:

```sh
python3 tools/build_vectors.py
python3 generation/build_graph_references.py
python3 generation/build_asset_map.py
```

The graph script rewrites all seven chart SVGs and idempotently registers them in `assets/vectors/ui-catalog.json`, preserving non-chart catalog entries. It does not change measurement values. Run it after the older vector builder and before asset mapping, validation, previews, or ZIP packaging. A chart-only rebuild needs only the middle command.

The SVGs contain editable vector shapes and text, without embedded raster images or external resources. Implement real chart components from stored data; never ship these fictional SVG plots as patient results. Raster mockups can approximate coordinates. The data, SVG calculations, and this guide take precedence where pixels differ.

## Interaction and verification

Selecting a range updates the plot, count, visible history, and change summary together. Keep the chosen profile and marker. Tapping or focusing a point reveals its original value, unit, collection date, report range, and source. Offer the same information in an ordinary dated list. A screen reader must be able to traverse records without interpreting path geometry.

At large text sizes, put the range controls on their own row and stack values, dates, and actions. The history can scroll. Do not shrink clinical text to preserve decoration. At tablet widths, enlarge the chart and keep the source list nearby. In dark mode, use opaque dark surfaces and verify contrast for grid, axes, values, and selected controls.

Verify the first/last dates, raw values, count, source units, period boundaries, and comparisons against the fixture. Check a period with zero results, one result, mixed units, qualitative text, missing device days, and the isolated post-action dose state. Ensure no marker appears at an unrecorded date and no data line crosses empty time before the first measurement.

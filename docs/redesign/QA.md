# Review and implementation limits

## Completed checks

The pack was grounded in the existing Compose/native routes, domain models, import/review, backup, local-model and export code. A separate source audit checked current/proposed capability boundaries and native Android/iOS differences. The Living Color direction follows the user's explicit correction to the initial pale concept.

Images were generated separately with the built-in image tool, then visually reviewed during generation. Clear unwanted additions, incorrect states and misleading data were regenerated where identified. All accepted assets are copied into this folder. Packaging validation checks the full manifest, PNG integrity, unique IDs, file paths, internal links and ZIP contents.

## Reference fidelity

The images specify visual hierarchy, color, density, component families and workflow composition. They are not pixel-perfect executable screens. Generated typography, icon shapes, numbers, source filenames, selected states and plot axes can differ from the written specification. Use the exact real logo assets, canonical DATA_AND_COPY fixture and current application data.

For example, report table values are authoritative in the written fixture, not the geometry of a generated chart. Recalculate dates, axis ticks and plotted positions from observations. Localize interface labels without translating original clinical strings unless the source data itself is localized. Do not add patient sex, diagnoses, medications, health scores or other fields suggested incidentally by an image.

Some screen images simplify visible rows to fit a phone viewport. This does not authorize truncating real data or hiding required fields. Implement scrolling and pagination. Representative dark and large-text images establish the adaptation rules for every screen; they are not the only screens requiring accessibility support.

OS-owned mockups are handoff illustrations. Permission wording, icons and layout vary by OS. Invoke the real API and verify actual native behavior. Do not use generated brand-like OS icons as production assets.

The production vector library supplies 30 original illustrations, 86 icons, 7 chart references, 4 fictional document excerpts and the 8 existing logo variants. These are editable vector replacements, not exact traces of generated decorative pixels. SVG masters contain no embedded raster images, scripts or remote dependencies. Every screen has an asset/runtime/native-control mapping. Native conversion and actual rendering still need device verification.

The independent final review caught and resolved inconsistent decimal examples, inadequate control-boundary contrast, an outdated decoration restriction and imprecise persisted-MISSED wording. The final light control outline measures 3.82:1 on white and 3.55:1 on paper; the dark outline measures 3.63:1 on dark cards and 4.44:1 on dark background. Gallery search, workflow filtering, full-image preview and next-image navigation were exercised in a browser.

## PDF review

The twelve images show independent layouts/states, except pdf-02 and pdf-03, which form a two-page lab-history example. The actual app exporter must use selectable text, original values/units, actual-date charts, repeated headers, real page numbers, A4/Letter reflow and a textual chart alternative. Grayscale has its own reference.

The generator added unwanted fields or sections to some initial PDF attempts. Those attempts were replaced. In particular, unrequested demographic text and invented clinical charts were removed from the continuation, selected-results and source-appendix images. The remaining visual references still require implementation-level numeric, locale and pagination verification.

## Updated brand and history review

The full 162-image set was reviewed for Vezuna identity. Visible substitute leaf/sprout marks were replaced using the repository's official logo PNGs as image-edit references. App screens without a visible brand mark kept their existing artwork. Decorative foliage is intentional and is not the logo. Visual matching of generated raster marks is not a guarantee of identical paths; the copied, unmodified SVG masters are the implementation source.

Ten app history views were revised with range selection, clear dates/units, available-history context, and appropriate numeric or categorical representations. Four export previews were updated to match the new health summary. All twelve PDF page references were revised for the official logo and clearer reading order. Dated changes are arithmetic comparisons, not claims about improvement or deterioration. One-reading, qualitative and missing-data states remain truthful.

Use the deterministic chart SVGs and GRAPH_GUIDANCE for precise geometry. Generated chart pixels can still approximate date spacing or tick placement. The canonical synthetic dataset and actual runtime data take precedence. In particular, the one-year example has only three recorded HGB readings; longer time controls must not invent additional observations.

Two real sample PDFs were rendered and inspected across all four pages. Strict PDF parsing, A4/Letter dimensions, embedded Noto fonts, selectable fixture values and absence of raster images passed. Letter color operands were checked for grayscale. The samples include a plain-text alternative but are not tagged PDF/UA; production tagging and reading-order verification remain required. See `generation/pdf-sample-verification.json` for evidence.

The revised gallery search and full-image viewer were exercised with screen051, and the sample-PDF link resolved. Final package checks cover all 162 unique PNGs, 135 indexed SVG assets (147 including legacy brand icons), all 162 asset mappings, local links and JSON. All 16 supplied logo SVG/PNG masters match the repository originals byte for byte. No application code was modified. The archive also contains file checksums; `tools/verify_archive.py` checks every packaged member.

## Not performed

No application redesign was implemented or run. No native accessibility audit, device permission test, store entitlement test or actual exporter regression test is claimed by this image pack. The handoff includes the acceptance criteria the coding agent must verify in the real application.

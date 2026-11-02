# Vezuna Living Color — updated handoff

Open **index.html** for the visual gallery. Give the entire folder to the implementation agent, together with the Vezuna repository, then paste **HANDOFF_PROMPT.md**.

This pack contains **150 app screen/state images and 12 PDF page-design images**. It is a redesign specification, not a modified build of the application. The visual direction uses deep pine, vivid mint, coral, lavender and citron. It incorporates research into Gentler Streak, Bevel, Oura and Apple's health/fitness interfaces.

This revision corrects the mockup branding to the existing rounded V/person symbol, expands history charts in the app, and redesigns the PDF references around readable dates, values and comparisons. See [REVISION_NOTES.md](REVISION_NOTES.md) for the changes and [GRAPH_GUIDANCE.md](specs/GRAPH_GUIDANCE.md) for long-term history behavior.

All custom illustration families and icons have editable SVG sources. Open [assets.html](assets.html) for the vector library and [VECTOR_ASSETS.md](specs/VECTOR_ASSETS.md) for native integration guidance. The SVG drawings are canonical, coherent replacements for the generated motifs; they are not pixel-identical traces of the mockups.

The indexed library includes **30 illustrations, 86 icons, 7 chart references, 4 fictional document excerpts and 8 existing brand marks**. Each of the 162 screen references has an asset map, including native OS elements and data-driven shapes.

## Use this pack

1. Browse [index.html](index.html). Search by screen name or ID, or filter a workflow.
2. Read [SCREEN_INDEX.md](SCREEN_INDEX.md) to see every image and its scope.
3. Give the folder and [HANDOFF_PROMPT.md](HANDOFF_PROMPT.md) to the coding agent.
4. Ask it to implement and verify each complete workflow using the supplied coverage ledger.

## Files

| File or folder | Purpose |
|---|---|
| images/app | Individual app screens, dialogs, empty/error/loading states, system handoffs, dark/language/large-text/tablet variants |
| images/pdf | Twelve individual printable page-layout references, including continuation, sparse data, Portuguese and Letter/grayscale |
| pdf-samples | Actual two-page lab-history PDFs in A4 color and US Letter grayscale, with selectable text and vector charts |
| screen-manifest.json | Machine-readable image index, scope and screen descriptions |
| coverage.csv | Implementation tracking rows for every image ID |
| specs | Design system, flow map, source audit, data/copy, PDF rules, accessibility and platform guidance, research |
| assets/brand | Exact existing Vezuna logos, UI icons, local fonts and their licenses |
| assets/vectors | Editable illustration, icon and chart SVG sources with MIT license |
| assets.html | Searchable offline vector asset gallery |
| assets/screen-assets.json | Per-screen mapping to artwork, runtime shapes and native OS elements |
| generation | Full image prompts and generation metadata, using the built-in image tool |
| QA.md | What was checked and how to interpret generated reference images |
| checksums.sha256 | Integrity hashes for the delivered files |

Open the [A4 color PDF](pdf-samples/vezuna-laboratory-history-a4.pdf) or [Letter grayscale PDF](pdf-samples/vezuna-laboratory-history-letter-grayscale.pdf) to inspect readable print examples. These are synthetic reference documents, generated separately from the app. They are not evidence that the application exporter has already been updated.

## What “complete” means here

The set covers the app's existing workflow families, their distinct user-visible states, the proposed redesign flows, native handoffs and representative adaptive variants. It does not enumerate every possible combination of user data, OS version, permission wording, locale, font scale or theme. Shared state templates handle those permutations; exact reason-specific messages are recorded in the implementation guide.

Each manifest entry marks an existing redesign, a proposed feature, a conditional capability or an OS-owned handoff. Proposed screens are intentional design scope, not claims that their behavior already exists. The local model catalog is empty in the current repository, so ready-model images are conditional examples.

All health data is synthetic. Images are layout references. Use specs/DATA_AND_COPY.md and actual domain state for values and copy; do not copy raster mistakes or approximate chart geometry into code. Exact logos and fonts are supplied separately. The official symbol is an open rounded V with one dot; foliage is never a substitute logo. PDF images are layout references. The implementation must generate actual text and vector content with correct pagination.

The discarded pale first concept is excluded from this pack. Application source code was not changed.

## Repository copy

This folder is the committed subset of the owner's Living Color pack (`design/redesign-2026-11-01`, kept local and ignored by git). The PNG references in `images/`, `index.html`, `assets.html`, `generation/` and `tools/` are not committed; links to them in these documents resolve only inside the local pack. The ledger (`ledger.csv`) refers to every image by ID. Vector masters live in `brand/vectors/`, and chart reference data in `brand/vectors/charts/`. Document excerpts used only during development are in `reference-vectors/`.

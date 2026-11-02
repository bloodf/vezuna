# Updated handoff — official branding and readable history

This revision keeps the complete Living Color design pack: 150 app/state images, 12 PDF page references, the editable SVG library, source audit, research, and implementation guidance. Forty-eight images were revised: 36 app screens and all 12 PDF references. It supersedes the previous ZIP.

## Official identity

Brand-bearing mockups now use the existing Vezuna identity as their image-edit reference: two thick rounded curved strokes forming an open V around one circular dot. The supplied original SVG and PNG logo files are unchanged. A leaf or sprout is not an alternative logo. The interface can still use the original botanical decorative motifs.

Production must use the exact SVG masters rather than tracing the generated marks or recreating the wordmark with a font. Their hashes are verified against the repository brand assets in the delivery checks.

## App history

Updated images: 023, 050, 051, 052, 054, 067, 072, 073, 134 and 142. They cover numeric detail, history overview, a one-year view, text results, separate units, dose events, device measurements, dark mode and tablet layout.

The design adds 3M, 6M, 1Y and All controls, visible dates/units, latest values and clear comparison context. Numeric observations use date-based plots; dose events use a labelled categorical timeline; text results and single points do not pretend to be numeric trends. The longer range example retains blank time before the first recorded observation.

Seven deterministic chart SVG references include a one-year hemoglobin view and dose-history timeline. Their source data is bundled. See [GRAPH_GUIDANCE.md](specs/GRAPH_GUIDANCE.md) for filtering, comparison calculations, interaction, missing data and accessibility.

## PDF reading experience

All twelve PDF images were updated. The health summary now introduces the two available multi-reading histories with dated graphs and changes since the first and previous results. Complete lab history pairs graphs with values and source context. Medication, device, sparse and text-only pages use representations that suit their data.

The specification requires plain headings, readable body/table text, explained chart marks, original units, selectable text, real pagination and a grayscale alternative. Extra rows must create another page instead of shrinking the document. Export previews 118, 120, 124 and 138 now display the revised summary.

Two actual two-page sample PDFs are included: [A4 color](pdf-samples/vezuna-laboratory-history-a4.pdf) and [US Letter grayscale](pdf-samples/vezuna-laboratory-history-letter-grayscale.pdf). They use vector charts, the original SVG logo paths, embedded Noto Sans, selectable text and a plain-language chart key.

The eight confirmed observations and synthetic person are unchanged. There are no new diagnoses or clinical interpretations. The dose-history app example is a separate post-action state; the seven-day PDF dose log keeps its original snapshot. Read [DATA_AND_COPY.md](specs/DATA_AND_COPY.md) for exact values and [PDF_SPEC.md](specs/PDF_SPEC.md) for export behavior.

## Delivery

Start with [index.html](index.html), browse [assets.html](assets.html), and give the complete extracted folder plus [HANDOFF_PROMPT.md](HANDOFF_PROMPT.md) to the implementation agent. Use [coverage.csv](coverage.csv) to track every screen. This is a design and reference-artifact handoff; application code has not been changed.

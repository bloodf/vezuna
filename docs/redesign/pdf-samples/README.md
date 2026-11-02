# Vector PDF samples

These are fictional print design samples, not clinical documents or exports produced by the current application.

- `vezuna-laboratory-history-a4.pdf`: two A4 pages with restrained Living Color accents.
- `vezuna-laboratory-history-letter-grayscale.pdf`: the same records on two US Letter pages, using grayscale throughout.
- `vezuna-laboratory-history.txt`: the same information as plain text.

The samples use the exact paths from `assets/brand/logos/lockup-ink.svg`, embedded bundled Noto Sans, selectable 11–12 pt body/data text, real collection-date spacing and vector graphs. Each history has separate changes since its first and previous results. The second page includes the complete six-row numeric history table, TSH, the qualitative result, medication and scope. No additional measurements were invented.

The PNG concepts in `images/pdf/` are visual references. These real PDF files demonstrate printable geometry and selectable text; they are not pixel copies of the concepts. The generator is `generation/build_pdf_samples.py` and requires Python with `reportlab` and `fonttools`. Run it from any working directory; its paths resolve from the script location. `pypdf` and Poppler are useful for verification.

The PDFs have document language metadata and a text alternative, but do not have a tagged PDF/UA structure. Production must add tags, table headers, chart descriptions and verified reading order as specified in `specs/PDF_SPEC.md`. Selectable text alone is not a claim of complete accessibility.

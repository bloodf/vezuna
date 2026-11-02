# Vector assets for implementation

Every custom illustration family, decorative motif, navigation/action icon and example chart has a supplied vector source. Browse [assets.html](../assets.html), then use [ASSET_MAP.md](ASSET_MAP.md) and [screen-assets.json](../assets/screen-assets.json) to select the files for a screen.

The SVGs are editable paths and shapes, not PNG files wrapped in SVG. Illustration SVGs are consistent production replacements for the generated motifs. They are not exact pixel traces of the raster previews: shadows, leaf placement and drawing details can vary. Use the supplied SVG artwork consistently throughout the implementation rather than recreating a slightly different drawing from each screenshot.

## Sources of truth

| Visual element | Source | Runtime behavior |
|---|---|---|
| Vezuna logo and mark | assets/brand/logos/*.svg | Use exact existing brand paths. Do not redraw or typeset a substitute logo. |
| Foliage, capsule, papers, empty states, security, device and assistant art | assets/vectors/illustration-*.svg | Scale with preserved aspect ratio, use decorative semantics when adjacent copy conveys meaning. |
| Navigation and action icons | assets/vectors/icons/*.svg | 24-unit viewBox, 1.8-unit rounded stroke, currentColor. Bind tint to semantic tokens. |
| Chart geometry | assets/vectors/charts/*.svg | Example only. Recalculate from actual dated observations; use fixture-data.json for reference checks. |
| Avatars | Native circle and live initials | Never ship a raster avatar with fictional initials. |
| Cards, buttons, chips, sheets, progress and tabs | Native layout, shapes and text | Keep labels localizable and interactive controls accessible. |
| OS status, keyboard, permission, picker, authentication, share and notification UI | Actual Android/iOS APIs | OS supplies these assets. Do not copy the generated system UI or vendor-like marks into the app. |
| Source document shown in review | User's actual PDF/image | Clinical source content is data, not bundled app artwork. It is not replaced with a vector drawing. |
| PDF text, tables, dividers and category bands | Native/vector PDF rendering | Keep text selectable and Unicode-aware; use actual values and pagination. |

## Native integration

Preserve the vector masters in the repository. Import or convert them through the existing Android/Compose and iOS asset pipelines supported by the app's current dependency versions. Do not add an online image loader or a runtime web view to display the artwork. Where an Android vector importer does not support an SVG feature, convert the geometry to the supported native equivalent and compare its output against the master.

Resolve currentColor to the appropriate ink/white/accent tint at render time; converters may require an explicit color. Keep icon visual sizes around 20–24dp while the interactive target is at least 48dp on Android and 44pt on iOS. Use one stroke family. Do not scale stroke width independently from its icon unless it is a deliberate optical adjustment.

Keep decorative art out of the accessibility tree. Meaningful illustrations need a short localized description; adjacent text should not be announced twice. Follow reduced-motion settings. The loading icon is a static vector master, not permission for an endlessly animated page.

Use mint/coral/lavender/citron for category identity, never to infer clinical status. Status labels still need semantic text and symbols. Dark mode preserves vivid artwork against the dark surfaces; avoid coloring dark-stroke icons with insufficient contrast.

Chart SVGs deliberately contain fictional reference values. Do not ship them as live health graphs. Dates determine x positions; incompatible units and qualitative results must not enter numeric plots. Missing observations remain missing. Provide an accessible text/table equivalent and follow PDF_SPEC for printable charts.

## Open-source use

New vector artwork uses the repository's MIT license, copied to assets/vectors/LICENSE. Existing brand assets retain assets/brand/LICENSE; Noto Sans retains assets/brand/fonts/NOTO-LICENSE.txt. No competitor artwork is included. Native vendor UI is represented only as a system handoff in the mockups.

The SVG validation report checks parseability, viewBox, absence of embedded raster images/scripts/external dependencies, and asset-map paths. Rendering still needs verification after native conversion on both platforms.

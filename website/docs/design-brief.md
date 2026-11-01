# Tech-health visual upgrade brief

## Concept

A local clinical-record engine rendered as a spatial data instrument: precise, technical, calm, and unmistakably connected to laboratory health records rather than generic science fiction.

## Requirements checklist

- Animated 3D elements and custom GLSL shaders in the marketing hero.
- Health-specific content: OCR drafts, confirmed observations, units, profiles, and longitudinal comparisons.
- English, Brazilian Portuguese, and Spanish remain complete for new interface copy.
- GitHub action remains live; App Store and Google Play states must be honest because no listings are verified.
- Real product screenshots and release-preparation claims remain intact.
- Responsive, keyboard-accessible, reduced-motion-aware, and performant on phones.
- No medical diagnosis cues, fake clinical readings, or generated screens presented as product captures.

## Reference feature to implementation

| Verified feature | Implementation |
| --- | --- |
| App preserves original result values and units | Synthetic `Hemoglobin 13.2 g/dL` label with an explicit fictional-data caption |
| OCR candidates require confirmation | Spatial draft plane transitions into a bright confirmed node |
| Confirmed results form comparisons | Two glowing observations connected by a longitudinal line chart |
| Profiles are isolated | Three separated orbital record bands around a central local vault |
| Brand uses two curves around a person | Layered orbital geometry, without redrawing or replacing the supplied logo |
| Deep navy, teal, aqua, and mist brand palette | Emissive aqua data, teal lines, translucent navy record planes |
| Real screenshots are the product evidence | Keep unmodified emulator captures below the abstract scene |

## Scene structure and scale

The scene uses abstract digital units. A 10 × 6 × 6 volume contains three tilted orbital bands, two transparent record planes, a longitudinal chart, and 900 shader-driven data particles. The camera sits at `(0, 1.2, 10)` with a slight pointer-responsive offset. HTML labels identify the processing stages; the canvas itself is decorative and hidden from assistive technology.

## Materials and shaders

- Record planes: custom GLSL Fresnel edges, procedural grid lines, scan sweep, and controlled transparency.
- Data particles: custom GLSL point-size attenuation, soft circular falloff, time-based orbital deformation, and aqua/teal color variation.
- Chart: emissive aqua line and discrete observation points, not a diagnostic trend or normal-range claim.
- Background: transparent renderer over the brand navy; no external textures or downloaded assets.

## Motion and interaction

Absolute-time animation drives record orbit, scan sweep, and particle drift. Pointer movement changes camera offset only on fine pointers. Scrolling does not block reading. Reduced-motion mode renders one static frame and disables pointer response. Hidden tabs and offscreen scenes pause the render loop.

## Routing

Everything is procedural Three.js inside the existing Astro website. No Blender asset is needed: there are no organic characters, complex manufactured forms, or external models. The website remains the primary deliverable; this is decorative 3D content rather than a separate editable 3D scene.

## Performance budget

Cap device pixel ratio at 1.5, use fewer than 40 draw calls and 100,000 triangles, avoid post-processing and real-time shadows, reuse all per-frame objects, and dispose renderer, geometry, materials, observers, and listeners on teardown. Desktop and phone browser checks must show no console errors or horizontal overflow.

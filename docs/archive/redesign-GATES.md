# Gates: Vezuna product-led redesign

Scope: replace the abstract marketing experience with clear lab, medication, weight, device-health, household, and export workflows in three languages.

- [x] G1: every route, translation, metadata entry, internal link, and availability action is valid
  CHECK: npm run build && node scripts/verify-site.mjs
  EXPECT: SITE_CONTENT_VERIFIED
  CWD: website
  EVIDENCE: automatic-evidence=v1; definition-sha256=6f13fe6ab26a09ae50a56101aaf440dfc5b8acc338fc1dc9ad922aa6da38a2a6; exit=0; EXPECT=matched; output-sha256=1e52bc4212a6b42ac7e72166c85d1713f31e423c23209a052bdafc28b2cb7dba; output-bytes=2499; shell=/bin/sh; cwd=/Users/heitor/Developer/github.com/bloodf/vezuna/.worktrees/redesign-health/website; path=e1d15faa0cd6/30 entries

- [x] G2: content tests, type checks, design checks, and the production build pass
  CHECK: npm test && npm run check && npm run build && node scripts/verify-build.mjs
  EXPECT: PRODUCTION_BUILD_VERIFIED
  CWD: website
  EVIDENCE: automatic-evidence=v1; definition-sha256=d8713d93fffa0d9579eaed7b6455847f348f57bb3db79aa7e1158d0b72369846; exit=0; EXPECT=matched; output-sha256=59a2100d3a05a31d321a66cca662766204c4295d6ef7d530a7329edcb97c3515; output-bytes=3208; shell=/bin/sh; cwd=/Users/heitor/Developer/github.com/bloodf/vezuna/.worktrees/redesign-health/website; path=e1d15faa0cd6/30 entries

- [x] G3: workspace interactions, responsive layouts, keyboard navigation, accessibility, and reduced motion pass in Chromium
  CHECK: node scripts/verify-browser.mjs
  EXPECT: BROWSER_VERIFIED
  CWD: website
  EVIDENCE: automatic-evidence=v1; definition-sha256=66d861d2854c260741c0df6a6fcd44fdb99d7b3e73f4c495677e30669ca8a238; exit=0; EXPECT=matched; output-sha256=67e52b37bf9f3549a1a7889b6a436e90468e7d367a75786dea88cac1bd1c7911; output-bytes=128; shell=/bin/sh; cwd=/Users/heitor/Developer/github.com/bloodf/vezuna/.worktrees/redesign-health/website; path=e1d15faa0cd6/30 entries

- [x] G4: the automatic Git deployment serves the redesigned site on the production domain
  CHECK: node scripts/verify-live.mjs
  EXPECT: LIVE_SITE_VERIFIED
  CWD: website
  EVIDENCE: automatic-evidence=v1; definition-sha256=2ff1eb2cf2daa327a38ed436010cfda6cd46e173e556b09c548da6c16e821a2f; exit=0; EXPECT=matched; output-sha256=d2c71ec52441f3685963962787ce155de6b0609a8e82168dce77ebcc34d243ff; output-bytes=71; shell=/bin/sh; cwd=/Users/heitor/Developer/github.com/bloodf/vezuna/.worktrees/redesign-health/website; path=e1d15faa0cd6/30 entries

- [x] G5: changes remain isolated to the website directory
  CHECK: node scripts/verify-scope.mjs
  EXPECT: WEBSITE_SCOPE_VERIFIED
  CWD: website
  EVIDENCE: automatic-evidence=v1; definition-sha256=43fa5c181afc357bae20ce37d337f502676c6e49c8247181d072bba549596c5a; exit=0; EXPECT=matched; output-sha256=8dc5158346758c70b44bff576496d419dafcd3748486c3b0a3787657b7ea1964; output-bytes=76; shell=/bin/sh; cwd=/Users/heitor/Developer/github.com/bloodf/vezuna/.worktrees/redesign-health/website; path=e1d15faa0cd6/30 entries

- [x] G6: desktop and phone screenshots show lab records, medications, weight, and device health clearly without decorative abstraction or clipping
  EVIDENCE: Reviewed desktop and phone captures of the new workspace, device metrics, and full page. Both lab values fit the mobile chart, weight 72.4 kg, heart rate 68 bpm, steps 6,842, medication actions, and household profiles are explicit, and the abstract scene is removed.

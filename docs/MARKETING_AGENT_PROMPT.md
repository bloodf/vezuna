# Vezuna marketing website and documentation

Build and ship Vezuna's marketing website at `https://vezuna.app` and public documentation at `https://vezuna.app/docs`. I purchased `vezuna.app` and it is already connected to Vercel. Complete the implementation, verify it in a real browser, and deploy through the existing Vercel project. Do not stop at a design proposal.

## Start from the actual product

The source repository is https://github.com/bloodf/vezuna. Use the current working tree supplied with this prompt; its identity rename and README assets may not have been pushed yet. If your checkout disagrees with the `app.vezuna` identity below, obtain the latest changes before documenting the native build. Read the current README, `docs/ARCHITECTURE.md`, `docs/DEVELOPMENT.md`, `docs/RELEASING.md`, `PRIVACY.md`, `SECURITY.md`, `THIRD_PARTY_NOTICES.md`, and `docs/STORE_REVIEW_DRAFT.md` before writing product claims. Inspect the current code when a claim is uncertain.

Vezuna is a paid, offline household health-record app for Android and iOS. It uses Kotlin Multiplatform, Compose, and native platform integrations. The Android application ID and iOS bundle ID are `app.vezuna`. The native app and its release tooling must remain intact. The website is a separate deliverable. Use the existing website repository if one exists; otherwise isolate website code in a dedicated directory or repository. Do not replace the root EAS tooling package, migrate the app to React Native, or implement a web version of its health vault.

Inspect the existing Vercel project and domain configuration before creating anything. Reuse the intended project and connected domain. If multiple projects or domains could match, ask me to identify the intended one. Let me handle login, passwords, and MFA. Do not modify unrelated domains, nameservers, accounts, or deployments.

## Design a recognizable Vezuna site

Use `brand/brand-guide.md`, `brand/tokens/tokens.json`, `brand/logos/`, `brand/ui-icons/`, `brand/fonts/`, and `brand/social/`. Preserve the supplied logo geometry. The core palette is deep navy `#123B46`, teal `#087F8C`, aqua `#71E2CC`, and the light background `#F5FAF9`. Use local Noto Sans and retain its license.

Create a calm, premium, editorial page with generous whitespace, strong typography, clear reading order, and carefully composed product imagery. Let the visual rhythm change with the content. Use the real app screenshots linked in the README and identify synthetic example records where relevant. Do not present generated UI as a working product screenshot. Keep charts and screenshots legible at phone widths. Use restrained motion that respects reduced-motion preferences.

The supplied English brand line is “Your health, in one place.” Build the copy around people keeping household records organized, checking imported results, and preparing information for a clinician. Keep engineering details in developer documentation.

## Deliver one complete marketing page

Create a responsive landing page with anchored navigation and these sections:

1. A distinctive hero with the real brand, a concise value proposition, product imagery, and an honest primary action.
2. A product story showing separate household profiles, image/PDF import, manual confirmation of OCR drafts, and longitudinal lab comparisons.
3. Medication organization, local reminders, and PDF/CSV exports, with clear examples of how they fit into daily use.
4. A concise explanation of local storage, optional health imports, and the boundary when users share exports.
5. An FAQ covering availability, supported devices, languages, permissions, backup limitations, and the app's role as a record organizer.

Finish with a useful footer linking documentation, support, privacy, source code, and third-party notices. Support English, Brazilian Portuguese, and Spanish for the marketing page, matching the app's supported languages. Give translated routes correct language metadata and navigation.

The repository is still in release preparation. Until real store URLs and availability are verified, use “Read the docs” and “View source” actions with a clear availability note. Do not invent App Store/Play links, prices, release dates, customer counts, testimonials, ratings, waitlists, or working downloadable models. Do not add a backend, newsletter, account system, or analytics unless I request one.

## Deliver useful documentation

Build a searchable, accessible documentation area at `/docs` with a sidebar, readable code examples, local anchors, and navigation back to the product page. Write complete English docs and localize essential getting-started and support guidance into Portuguese and Spanish. Distinguish user instructions from developer material.

User documentation must explain profiles, synthetic first-use examples, importing and correcting OCR, confirming results, comparisons, medications and reminders, health permissions, exports, deletion, backups, and offline/model limitations. Same-device encrypted backups do not provide portable cross-device or cross-app recovery. Document how to deny health permissions without blocking manual records. Never ask users to upload medical records to the website or public issues.

Developer documentation must cover the repository structure, native setup and builds, synthetic testing, localization and branding, model provenance and delivery, contributions, and release submission. iOS requires 18.5 or later. Expo is used only to submit externally built signed AAB/IPA files. Use the current source for all commands and platform versions. Explain that changing the application ID creates a separate installation and does not transfer an earlier sandbox or Android Keystore keys.

Create `/support` with verified support options. The owner confirmed `heitor.ramon@gmail.com` as the public support/privacy email on October 31, 2026. The intended store price is US$9.99, but it has not yet been saved or made available in either store. Keep any price copy clearly prospective until release availability is verified.

Prepare `/privacy` from implementation evidence, separating app behavior from website hosting behavior. The repository's `PRIVACY.md` is technical documentation. Publisher legal identity and retention commitments still need owner review before presenting a policy as final. Never publish fictional contact information or an unfinished legal template.

## Keep claims accurate

Vezuna organizes records. It does not diagnose, recommend treatment, or change doses. OCR drafts require user confirmation. App-owned health data stays on the device, while store services handle licensing and planned model delivery. SDK privacy behavior must be assessed separately. Do not claim independent security auditing, regulatory certification, absolute privacy, or completed physical-device testing without evidence.

Local model code exists, but production catalogs, hosted assets, licensing tests, and native-library attribution remain release gates. Read current source before describing AI availability. Do not publish private files, credentials, internal agent logs, ignored samples, or model binaries. Source and original artwork are MIT; fonts and third-party components retain their licenses.

## Verify and hand off

Use the existing website stack and current official setup documentation. Implement metadata, canonical URLs, language alternates, sitemap, robots rules, favicon, and social previews using the verified domain and original assets. Ensure keyboard navigation, visible focus, adequate contrast, semantic headings, descriptive alt text, comfortable touch targets, and reduced-motion support.

Test phone, tablet, and desktop layouts in a real browser. Check every internal link, documentation route, translation switch, navigation anchor, and external action. Run the production build and relevant lint/type checks. Inspect screenshots for clipping and tiny text. Confirm that the live deployment serves the intended domain and documentation routes without errors.

Return the live URL, repository/PR or changed-file references, representative screenshots, verification results, and any exact owner-dependent blocker. Clearly distinguish a deployed preview from production. The work is complete only when the actual site and documentation are usable, or an identified access or publisher-information blocker prevents the remaining step.

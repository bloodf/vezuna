> Historical snapshot only. Current repository instructions and central policy supersede all operational directions below. Historical claims are not fresh acceptance. See the evidence retention map at `.unlazy/t3-resume/local-pad-host-runner/EVIDENCE_RETENTION.md` for intentional omissions and unresolved recovery proof.

# Vezuna redesign audit

## Failed direction

- The orbital scene visualizes abstract technology instead of lab reports, medication routines, weight, heart rate, steps, or household care.
- Glow, particles, wireframes, and HUD labels consume attention without helping a visitor understand the product.
- The hero value and chart do not show the actual workflows of importing reports, confirming OCR drafts, tracking medications, or importing device health data.
- Random dark sections break the reading rhythm, and technical copy obscures the household use case.
- The 3D bundle and continuous animation add cost without improving comprehension.

## Route classification

| Route | Class | Business variable | What persuasion is there |
| --- | --- | --- | --- |
| `/`, `/pt-br`, `/es` | A acquisition | Product comprehension leading to documentation or GitHub clicks | Show specific lab, medication, weight, and device-health workflows with honest release status |
| `/docs` and localized guides | B work | Task completion and correct use | Searchable, readable instructions with local anchors |
| `/support` | C trust | Safe support without exposing health records | Verified GitHub and private vulnerability channels |
| `/privacy` | C trust | Informed data sharing and permission decisions | Publisher policy, local storage, and hosting boundaries |

## New direction

- **Product:** Vezuna helps households organize lab reports, medication routines, weight, and device health metrics locally.
- **Character:** calm, exact, human.
- **World:** lab printouts, medication schedules, bathroom scales, and phone health dashboards.
- **Moments:** one readable lab comparison and a medication schedule with clearly labeled synthetic records; actual app screenshots as supporting evidence.
- **Signature:** a small “confirmed by you” state attached to imported results, reinforcing manual review rather than diagnosis.

## Layout decision

Use a light, continuous editorial surface. Lead with an asymmetric product statement and a readable record workspace. Give lab records, medications, device health, and household exports their own interactive views. Use one accent, local Noto Sans, meaningful 120–200ms transitions, and no ambient loops.

## Screen seen

| Route | Width | Data state | Result |
| --- | --- | --- | --- |
| `/` | 1440px | Synthetic lab, medication, device, and household records plus actual app captures | Lab chart and four workspace views are readable; no horizontal overflow or generic design findings |
| `/` | 390px | Same records with decimal-comma variants | Both lab points fit; all text is legible; no horizontal overflow |
| `/pt-br` | 390px | Decimal-comma lab values | SVG coordinates are valid; no console errors |
| `/es` | 390px | Decimal-comma lab values | SVG coordinates are valid; no console errors |

## Verification notes

- 43 phone, tablet, and desktop route layouts measured.
- Keyboard tab navigation, direct device links, documentation search, and code copying passed.
- Accessibility scans passed on eight representative pages.
- Copy and motion linters from the supplied skill collection pass.
- The mobile lab chart was adjusted after screenshot review so both values remain visible.

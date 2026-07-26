# Bill Design System

- Status: curated foundation
- Owner: project maintainer
- Last verified: 2026-07-19
- Source: `ui-ux-pro-max` product, color, style, UX, chart, and Jetpack Compose searches; curated against `docs/DESIGN.md`, financial-domain constraints, and the museum references listed below

This file is the visual-token source of truth. Product behavior remains in `docs/product-specs/`; Compose implementation rules remain in `docs/FRONTEND.md`.

## Product character

Bill is a personal local ledger, not an enterprise dashboard or trading terminal. It should feel decisive and authored without making uncertain financial evidence look more certain than it is.

- Keep Material 3 for interaction states, focus, semantics, system bars, sheets, and accessibility; do not accept its default visual treatment as the product identity.
- Use a restrained Constructivist and Soviet space-era editorial grammar: asymmetric but measured grids, one clear diagonal thrust, circles/orbits, strong rules, compact labels, and large numeric hierarchy.
- Keep the phone reading order vertical and calm. Geometry establishes direction and grouping; it never tilts body text, inputs, transaction rows, or touch targets.
- Light mode is warm paper with dark ink. Dark mode is a cosmic deep blue with paper-colored text, not pure black with neon accents.
- A screen may have one dominant composition and at most one red-wedge brand gesture. Repeating wedges, stars, rockets, or propaganda-style decoration on every card turns the reference into costume and is forbidden.
- No political insignia, imitation Cyrillic, distressed-poster texture, glassmorphism, fintech gradients, decorative count-up numbers, or looping spectacle.

## Reference method

These museum records are visual research, not assets to copy and not product endorsements:

- [Ivan Picelj, *Beat the Whites with the Red Wedge (after El Lissitzky)*, 1969 — MoMA](https://www.moma.org/collection/works/189492): extract the decisive diagonal, geometric counter-pressure, and limited palette.
- [*Poster of Valentina Tereshkova, First woman cosmonaut*, 1963 — Science Museum Group](https://collection.sciencemuseumgroup.org.uk/objects/co415116/poster-of-valentina-tereshkova-first-woman-cosmonaut): extract portrait-scale hierarchy, orbital framing, and the relationship between image, title, and technical detail.
- [*Into Space!*, Soviet — Smithsonian National Air and Space Museum, A20150394000](https://airandspace.si.edu/collection-objects/space-soviet/nasm_A20150394000): extract upward trajectory, deep-space fields, and a small warm signal against a cool background.

The implementation re-composes these formal methods using original geometry and type. It must not reproduce museum imagery or imply an ideological message.

## Color roles

Use named semantic roles in code. Feature components must not contain raw hex values.

| Token / role | Value | Use | Prohibited use |
| --- | --- | --- | --- |
| `ActionBlue` / primary | `#123B68` | primary action, selected navigation, focus and link emphasis on light surfaces | decorative fields or income-only meaning |
| `OnActionBlue` | `#FFFFFF` | text and icons on `ActionBlue` | body text on paper |
| `RedWedge` / brand signal | `#B92724` | one small wedge, rule, or launch-point accent that identifies Bill | income, expense, success, error, destructive action, or disabled state |
| `Paper` / light background | `#F3EBD8` | main light canvas and paper-colored text on cosmic surfaces | status meaning by itself |
| `PaperSurface` | `#FFF9EA` | cards, sheets, and raised reading surfaces in light mode | pure-white card stacks with excessive elevation |
| `CosmicBlue` / dark background | `#101D3D` | main dark canvas and occasional light-mode hero field | every card or large dense body copy in light mode |
| `CosmicSurface` | `#18284D` | dark-mode cards, sheets, and navigation | substitute for disabled state |
| `SolarGold` / attention | `#D9A326` | orbit marker, unresolved-count accent, and compact highlight with `CosmicBlue` text | warning or confidence meaning without a label/icon |
| `Ink` / on light surface | `#171C2C` | primary text and numbers on paper | text on `CosmicBlue` |
| `PaperInk` / on dark surface | `#F3EBD8` | primary text and numbers on cosmic surfaces | text on `Paper` |
| `MutedInk` | `#596071` | secondary light-mode text | required text below contrast thresholds |
| `ErrorCrimson` / error | `#8E2430` | validated error/destructive state together with text and icon | brand wedge or ordinary expense |
| `SourceHealthy` / success | `#2D6656` | validated source-health state together with text and icon | ordinary income |
| `OutlineInk` | `#74716A` | dividers, rules, and inactive boundaries | sole indication of focus |

Dark-mode interactive blue may use the accessible tonal companion `#9CCBFF`, but `#123B68` remains the canonical brand action blue. `RedWedge` stays a brand signal, never a semantic shortcut. Income, expense, warning, matched, and error states always include a label, sign, icon, or shape in addition to color.

## Typography

- Use the Android system sans-serif stack; do not add a downloadable font dependency.
- Material 3 type roles are the only public typography API.
- Build editorial hierarchy through weight, scale, alignment, and rules; do not fake it with all-caps paragraphs, excessive letter spacing, or condensed text that harms Chinese legibility.
- Money and aligned numeric data use tabular figures when supported.
- Amount hierarchy: display small or headline large for the main balance, title large for transaction amounts, body/label roles elsewhere. A screen gets one display-scale number, not one per card.
- Never shrink body copy below 14sp; support 200% system font scale without hiding required actions.
- Wrap meaningful labels before truncating. Masked account identifiers may use ellipsis when their complete value is available in details.

## Spacing, shape, and elevation

- Base grid: 4dp. Common gaps: 8, 12, 16, 24, and 32dp.
- Portrait phone horizontal gutter: 16dp. Wider fallback windows use 24-32dp gutters and a centered content column no wider than 560dp.
- Minimum Android touch target: 48x48dp with at least 8dp between adjacent targets.
- Structural panels use 0-4dp corners; compact controls use 4-8dp; only modal bottom sheets may use 20dp top corners. The product must not become a stack of identical soft cards.
- Prefer color fields, 1dp rules, spacing, and alignment over shadows. Elevation is reserved for sheets and transient overlays.
- Diagonals and circles are drawn as non-interactive background or edge geometry. They must not reduce the 48dp target, cross readable text, or change layout measurement.
- Respect display cutouts, status/navigation bars, and gesture insets.

## Motion

- Tap feedback begins within 100ms using native Material state layers/ripple.
- Micro-interactions use 150-250ms; sheet/navigation transitions may use up to 300ms.
- Motion explains state or hierarchy. A wedge/orbit transition may travel once along the actual navigation direction, but there is no decorative looping, count-up balance, pulsing status dot, or staged list choreography.
- Keep animations interruptible and honor system reduced-motion/animation-scale settings.

## Icons and data visualization

- Use one Material Symbols/Icons family, consistent weight and 24dp default size.
- Every icon-only action has a localized content description and a 48dp hit target.
- Time trends use a line chart; category comparisons use sorted bars. Provide a textual summary and table/list alternative.
- Avoid pie charts beyond five categories and avoid Sankey diagrams on the phone primary experience.
- Orbital circles, registration marks, and launch trajectories are decorative grammar, not substitutes for chart axes or data points.
- Do not use red/green alone for positive/negative values: include `+`/`-`, arrows, labels, or patterns.

## Navigation

- Current release baseline: portrait phone, one vertical content column, and four labeled top-level destinations — Overview, Drafts, Ledger, Accounts — in the bottom navigation.
- Pending drafts may show a restrained badge; visiting the destination clears only the attention state, not the drafts.
- Settings and privacy are secondary destinations reached from the top app bar.
- This release does not switch to a navigation rail or two-pane review. If the platform exposes a wider, landscape, split-screen, or `sw>=600dp` window, keep a centered, scrollable single-column fallback and preserve access to all actions.
- Back restores scroll, filters, and in-progress edits. Unsaved sheet dismissal requires confirmation.

## Canonical component set

- `MoneyText`: locale-aware amount, tabular figures, privacy mask, explicit sign.
- `LedgerCard`: tonal card with semantic heading and bounded actions.
- `SourceStatusRow`: source, capture method, last safe status, and recovery action.
- `DraftSummaryCard`: evidence count, hard-block explanation, and review action.
- `ReviewBottomSheet`: evidence, editable economic meaning, funding account, impact preview, and one primary confirmation action.
- `ConfidenceExplanation`: plain-language evidence; never exposes raw sensitive payloads in accessibility text.
- `BillNavigationBar`: stable labeled primary navigation for the current portrait-phone release.

## Page overrides

Page-specific files under `pages/` may refine layout only. They may not redefine semantic colors, touch targets, privacy rules, or financial meaning.

## Delivery gates

- Verify portrait compact previews for a small phone and S24 Ultra-class phone as the release UI.
- Separately verify forced landscape, split-screen, and `sw>=600dp` as reachability and state-preservation fallbacks; passing these does not claim a landscape-first visual design.
- Verify light/dark, 200% font scale, TalkBack reading order, and reduced motion.
- All tap targets are at least 48dp; no content is hidden by system bars or fixed navigation.
- Contrast: normal text 4.5:1; large text and non-text controls 3:1.
- Loading, empty, partial, success, and failure states are explicit.
- No real financial information appears in previews, tests, screenshots, logs, or accessibility descriptions.
